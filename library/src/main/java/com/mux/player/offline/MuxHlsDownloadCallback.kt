package com.mux.player.offline

import android.annotation.SuppressLint
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
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
 * 1. selects the top variant's video track and all audio and subtitle renditions in that variant's
 *    AUDIO and SUBTITLES rendition groups (eg, default and alternate-language renditions,
 *    accessible renditions, etc)
 * 2. reads the captured video [androidx.media3.common.DrmInitData] off [mediaSource] — the multivariant
 *    `#EXT-X-SESSION-KEY`, falling back to a media playlist's `#EXT-X-KEY`;
 * 3. (off the caller's looper, on [ioExecutor]) acquires the offline Widevine license → `keySetId`
 * 4. builds a [androidx.media3.exoplayer.offline.DownloadRequest] keyed by **playbackId**, with the
 *    Widevine PSSH saved to its data as [ExtraData] for later license renewals
 * 5. emits it via [onReady] and releases the helper, waiting for license acquisition if required
 *
 * - Any failure routes to [onError].
 *
 * ### Notes
 * - Track selection will only work correctly for HLS streams (though mux doesn't deliver DASH at
 *   the time of writing this)
 * - Clear content is downloaded with no `keySetId`.
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
      val baseRequest = buildRequest(uri, generateStreamKeys(mvp), videoDrmInitData)
      // after building the base request we can release. License data will be added async
      helper.release()
      acquireLicenseAsync(videoDrmInitData, baseRequest)
    } else {
      onReady(buildRequest(uri, generateStreamKeys(mvp)))
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
    videoDrmInitData: DrmInitData? = null,
  ): DownloadRequest {
    // the pssh is only in the playlists we're parsing right now, so save it for later renewals
    val extraData = ExtraData(
      widevinePssh = videoDrmInitData?.findSchemeData(C.WIDEVINE_UUID)?.data,
    )

    return DownloadRequest.Builder(playbackId, uri)
      .setMimeType(MimeTypes.APPLICATION_M3U8)
      .setStreamKeys(streamKeys)
      .setData(extraData.toUtf8Bytes())
      .build()
  }

  private fun acquireLicenseAsync(
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
      }
    }
  }


  /** Acquires a persistent offline license for [videoDrmInitData], returning its `keySetId`. */
  private fun acquireLicense(videoDrmInitData: DrmInitData): ByteArray {
    val token = drmToken
      ?: throw IOException("cannot acquire an offline license without a DRM token")
    return drmProvider.acquireOfflineLicense(
      playbackId = playbackId,
      drmToken = token,
      licenseEndpointHost = licenseEndpointHost,
      drmInitData = videoDrmInitData,
    )
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
