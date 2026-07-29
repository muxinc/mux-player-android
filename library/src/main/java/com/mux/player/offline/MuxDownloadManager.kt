package com.mux.player.offline

import android.content.Context
import android.media.MediaDrm
import android.os.Build
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.mux.player.internal.createLogcatLogger
import com.mux.player.internal.getDrmToken
import com.mux.player.internal.getLicenseUrlHost
import com.mux.player.internal.getPlaybackId
import com.mux.player.media.MuxDrmSessionManagerProvider
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The caller-facing entry point for Mux offline downloads.
 *
 * Most apps need nothing else. [startDownload] prepares an asset (fetching manifests, and for DRM
 * acquiring an offline license) and enqueues it on [MuxDownloadService]; [addListener] observes
 * progress and lifecycle as [MuxDownload] snapshots; the enumeration methods list what's on disk.
 * Everything is backed by the process-wide [MuxPlayerDownloadStore] — the same `DownloadManager` and
 * `DownloadIndex` the service acts on — so this facade and the service always agree.
 *
 * This is a plain `object`. Kotlin callers use `MuxDownloadManager.startDownload(...)`; Java callers
 * use `MuxDownloadManager.INSTANCE.startDownload(...)` (kept as instance methods so they're mockable
 * without static mocking). Java callers that want the enumeration reads use the `*Future` variants.
 */
@OptIn(UnstableApi::class)
object MuxDownloadManager {

  /**
   * Observes offline-download progress and lifecycle changes.
   *
   * Callbacks are driven by Media3's `DownloadManager.Listener` (plus Mux's
   * [MuxDownload.State.STARTING] phase), translated to deliver [MuxDownload] snapshots instead of
   * raw Media3 types. All callbacks are delivered on the `DownloadManager`'s application looper (the
   * main thread in normal use).
   *
   * Register with [addListener] and unregister with [removeListener].
   */
  interface Listener {
    /**
     * Called whenever a download's [state][MuxDownload.State] or progress changes, including the
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

  // Guards installation of our single DownloadManager.Listener onto the store's manager.
  private val installLock = Any()
  private var installed = false

  /**
   * Prepares [mediaItem] for offline playback and enqueues the download.
   *
   * The download id is the item's Mux playback ID. Preparation — fetching the HLS manifests and,
   * for DRM content, acquiring the offline Widevine license — runs asynchronously; during it,
   * listeners see a [MuxDownload.State.STARTING] snapshot. Once prepared, the resulting
   * `DownloadRequest` is handed to [MuxDownloadService] and the `DownloadManager` takes over
   * ([MuxDownload.State.QUEUED] onward). If preparation fails before the download is queued, a
   * [MuxDownload.State.FAILED] snapshot is delivered to listeners with the cause.
   *
   * @param context any context; the application context is used.
   * @param mediaItem a Mux [MediaItem] (built via `MediaItems.fromMuxPlaybackId`). Must carry a
   *   playback ID; DRM items must also carry a DRM token.
   * @throws IllegalArgumentException if [mediaItem] is not a Mux media item (no playback ID).
   */
  fun startDownload(context: Context, mediaItem: MediaItem) {
    val appContext = context.applicationContext
    val playbackId = requireNotNull(mediaItem.getPlaybackId()) {
      "mediaItem is not a Mux MediaItem (no playback ID). Build it with MediaItems.fromMuxPlaybackId."
    }
    val store = MuxPlayerDownloadStore.get(appContext)

    // Announce STARTING before the (async) manifest/license work — there is no DownloadManager
    // entry for this download yet, so this snapshot is the only signal it's in flight.
    dispatch(store) { it.onDownloadChanged(startingSnapshot(playbackId), null) }

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
    helper.prepare(
      MuxHlsDownloadCallback(
        mediaSource = mediaSource,
        drmProvider = drmProvider,
        playbackId = playbackId,
        drmToken = mediaItem.getDrmToken(),
        licenseEndpointHost = mediaItem.getLicenseUrlHost(),
        ioExecutor = store.ioExecutor,
        onReady = { request ->
          DownloadService.sendAddDownload(
            appContext,
            MuxDownloadService::class.java,
            request,
            /* foreground = */ false,
          )
        },
        onError = { e ->
          // Preparation failed before the DownloadManager ever saw this download, so the manager's
          // listener won't report it — surface the failure ourselves.
          dispatch(store) { it.onDownloadChanged(failedSnapshot(playbackId), e) }
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
   * Registers [listener] to observe download progress and lifecycle. Callbacks are delivered on the
   * `DownloadManager`'s application looper (the main thread in normal use). Non-blocking. Remove it
   * with [removeListener].
   */
  fun addListener(context: Context, listener: Listener) {
    val store = MuxPlayerDownloadStore.get(context.applicationContext)
    ensureManagerListenerInstalled(store)
    listeners.add(listener)
  }

  /** Unregisters a [listener] previously added with [addListener]. */
  fun removeListener(listener: Listener) {
    listeners.remove(listener)
  }

  /** All downloads known to the index, in any state. Runs the SQLite read off the main thread. */
  suspend fun allDownloads(context: Context): List<MuxDownload> {
    val store = MuxPlayerDownloadStore.get(context.applicationContext)
    return withContext(store.ioExecutor.asCoroutineDispatcher()) {
      store.downloadIndex.readAll()
    }
  }

  /** Only fully-downloaded assets ([MuxDownload.State.COMPLETED]). Runs off the main thread. */
  suspend fun getCompletedDownloads(context: Context): List<MuxDownload> {
    val store = MuxPlayerDownloadStore.get(context.applicationContext)
    return withContext(store.ioExecutor.asCoroutineDispatcher()) {
      store.downloadIndex.readAll(Download.STATE_COMPLETED)
    }
  }

  /** The download for [playbackId], or `null` if there is none. Runs off the main thread. */
  suspend fun getDownload(context: Context, playbackId: String): MuxDownload? {
    val store = MuxPlayerDownloadStore.get(context.applicationContext)
    return withContext(store.ioExecutor.asCoroutineDispatcher()) {
      store.downloadIndex.getDownload(playbackId)?.toMuxDownload()
    }
  }

  /** Java twin of [allDownloads]. */
  fun allDownloadsFuture(context: Context): ListenableFuture<List<MuxDownload>> {
    val store = MuxPlayerDownloadStore.get(context.applicationContext)
    return submit(store) { store.downloadIndex.readAll() }
  }

  /** Java twin of [getCompletedDownloads]. */
  fun completedDownloadsFuture(context: Context): ListenableFuture<List<MuxDownload>> {
    val store = MuxPlayerDownloadStore.get(context.applicationContext)
    return submit(store) { store.downloadIndex.readAll(Download.STATE_COMPLETED) }
  }

  /** Java twin of [getDownload]. */
  fun getDownloadFuture(context: Context, playbackId: String): ListenableFuture<MuxDownload?> {
    val store = MuxPlayerDownloadStore.get(context.applicationContext)
    return submit(store) { store.downloadIndex.getDownload(playbackId)?.toMuxDownload() }
  }

  private fun ensureManagerListenerInstalled(store: MuxPlayerDownloadStore) {
    synchronized(installLock) {
      if (installed) return
      store.downloadManager.addListener(ManagerListener)
      installed = true
    }
  }

  /** Translates the shared `DownloadManager.Listener` into [Listener] callbacks. */
  private object ManagerListener : DownloadManager.Listener {
    override fun onDownloadChanged(
      downloadManager: DownloadManager,
      download: Download,
      finalException: Exception?,
    ) {
      val snapshot = download.toMuxDownload()
      listeners.forEach { it.onDownloadChanged(snapshot, finalException) }
    }

    override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
      val snapshot = download.toMuxDownload()
      listeners.forEach { it.onDownloadRemoved(snapshot) }
    }

    override fun onWaitingForRequirementsChanged(
      downloadManager: DownloadManager,
      waitingForRequirements: Boolean,
    ) {
      listeners.forEach { it.onWaitingForRequirementsChanged(waitingForRequirements) }
    }
  }

  /**
   * Posts [block] to each listener on the `DownloadManager`'s application looper, so our synthetic
   * STARTING/FAILED snapshots are delivered on the same thread as the manager's own callbacks.
   */
  private fun dispatch(
    store: MuxPlayerDownloadStore,
    block: (Listener) -> Unit,
  ) {
    Handler(store.downloadManager.applicationLooper).post {
      listeners.forEach(block)
    }
  }

  private fun <T> submit(
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
          add(cursor.download.toMuxDownload())
        }
      }
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

  private fun Download.toMuxDownload(): MuxDownload =
    MuxDownload(
      playbackId = request.id,
      state = state.toMuxState(),
      percentDownloaded = percentDownloaded,
      bytesDownloaded = bytesDownloaded,
      totalBytes = contentLength,
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

  /**
   * Local-only purge of the offline license keyed by [keySetId]. No network and no DRM token — Mux
   * enforces no offline-license quota, so there is no server-side release. Best-effort.
   */
  private fun dropOfflineLicense(keySetId: ByteArray) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return // no per-license purge API pre-29
    val mediaDrm = try {
      MediaDrm(C.WIDEVINE_UUID)
    } catch (_: Exception) {
      return
    }
    try {
      mediaDrm.removeOfflineLicense(keySetId)
    } catch (_: Exception) {
      // best-effort; the DownloadRequest reference is already gone, so any residue is unreferenced
    } finally {
      mediaDrm.close()
    }
  }
}