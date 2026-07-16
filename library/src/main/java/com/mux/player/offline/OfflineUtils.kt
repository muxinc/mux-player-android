package com.mux.player.offline

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.offline.DownloadHelper
import com.mux.player.media.MediaItems.MUX_VIDEO_DEFAULT_DOMAIN
import com.mux.player.media.MuxDrmCallback
import com.mux.player.media.MuxDrmSessionManagerProvider
import java.util.UUID

// TODO: Many of these functions should be internal-viz
// Preliminary Design Note: it's my hope that most of the logic a custom integration would need will
//  be here, and could be lifted out without our storage, playback, or task-management code

/**
 * Creates a [DownloadHelper] that can download Mux Video HLS streams, including DRM-protected ones.
 *
 * The default [DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS] force the highest-bitrate video
 * rendition. Selecting the remaining audio and subtitle renditions, extracting the captured PSSHs,
 * and acquiring the Widevine license all happen in the prepare callback. Use [MuxHlsDownloadCallback]
 * to handle license acquisition and selecting audio/subtitle tracks
 *
 * @param context used only to build the default [renderersFactory].
 * @param mediaSource the capturing source to prepare; build it with [MuxOfflineCmafHlsMediaSource.create].
 * @param renderersFactory renderer capabilities used for track selection during preparation.
 */
@OptIn(UnstableApi::class)
fun createMuxHlsDownloadHelper(
  context: Context,
  mediaSource: MuxOfflineCmafHlsMediaSource,
  renderersFactory: RenderersFactory = DefaultRenderersFactory(context),
): DownloadHelper =
  DownloadHelper.Factory()
    .setRenderersFactory(renderersFactory)
    .setTrackSelectionParameters(DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS)
    .create(mediaSource)

/**
 * Builds an [OfflineLicenseHelper] that acquires a *persistent* Widevine license from the same
 * Mux endpoint the online provider uses, reusing the provider's public
 * [MuxDrmSessionManagerProvider.drmHttpDataSourceFactory] /
 * [MuxDrmSessionManagerProvider.logger] and [MuxDrmCallback].
 *
 * NOTE: [OfflineLicenseHelper.downloadLicense] blocks, so call it off the caller's looper
 *
 * @param playbackId the Mux playback ID being downloaded.
 * @param drmToken a DRM token authorizing a persistent (offline) license for [playbackId].
 * @param licenseEndpointHost the Mux license server host, e.g. `license.mux.com`.
 */
@OptIn(UnstableApi::class)
internal fun MuxDrmSessionManagerProvider.offlineLicenseHelper(
  playbackId: String,
  drmToken: String,
  licenseEndpointHost: String = "license.$MUX_VIDEO_DEFAULT_DOMAIN",
): OfflineLicenseHelper {
  val sessionManager = DefaultDrmSessionManager.Builder()
    .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
    .setMultiSession(false)
    .build(
      MuxDrmCallback(
        drmHttpDataSourceFactory,
        licenseEndpointHost = licenseEndpointHost,
        drmToken = drmToken,
        playbackId = playbackId,
        logger = logger,
      )
    )
  return OfflineLicenseHelper(sessionManager, DrmSessionEventListener.EventDispatcher())
}

/**
 * The [DrmInitData] decoded from the (single) `#EXT-X-KEY`. The stock parser attaches the full PSSH
 * to each segment's [DrmInitData], so we read it off the first segment.
 *
 * (Deliberately NOT [HlsMediaPlaylist.protectionSchemes] — that copy has its PSSH data stripped.)
 */
@OptIn(UnstableApi::class)
fun HlsMediaPlaylist.firstSegmentDrmInitData(): DrmInitData? =
  segments.firstOrNull()?.drmInitData

/**
 *  The first Widevine [DrmInitData] decoded from the multivariant playlist's `#EXT-X-SESSION-KEY`,
 *  if any.
 *  (Unlike with media playlists, session-key pssh's aren't bundled into a single DrmInitData)
 */
@OptIn(UnstableApi::class)
fun HlsMultivariantPlaylist.firstWidevineSessionKeyDrmInitData(): DrmInitData? =
  sessionKeyDrmInitData.firstOrNull { drmInitData ->
    drmInitData.findSessionKeySchemeData(uuid = C.WIDEVINE_UUID) != null
  }

@OptIn(UnstableApi::class)
private fun DrmInitData.findSessionKeySchemeData(uuid: UUID): DrmInitData.SchemeData? {
  if (schemeDataCount <= 0) {
    return null
  }

  for (i in 0 until schemeDataCount) {
    val schemeData = get(i)
    if (schemeData.uuid == uuid) {
      return schemeData
    }
  }

  return null
}
