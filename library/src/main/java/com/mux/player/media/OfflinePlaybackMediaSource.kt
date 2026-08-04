package com.mux.player.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import java.io.IOException
import java.util.UUID

/**
 * Plays a Mux asset that was downloaded for offline playback, resolving which [MediaSource] to
 * actually use on the *playback* thread rather than in
 * [MuxMediaSourceFactory.createMediaSource].
 *
 * The point of the indirection is threading. `createMediaSource` runs on the app's thread — the one
 * that called `Player.setMediaItem` — so it can't read the download index or open the download
 * cache without doing disk I/O on the main thread. [prepareSourceInternal], by contrast, runs on
 * the playback thread, where blocking reads are merely slow instead of an ANR. So this source
 * publishes no [Timeline] of its own: it waits until the player prepares it, resolves the download,
 * and then hands the real source's timeline through.
 *
 * A download that can't be resolved (never downloaded, or an unreadable index) is reported through
 * [maybeThrowSourceInfoRefreshError], which reaches the app as a [PlaybackException] on
 * `Player.Listener.onPlayerError` — the same channel as a failed network manifest. That's deliberate:
 * a download can disappear between building the [MediaItem] and preparing it (the user deletes it,
 * or the app's own cleanup removes it), so this is a recoverable playback failure and not a
 * programming error worth throwing at whoever called `setMediaItem`.
 *
 * @param mediaItem the `mux_offline` item being played. Used until the child source is prepared.
 * @param playbackId the Mux playback ID, which is also the download's ID.
 * @param downloadIndex the index to look the download up in. Resolve this on the app's thread — see
 *   [MuxMediaSourceFactory.createOfflineMediaSource] for why.
 * @param downloadCache the cache holding the downloaded media, opened lazily so the (blocking) open
 *   happens on the playback thread.
 */
@OptIn(UnstableApi::class)
internal class OfflinePlaybackMediaSource(
  private val mediaItem: MediaItem,
  private val playbackId: String,
  private val downloadIndex: DownloadIndex,
  private val downloadCache: () -> Cache,
) : CompositeMediaSource<Void?>() {

  /**
   * The real source, once resolved. [CompositeMediaSource] keeps its children private, so we hold
   * onto it for [createPeriod] and [releasePeriod] to delegate to.
   */
  private var childSource: MediaSource? = null

  /** Set when the download couldn't be resolved; thrown from [maybeThrowSourceInfoRefreshError]. */
  private var resolutionError: IOException? = null

  override fun getMediaItem(): MediaItem = mediaItem

  override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
    super.prepareSourceInternal(mediaTransferListener)

    val child = try {
      resolveDownloadedSource()
    } catch (e: IOException) {
      // prepareSourceInternal isn't allowed to throw IOException, and throwing anything here would
      // be reported as an unexpected error with a useless error code. Stash it for
      // maybeThrowSourceInfoRefreshError, which the player polls while it waits for a timeline.
      resolutionError = e
      return
    }

    childSource = child
    prepareChildSource(null, child)
  }

  @Throws(IOException::class)
  private fun resolveDownloadedSource(): MediaSource {
    val download = downloadIndex.getDownload(playbackId)
      ?: throw DataSourceException(
        "No offline download for Mux playback ID $playbackId",
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
      )

    val drmSessionManager = download.request.keySetId
      ?.let { offlinePlaybackDrmSessionManager(it) }
      ?: DrmSessionManager.DRM_UNSUPPORTED

    return DownloadHelper.createMediaSource(
      download.request,
      cacheOnlyDataSourceFactory(downloadCache()),
      drmSessionManager,
    )
  }

  override fun onChildSourceInfoRefreshed(
    childSourceId: Void?,
    mediaSource: MediaSource,
    newTimeline: Timeline,
  ) {
    // Passed through unchanged: one child, one window, so period IDs map 1:1 and createPeriod can
    // delegate straight to the child.
    refreshSourceInfo(newTimeline)
  }

  override fun createPeriod(
    id: MediaSource.MediaPeriodId,
    allocator: Allocator,
    startPositionUs: Long,
  ): MediaPeriod =
    // Only reachable after we published a Timeline, which only happens once the child is prepared.
    checkNotNull(childSource).createPeriod(id, allocator, startPositionUs)

  override fun releasePeriod(mediaPeriod: MediaPeriod) {
    checkNotNull(childSource).releasePeriod(mediaPeriod)
  }

  override fun maybeThrowSourceInfoRefreshError() {
    // CompositeMediaSource's implementation only loops over children, so without this a failed
    // resolution would leave the player buffering forever instead of reporting an error.
    resolutionError?.let { throw it }
    super.maybeThrowSourceInfoRefreshError()
  }

  override fun releaseSourceInternal() {
    super.releaseSourceInternal()
    childSource = null
    resolutionError = null
  }
}

/**
 * A [DrmSessionManager] that plays back using the offline license identified by [keySetId], without
 * going to the network for it.
 */
@OptIn(UnstableApi::class)
private fun offlinePlaybackDrmSessionManager(keySetId: ByteArray): DrmSessionManager =
  DefaultDrmSessionManager.Builder()
    .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
    .build(FailingDrmCallback())
    .apply { setMode(DefaultDrmSessionManager.MODE_PLAYBACK, keySetId) }

@OptIn(UnstableApi::class)
private fun cacheOnlyDataSourceFactory(cache: Cache): DataSource.Factory =
  CacheDataSource.Factory().apply {
    setCache(cache)
    setUpstreamDataSourceFactory(null) // downloaded assets should never need to go online
  }

@OptIn(UnstableApi::class)
private class FailingDrmCallback : MediaDrmCallback {
  override fun executeProvisionRequest(
    uuid: UUID,
    request: ExoMediaDrm.ProvisionRequest
  ): MediaDrmCallback.Response {
    throw IOException("On-disk downloads should never need network")
  }

  override fun executeKeyRequest(
    uuid: UUID,
    request: ExoMediaDrm.KeyRequest
  ): MediaDrmCallback.Response {
    throw IOException("On-disk downloads should never need network")
  }
}