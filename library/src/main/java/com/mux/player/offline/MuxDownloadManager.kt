package com.mux.player.offline

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.google.common.collect.Sets
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.mux.player.internal.createLogcatLogger
import com.mux.player.internal.getDrmToken
import com.mux.player.internal.getLicenseUrlHost
import com.mux.player.internal.getPlaybackId
import com.mux.player.media.MediaItems.MUX_VIDEO_DEFAULT_DOMAIN
import com.mux.player.media.MuxDrmSessionManagerProvider
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The caller-facing entry point for Mux offline downloads. [startDownload] prepares an asset
 * (fetching manifests, and for DRM acquiring an offline license) and enqueues it on
 * [MuxDownloadService]; [addListener] observes progress and lifecycle
 *
 * If you'd rather manage your own download lifecycle and datastore, you can skip using this class
 * and use [MuxHlsDownloadCallback] with your own [DownloadHelper], [DownloadService], etc
 */
@OptIn(UnstableApi::class)
object MuxDownloadManager {

  /**
   * The requirements downloads must satisfy to make progress, applied to the `DownloadManager` on
   * first use. The default requires only a network connection, including metered ones — pass
   * [Requirements] including [Requirements.NETWORK_UNMETERED] to [setRequirements] to restrict
   * downloads to unmetered (e.g. Wi-Fi) networks.
   */
  val DEFAULT_REQUIREMENTS: Requirements = Requirements(Requirements.NETWORK)

  private const val TAG = "MuxDownloadManager"

  /**
   * How often in-progress downloads are polled for progress. See [ProgressUpdateHelper].
   */
  private const val PROGRESS_POLL_INTERVAL_MS = 1_000L

  /**
   * Observes offline-download progress and lifecycle changes.
   *
   * All callbacks are delivered on the `DownloadManager`'s application looper, which should be the
   * main thread.
   *
   * Register with [addListener] and unregister with [removeListener].
   */
  interface Listener {
    /**
     * Called whenever a download's [MuxDownload.State] or progress changes, including the
     * initial [MuxDownload.State.STARTING] snapshot emitted by [startDownload].
     *
     * @param download a fresh snapshot of the download at this transition.
     * @param error the cause when [download].state is [MuxDownload.State.FAILED], otherwise `null`.
     */
    fun onDownloadChanged(download: MuxDownload, error: Throwable?)

    /**
     * Called when a download has been removed. [download] is the last snapshot before removal.
     */
    fun onDownloadRemoved(download: MuxDownload) {}

    /**
     * Called when there is a change in whether one or more downloads are stalled *solely* because
     * the `DownloadManager`'s requirements (e.g. network connectivity) are not met.
     */
    fun onWaitingForRequirementsChanged(waitingForRequirements: Boolean) {}
  }

  private val listeners = CopyOnWriteArraySet<Listener>()

  // Guards installation of our single DownloadManager.Listener onto the store's manager, and of the
  // poller that goes with it. A non-null poller means we're installed.
  private val installLock = Any()

  @Volatile
  private var progressUpdateHelper: ProgressUpdateHelper? = null

  private val playbackIdsStarting = ConcurrentHashMap<String, DownloadHelper>()

  /**
   * Playback IDs with a [renewOfflineLicense] in flight, so two can't race on the same asset.
   *
   * Backed by a `ConcurrentHashMap`, so [MutableSet.add] is an atomic test-and-set — it delegates to
   * `put`, and reports whether this caller is the one that claimed [playbackIdsRenewing].
   */
  private val playbackIdsRenewing: MutableSet<String> = Sets.newConcurrentHashSet()

  /**
   * Prepares [mediaItem] for offline playback and enqueues the download.
   *
   * The download id is the item's Mux playback ID. Preparation — fetching the HLS manifests and,
   * for DRM content, acquiring the offline Widevine license — runs asynchronously; during it,
   * listeners see a [MuxDownload.State.STARTING] snapshot. Once prepared, the download states are
   * the similar to media3.
   *
   * @param context any context; the application context is used.
   * @param mediaItem a Mux [MediaItem] (built via `MediaItems.fromMuxPlaybackId`). Must carry a
   *   playback ID; DRM items must also carry playback and DRM tokens.
   * @throws IllegalArgumentException if [mediaItem] is not a Mux media item (no playback ID).
   */
  @MainThread
  fun startDownload(context: Context, mediaItem: MediaItem) {
    val appContext = context.applicationContext
    val playbackId = requireNotNull(mediaItem.getPlaybackId()) {
      "mediaItem is not a Mux MediaItem (no playback ID). Build it with MediaItems.fromMuxPlaybackId."
    }
    val store = MuxPlayerDownloadStore.get(appContext)

    if (this.playbackIdsStarting[playbackId] != null) {
      Log.w(TAG, "Trying to start already-started download for playback ID $playbackId")
      return
    }

    // Announce STARTING before the (async) manifest/license work — there is no DownloadManager
    // entry for this download yet, so this snapshot is the only signal it's in flight.
    dispatchListenerCallOnMain(store) { it.onDownloadChanged(startingSnapshot(playbackId), null) }

    val logger = createLogcatLogger()
    val drmProvider = MuxDrmSessionManagerProvider(
      drmHttpDataSourceFactory = DefaultHttpDataSource.Factory(),
      logger = logger,
    )
    val mediaSource = MuxOfflineCmafHlsMediaSource.create(
      dataSourceFactory = DefaultHttpDataSource.Factory(),
      mediaItem = mediaItem,
      drmSessionManagerProvider = drmProvider,
    )
    val helper = createMuxHlsDownloadHelper(appContext, mediaSource)

    this.playbackIdsStarting[playbackId] = helper
    helper.prepare(
      MuxHlsDownloadCallback(
        mediaSource = mediaSource,
        drmProvider = drmProvider,
        playbackId = playbackId,
        drmToken = mediaItem.getDrmToken(),
        licenseEndpointHost = mediaItem.getLicenseUrlHost(),
        ioExecutor = store.ioExecutor,
        onReady = { request ->
          // Start the prepared Download unless it was removed in the meantime
          if (playbackIdsStarting[playbackId] != null) {
            DownloadService.sendAddDownload(
              appContext,
              MuxDownloadService::class.java,
              request,
              /* foreground = */ false,
            )
            playbackIdsStarting.remove(playbackId)
          } else {
            // If removed we might need to try to drop the license from the cdm cache
            request.keySetId?.let { dropOfflineLicense(it) }
          }
        },
        onError = { e ->
          // Preparation failed before the DownloadManager ever saw this download, so report here
          dispatchListenerCallOnMain(store) { it.onDownloadChanged(failedSnapshot(playbackId), e) }
          playbackIdsStarting.remove(playbackId)
        },
      )
    )
  }

  /**
   * Removes a download: deletes its media via the `DownloadManager` and drops the local offline
   * license, if any. Safe to call for an unknown [playbackId] (no-op). Runs off the caller thread.
   */
  fun removeDownload(context: Context, playbackId: String) {
    val appContext = context.applicationContext
    val store = MuxPlayerDownloadStore.get(appContext)
    store.ioExecutor.execute {
      // Capture the keySetId before the index row is gone; removal is what actually deletes media.
      val keySetId = runCatching { store.downloadIndex.getDownload(playbackId)?.request?.keySetId }
        .getOrNull()
      DownloadService.sendRemoveDownload(
        appContext, MuxDownloadService::class.java, playbackId, /* foreground = */ false,
      )
      keySetId?.let { dropOfflineLicense(it) }
    }

    this.playbackIdsStarting.remove(playbackId)

  }

  /**
   * Renews the offline DRM license for the already-downloaded asset [playbackId], so it can be
   * played offline again without re-downloading any media.
   *
   * Offline Widevine licenses run out — see [MuxDownload.State.EXPIRED] — and renewing one needs a
   * network, so this is something to do opportunistically while the device is online, before the
   * user next goes offline.
   *
   * DRM tokens are short-lived, so [drmToken] must be a *fresh* one from your backend.
   *
   * Returns once the license is renewed (requires network). [Listener]s hear about nrewals too
   *
   * @param context any context; the application context is used.
   * @param playbackId the Mux playback ID of a *fully downloaded*, DRM-protected asset.
   * @param drmToken a fresh DRM token authorizing a persistent (offline) license for [playbackId].
   * @param domain your Mux Video [custom domain](https://docs.mux.com/guides/use-a-custom-domain-for-streaming),
   *   if you downloaded the asset with one. The domain provided should match the one used originally
   * @return a fresh snapshot of the download, with its license state checked.
   * @throws IllegalArgumentException if nothing is downloaded for [playbackId].
   * @throws IllegalStateException if the download isn't finished, isn't DRM-protected, or already
   *   has a renewal in flight.
   * @throws IOException if the license request fails, or the renewed license can't be saved.
   */
  suspend fun renewOfflineLicense(
    context: Context,
    playbackId: String,
    drmToken: String,
    domain: String? = null,
  ): MuxDownload {
    val store = MuxPlayerDownloadStore.get(context.applicationContext)
    return withContext(store.ioExecutor.asCoroutineDispatcher()) {
      renewOfflineLicenseBlocking(store, playbackId, drmToken, domain)
    }
  }

  /**
   * Renews the license identified by the download's stored `keySetId`, persists the renewed one, and
   * returns a checked snapshot. Blocking; callers run it on [MuxPlayerDownloadStore.ioExecutor].
   *
   * Internal rather than private so tests can drive it against a fake store.
   */
  internal fun renewOfflineLicenseBlocking(
    store: MuxPlayerDownloadStore,
    playbackId: String,
    drmToken: String,
    domain: String?,
  ): MuxDownload {
    if (!playbackIdsRenewing.add(playbackId)) {
      throw IllegalStateException(
        "a license renewal for playback ID $playbackId is already in flight"
      )
    }

    try {
      val download = store.downloadIndex.getDownload(playbackId)
        ?: throw IllegalArgumentException("nothing is downloaded for Mux playback ID $playbackId")

      // An expired download is still STATE_COMPLETED in the index (see MuxDownload.State.EXPIRED),
      // so this admits all finished downloads that might have reasonably expired
      check(download.state == Download.STATE_COMPLETED) {
        "the download for $playbackId isn't fully downloaded" +
            " (state ${download.state.toMuxState()}), so there's no settled license to renew"
      }
      val oldKeySetId = checkNotNull(download.request.keySetId) {
        "the download for $playbackId has no offline license to renew; it isn't DRM-protected"
      }

      val drmProvider = MuxDrmSessionManagerProvider(
        drmHttpDataSourceFactory = DefaultHttpDataSource.Factory(),
        logger = createLogcatLogger(),
      )
      val newKeySetId = try {
        drmProvider.renewOfflineLicense(
          playbackId = playbackId,
          drmToken = drmToken,
          licenseEndpointHost = "license.${domain ?: MUX_VIDEO_DEFAULT_DOMAIN}",
          keySetId = oldKeySetId,
        )
      } catch (e: IOException) {
        throw e
      } catch (e: Exception) {
        throw IOException("couldn't renew the offline license for $playbackId", e)
      }

      // write directly to index. DownloadManager/DownloadService would re-download the media
      val renewed = download.copyWithKeySetId(newKeySetId)
      store.downloadIndex.putDownload(renewed)

      if (!newKeySetId.contentEquals(oldKeySetId)) {
        // only drop the old one if we really got new license bytes
        dropOfflineLicense(oldKeySetId)
      }

      val snapshot = renewed.toMuxDownloadCheckingLicense()
      dispatchListenerCallOnMain(store) { it.onDownloadChanged(snapshot, null) }
      return snapshot
    } finally {
      playbackIdsRenewing.remove(playbackId)
    }
  }

  /** Pauses all downloads. They can be resumed with [resumeAll]. */
  fun pauseAll(context: Context) {
    DownloadService.sendPauseDownloads(
      context.applicationContext, MuxDownloadService::class.java, /* foreground = */ false,
    )
  }

  /** Resumes all downloads paused by [pauseAll]. */
  fun resumeAll(context: Context) {
    DownloadService.sendResumeDownloads(
      context.applicationContext, MuxDownloadService::class.java, /* foreground = */ false,
    )
  }

  /**
   * Sets the [Requirements] that must be met for downloads to make progress, replacing the current
   * ones (initially [DEFAULT_REQUIREMENTS], i.e. network only). Downloads that don't meet the new
   * requirements move to a waiting state until they do.
   *
   * For example, to restrict downloads to unmetered (e.g. Wi-Fi) networks:
   * `setRequirements(context, Requirements(Requirements.NETWORK_UNMETERED))`.
   */
  fun setRequirements(context: Context, requirements: Requirements) {
    DownloadService.sendSetRequirements(
      context.applicationContext, MuxDownloadService::class.java, requirements, /* foreground = */ false,
    )
  }

  /**
   * Registers [listener] to observe download progress and lifecycle. Callbacks are delivered on the
   * `DownloadManager`'s application looper. Non-blocking. Remove it with [removeListener].
   */
  fun addListener(context: Context, listener: Listener) {
    val store = MuxPlayerDownloadStore.get(context.applicationContext)
    val poller = ensureManagerListenerInstalled(store)
    listeners.add(listener)
    poller.syncAsync()
  }

  /** Unregisters a [listener] previously added with [addListener]. */
  fun removeListener(listener: Listener) {
    listeners.remove(listener)
    progressUpdateHelper?.syncAsync()
  }

  /**
   * All downloads known to the index, in any state.
   *
   * Completed downloads have their offline license checked against the device's CDM, so an asset
   * whose license ran out is reported as [MuxDownload.State.EXPIRED].
   */
  suspend fun allDownloads(context: Context): List<MuxDownload> {
    val store = MuxPlayerDownloadStore.get(context.applicationContext)
    return withContext(store.ioExecutor.asCoroutineDispatcher()) {
      store.downloadIndex.readAll()
    }
  }

  /**
   * Only fully-downloaded assets. Their offline licenses are checked against the device's CDM, so
   * these are [MuxDownload.State.COMPLETED] *or* [MuxDownload.State.EXPIRED] — an expired download
   * is still fully on disk, so it's still listed here, just not playable. Check the state before
   * offering one for playback.
   */
  suspend fun completedDownloads(context: Context): List<MuxDownload> {
    val store = MuxPlayerDownloadStore.get(context.applicationContext)
    return withContext(store.ioExecutor.asCoroutineDispatcher()) {
      store.downloadIndex.readAll(Download.STATE_COMPLETED)
    }
  }

  /**
   * The download for [playbackId], or `null` if there is none. Runs off the main thread. A completed
   * download whose offline license has run out is reported as [MuxDownload.State.EXPIRED].
   *
   * You don't need to call this in order to play a downloaded asset.
   * Just use [com.mux.player.media.MediaItems.forMuxDownload] and add the returned MediaItem.
   */
  suspend fun getDownload(context: Context, playbackId: String): MuxDownload? {
    val store = MuxPlayerDownloadStore.get(context.applicationContext)
    return withContext(store.ioExecutor.asCoroutineDispatcher()) {
      store.downloadIndex.getDownload(playbackId)?.toMuxDownloadCheckingLicense()
    }
  }

  /** Java twin of [allDownloads]. */
  fun allDownloadsFuture(context: Context): ListenableFuture<List<MuxDownload>> {
    val store = MuxPlayerDownloadStore.get(context.applicationContext)
    return submitToIoExecutor(store) { store.downloadIndex.readAll() }
  }

  /** Java twin of [completedDownloads]. */
  fun completedDownloadsFuture(context: Context): ListenableFuture<List<MuxDownload>> {
    val store = MuxPlayerDownloadStore.get(context.applicationContext)
    return submitToIoExecutor(store) { store.downloadIndex.readAll(Download.STATE_COMPLETED) }
  }

  /** Java twin of [getDownload]. */
  fun getDownloadFuture(context: Context, playbackId: String): ListenableFuture<MuxDownload?> {
    val store = MuxPlayerDownloadStore.get(context.applicationContext)
    return submitToIoExecutor(store) {
      store.downloadIndex.getDownload(playbackId)?.toMuxDownloadCheckingLicense()
    }
  }

  /** Java twin of [renewOfflineLicense]. */
  @JvmOverloads
  fun renewOfflineLicenseFuture(
    context: Context,
    playbackId: String,
    drmToken: String,
    domain: String? = null,
  ): ListenableFuture<MuxDownload> {
    val store = MuxPlayerDownloadStore.get(context.applicationContext)
    return submitToIoExecutor(store) {
      renewOfflineLicenseBlocking(store, playbackId, drmToken, domain)
    }
  }

  private fun ensureManagerListenerInstalled(
    store: MuxPlayerDownloadStore
  ): ProgressUpdateHelper = synchronized(installLock) {
    progressUpdateHelper ?: ProgressUpdateHelper(store.downloadManager).also { poller ->
      progressUpdateHelper = poller
      store.downloadManager.addListener(ManagerListener)
    }
  }

  /** Translates the shared `DownloadManager.Listener` into [Listener] callbacks. */
  private object ManagerListener : DownloadManager.Listener {

    override fun onInitialized(downloadManager: DownloadManager) {
      // Downloads restored from the index may resume immediately, without a state change
      progressUpdateHelper?.sync()
    }

    override fun onDownloadChanged(
      downloadManager: DownloadManager,
      download: Download,
      finalException: Exception?,
    ) {
      val snapshot = download.toMuxDownload()
      listeners.forEach { it.onDownloadChanged(snapshot, finalException) }
      progressUpdateHelper?.sync()
    }

    override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
      val snapshot = download.toMuxDownload()
      listeners.forEach { it.onDownloadRemoved(snapshot) }
      progressUpdateHelper?.sync()
    }

    override fun onDownloadsPausedChanged(
      downloadManager: DownloadManager,
      downloadsPaused: Boolean,
    ) {
      progressUpdateHelper?.sync()
    }

    override fun onIdle(downloadManager: DownloadManager) {
      progressUpdateHelper?.sync()
    }

    override fun onWaitingForRequirementsChanged(
      downloadManager: DownloadManager,
      waitingForRequirements: Boolean,
    ) {
      listeners.forEach { it.onWaitingForRequirementsChanged(waitingForRequirements) }
      progressUpdateHelper?.sync()
    }
  }

  /**
   * Polls in-progress downloads and reports their progress to [listeners].
   *
   * Media3's `DownloadManager` only notifies its listeners on state transitions, so progress
   * between state transitions (ie, progress percentage/byte count updates)
   *
   * Call [sync] to automatically start or stop polling based on the state of the DownloadManager.
   */
  private class ProgressUpdateHelper(private val downloadManager: DownloadManager) : Runnable {

    private val handler = Handler(downloadManager.applicationLooper)

    /** Bytes last reported, per playback ID, so a stalled download isn't re-reported each tick. */
    private val lastReportedBytes = HashMap<String, Long>()

    private var polling = false

    /** Starts or stops the loop so that it's running exactly when there's progress to report. */
    fun sync() {
      val shouldPoll =
        listeners.isNotEmpty() && downloadManager.currentDownloads.any(::isInProgress)
      if (shouldPoll == polling) return

      polling = shouldPoll
      if (shouldPoll) {
        handler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
      } else {
        handler.removeCallbacks(this)
        lastReportedBytes.clear()
      }
    }

    /** [sync], from any thread. */
    fun syncAsync() {
      handler.post { sync() }
    }

    override fun run() {
      val inProgress = downloadManager.currentDownloads.filter(::isInProgress)
      // Downloads that left the list since the last tick shouldn't hold onto their old byte count
      lastReportedBytes.keys.retainAll(inProgress.mapTo(mutableSetOf()) { it.request.id })

      inProgress.forEach { download ->
        val bytesDownloaded = download.bytesDownloaded
        if (lastReportedBytes.put(download.request.id, bytesDownloaded) != bytesDownloaded) {
          val snapshot = download.toMuxDownload()
          listeners.forEach { it.onDownloadChanged(snapshot, null) }
        }
      }

      if (inProgress.isNotEmpty() && listeners.isNotEmpty()) {
        handler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
      } else {
        polling = false
        lastReportedBytes.clear()
      }
    }

    /** Whether [download] is one whose progress moves, and so is worth polling. */
    private fun isInProgress(download: Download): Boolean =
      download.state == Download.STATE_DOWNLOADING || download.state == Download.STATE_RESTARTING
  }

  /**
   * Posts [block] to each listener on the `DownloadManager`'s application looper, so our synthetic
   * STARTING/FAILED snapshots are delivered on the same thread as the manager's own callbacks.
   */
  private fun dispatchListenerCallOnMain(
    store: MuxPlayerDownloadStore,
    block: (Listener) -> Unit,
  ) {
    Handler(store.downloadManager.applicationLooper).post {
      listeners.forEach(block)
    }
  }

  private fun <T> submitToIoExecutor(
    store: MuxPlayerDownloadStore,
    block: () -> T,
  ): ListenableFuture<T> {
    val future = SettableFuture.create<T>()
    store.ioExecutor.execute {
      try {
        future.set(block())
      } catch (t: Throwable) {
        future.setException(t)
      }
    }
    return future
  }

  private fun DownloadIndex.readAll(@Download.State vararg states: Int): List<MuxDownload> =
    getDownloads(*states).use { cursor ->
      buildList {
        while (cursor.moveToNext()) {
          add(cursor.download)
        }
      }
    }.toMuxDownloadsCheckingLicenses()

  /** [toMuxDownloadsCheckingLicenses] for a single download. */
  private fun Download.toMuxDownloadCheckingLicense(): MuxDownload =
    listOf(this).toMuxDownloadsCheckingLicenses().single()

  /**
   * Snapshots [this], reporting any completed download whose offline license the CDM says is spent
   * as [MuxDownload.State.EXPIRED] instead of [MuxDownload.State.COMPLETED].
   *
   * Blocking — it opens DRM sessions — so this is only for the query APIs, which run on
   * [MuxPlayerDownloadStore.ioExecutor]. The listener callbacks are on the application looper and
   * can't do this; see [MuxDownload.State.EXPIRED].
   */
  private fun List<Download>.toMuxDownloadsCheckingLicenses(): List<MuxDownload> {
    // Only a finished download's license is worth asking about: one still in flight has a license
    // that was acquired moments ago, and a clear download has none at all.
    val licensesToCheck = mapNotNull { download ->
      download.request.keySetId
        ?.takeIf { download.state == Download.STATE_COMPLETED }
        ?.let { keySetId -> download.request.id to keySetId }
    }
    if (licensesToCheck.isEmpty()) {
      return map { it.toMuxDownload() }
    }

    val expiredPlaybackIds = try {
      // One helper for the whole batch; each query opens and closes its own CDM session anyway
      val licenseHelper = localOfflineLicenseHelper()
      try {
        licensesToCheck
          .filter { (_, keySetId) -> licenseHelper.isOfflineLicenseExpired(keySetId) }
          .mapTo(mutableSetOf()) { (playbackId, _) -> playbackId }
      } finally {
        licenseHelper.release()
      }
    } catch (e: Exception) {
      // A device that can't tell us about its licenses shouldn't fail the whole query; the states
      // we already have from the index are still worth reporting
      Log.w(TAG, "couldn't check offline licenses for expiration", e)
      emptySet()
    }

    return map { it.toMuxDownload(expired = it.request.id in expiredPlaybackIds) }
  }

  private fun startingSnapshot(playbackId: String): MuxDownload =
    MuxDownload(
      playbackId = playbackId,
      state = MuxDownload.State.STARTING,
      percentDownloaded = C.PERCENTAGE_UNSET.toFloat(),
      bytesDownloaded = 0L,
      totalBytes = C.LENGTH_UNSET.toLong(),
    )

  private fun failedSnapshot(playbackId: String): MuxDownload =
    MuxDownload(
      playbackId = playbackId,
      state = MuxDownload.State.FAILED,
      percentDownloaded = C.PERCENTAGE_UNSET.toFloat(),
      bytesDownloaded = 0L,
      totalBytes = C.LENGTH_UNSET.toLong(),
    )

  private fun Download.toMuxDownload(expired: Boolean = false): MuxDownload =
    MuxDownload(
      playbackId = request.id,
      state = if (expired) MuxDownload.State.EXPIRED else state.toMuxState(),
      percentDownloaded = percentDownloaded,
      bytesDownloaded = bytesDownloaded,
      totalBytes = contentLength,
    )

  /**
   * [this], with its `DownloadRequest` pointing at the offline license [keySetId].
   *
   * media3 keeps `Download.progress` package-private, so the byte counts are copied across by hand —
   * the shorter `Download` constructor would zero out what [MuxDownload] reports.
   */
  private fun Download.copyWithKeySetId(keySetId: ByteArray): Download =
    Download(
      request.copyWithKeySetId(keySetId),
      state,
      startTimeMs,
      /* updateTimeMs = */ System.currentTimeMillis(),
      contentLength,
      stopReason,
      failureReason,
      DownloadProgress().apply {
        bytesDownloaded = this@copyWithKeySetId.bytesDownloaded
        percentDownloaded = this@copyWithKeySetId.percentDownloaded
      },
    )

  private fun Int.toMuxState(): MuxDownload.State = when (this) {
    Download.STATE_QUEUED -> MuxDownload.State.QUEUED
    Download.STATE_STOPPED -> MuxDownload.State.STOPPED
    Download.STATE_DOWNLOADING -> MuxDownload.State.DOWNLOADING
    Download.STATE_COMPLETED -> MuxDownload.State.COMPLETED
    Download.STATE_FAILED -> MuxDownload.State.FAILED
    Download.STATE_REMOVING -> MuxDownload.State.REMOVING
    Download.STATE_RESTARTING -> MuxDownload.State.DOWNLOADING
    else -> MuxDownload.State.QUEUED
  }
}
