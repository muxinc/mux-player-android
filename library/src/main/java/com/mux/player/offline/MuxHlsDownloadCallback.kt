package com.mux.player.offline

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.mux.player.media.MediaItems
import com.mux.player.media.MuxDrmSessionManagerProvider
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Offline-download prepare callback that works with Mux's CMAF HLS VOD streams, including DRM ones
 *
 * 1. selects all audio and subtitle renditions for download
 * 2. reads the captured video [androidx.media3.common.DrmInitData] off [mediaSource] — the multivariant
 *    `#EXT-X-SESSION-KEY`, falling back to a media playlist's `#EXT-X-KEY`;
 * 3. (off the caller's looper, on [ioExecutor]) acquires the offline Widevine license → `keySetId`
 * 4. builds a [androidx.media3.exoplayer.offline.DownloadRequest] keyed by **playbackId**
 * 5. emits it via [onReady] and releases the helper, waiting for license acquisition if required
 *
 * Clear content is downloaded with no `keySetId`.
 *
 * Any failure routes to [onError].
 *
 * @param mediaSource the same capturing source passed to [createMuxHlsDownloadHelper]; its captures
 *   are complete by the time this callback fires.
 * @param playbackId the Mux playback ID being downloaded; used as the [androidx.media3.exoplayer.offline.DownloadRequest] id.
 * @param drmToken a DRM token authorizing a persistent license, or null for clear content.
 * @param licenseEndpointHost the Mux license server host, e.g. `license.mux.com`.
 */
@OptIn(UnstableApi::class)
class MuxHlsDownloadCallback(
  private val mediaSource: MuxOfflineCmafHlsMediaSource,
  private val drmProvider: MuxDrmSessionManagerProvider,
  private val playbackId: String,
  private val drmToken: String?,
  private val licenseEndpointHost: String = "license.${MediaItems.MUX_VIDEO_DEFAULT_DOMAIN}",
  private val ioExecutor: Executor,
  private val onReady: (DownloadRequest) -> Unit,
  private val onError: (IOException) -> Unit,
) : DownloadHelper.Callback {

  override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
    if (tracksInfoAvailable) {
      selectAllAudioAndTextRenditions(helper)
    }

    // either from EXT-X-SESSION-KEYs (multi-key) or the video's EXT-X-KEY (single-key)
    val videoDrmInitData = mediaSource.capturedMultivariantPlaylist?.capturedWidevinePssh
      ?: mediaSource.selectedMediaPlaylists.firstNotNullOfOrNull { it.capturedDrmInitData }

    if (videoDrmInitData != null) {
      acquireLicenseAsync(helper, videoDrmInitData)
    } else {
      onReady(helper.getDownloadRequest(playbackId, null))
    }
  }

  override fun onPrepareError(helper: DownloadHelper, e: IOException) {
    helper.release()
    onError(e)
  }

  private fun acquireLicenseAsync(helper: DownloadHelper, videoDrmInitData: DrmInitData) {
    ioExecutor.execute {
      try {
        val keySetId = acquireLicense(videoDrmInitData)
        val request = helper.getDownloadRequest(playbackId, null).copyWithKeySetId(keySetId)
        onReady(request)
      } catch (e: IOException) {
        onError(e)
      } catch (e: Exception) {
        onError(IOException(e))
      } finally {
        helper.release()
      }
    }
  }


  /** Acquires a persistent offline license for [videoDrmInitData], returning its `keySetId`. */
  private fun acquireLicense(videoDrmInitData: DrmInitData): ByteArray {
    val token = drmToken
      ?: throw IOException("cannot acquire an offline license without a DRM token")
    val licenseHelper = drmProvider.offlineLicenseHelper(playbackId, token, licenseEndpointHost)
    return try {
      val format = Format.Builder().setDrmInitData(videoDrmInitData).build()
      licenseHelper.downloadLicense(format)
    } finally {
      licenseHelper.release()
    }
  }

  private fun selectAllAudioAndTextRenditions(helper: DownloadHelper) {
    val mappedTrackInfo = helper.getMappedTrackInfo(/* periodIndex = */ 0)
    for (renderer in 0 until mappedTrackInfo.rendererCount) {
      val type = mappedTrackInfo.getRendererType(renderer)
      if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue // keep default top video
      val groups = mappedTrackInfo.getTrackGroups(renderer)
      for (group in 0 until groups.length) { // each rendition is its own group
        helper.addTrackSelectionForSingleRenderer(
          /* periodIndex = */ 0,
          renderer,
          DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS,
          listOf(DefaultTrackSelector.SelectionOverride(group, /* tracks = */ 0)),
        )
      }
    }
  }
}