package com.mux.player.offline

import android.annotation.SuppressLint
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
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

  @SuppressLint("UseKtx") // String.toUri is not in our deps
  override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
    // mvp guaranteed populated by onPrepared()
    val mvp = mediaSource.capturedMultivariantPlaylist!!.playlist
    val uri = Uri.parse(mvp.baseUri)

    // either from EXT-X-SESSION-KEYs (multi-key) or the video's EXT-X-KEY (single-key)
    val videoDrmInitData = mediaSource.capturedMultivariantPlaylist?.capturedWidevineData
      ?: mediaSource.selectedMediaPlaylists.firstNotNullOfOrNull { it.capturedDrmInitData }

    if (videoDrmInitData != null) {
//      acquireLicenseAsync(helper, videoDrmInitData) {
//        buildRequest(uri, generateStreamKeys(helper, mvp), it)
//      }
      val baseRequest = buildRequest(uri, generateStreamKeys(helper, mvp))
      // after building the base request we can release. License data will be added async
      helper.release()
      acquireLicenseAsync(helper, videoDrmInitData, baseRequest)
    } else {
      onReady(buildRequest(uri, generateStreamKeys(helper, mvp)))
      helper.release()
    }
  }

  override fun onPrepareError(helper: DownloadHelper, e: IOException) {
    helper.release()
    onError(e)
  }

  private fun buildRequest(
    uri: Uri,
    streamKeys: List<StreamKey>,
//    keySetId: ByteArray?
  ): DownloadRequest {
    return DownloadRequest.Builder(playbackId, uri)
      .setMimeType(MimeTypes.APPLICATION_M3U8)
      .setStreamKeys(streamKeys)
      .build()
//      .let { if (keySetId != null) it.copyWithKeySetId(keySetId) else it }
  }

  private fun acquireLicenseAsync(
    helper: DownloadHelper,
    videoDrmInitData: DrmInitData,
    baseRequest: DownloadRequest,
  ) {
    ioExecutor.execute {
      try {
        val keySetId = acquireLicense(videoDrmInitData)
        onReady(baseRequest.copyWithKeySetId(keySetId))
      } catch (e: IOException) {
        onError(e)
      } catch (e: Exception) {
        onError(IOException(e))
      } //finally {
        //helper.release()
      //}
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

  /**
   * Build stream keys for selecting the tracks to download. We do this manually because
   * Mux's CMAF streams would crash DownloadHelper otherwise. DownloadHelper and HlsMediaPeriod have
   * different pictures of how the audio renditions map to the variant list, so DownloadHelper tries
   * to select tracks at nonexistent indicies
   *
   * We know how mux streams are formatted so we just set this up manually and skip DownloadHelper's
   * track selection process to avoid the crash
   */
  private fun generateStreamKeys(
    helper: DownloadHelper,
    mvp: HlsMultivariantPlaylist
  ): List<StreamKey> {
    if (mvp.variants.isEmpty()) {
      // strange case but ok
      return listOf()
    }
    return buildList {
      val topIndex = mvp.variants.indices.maxBy { mvp.variants[it].format.bitrate }
      val topVideoVariant = mvp.variants[topIndex]
      add(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, topIndex))

      // select the audio/sub renditions that belongs to the top variant
      mvp.audios.forEachIndexed { i, rendition ->
        if (rendition.groupId == topVideoVariant.audioGroupId) {
          add(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, i))
        }
      }
      mvp.subtitles.forEachIndexed { i, rendition ->
        if (rendition.groupId == topVideoVariant.subtitleGroupId) {
          add(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_SUBTITLE, i))
        }
      }
    }
  }
}
