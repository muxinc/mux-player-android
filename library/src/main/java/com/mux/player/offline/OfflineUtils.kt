package com.mux.player.offline

import android.content.Context
import android.media.MediaDrm
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.offline.DownloadHelper
import com.mux.player.media.MediaItems.MUX_VIDEO_DEFAULT_DOMAIN
import com.mux.player.media.MuxDrmCallback
import com.mux.player.media.MuxDrmSessionManagerProvider
import java.io.IOException
import java.util.UUID

private const val TAG = "MuxOfflineUtils"

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
fun MuxDrmSessionManagerProvider.offlineLicenseHelper(
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
 * Renews the persistent Widevine license identified by [keySetId], returning the `keySetId` of the
 * renewed license. Widevine often renews in place and hands back the same `keySetId`, so callers
 * must not assume the result is new.
 *
 * Renewal goes to the same Mux endpoint as acquisition (see
 * [MuxDrmSessionManagerProvider.offlineLicenseHelper]) and needs nothing from the media: media3
 * builds the renewal request from [keySetId] alone, with no PSSH and no
 * [androidx.media3.common.DrmInitData].
 *
 * NOTE: [OfflineLicenseHelper.renewLicense] blocks, so call it off the caller's looper.
 *
 * @param playbackId the Mux playback ID whose license is being renewed.
 * @param drmToken a *fresh* DRM token authorizing a persistent (offline) license for [playbackId].
 *   The token used to acquire the license has likely expired by now.
 * @param licenseEndpointHost the Mux license server host, e.g. `license.mux.com`.
 * @param keySetId the `keySetId` of the license to renew, as stored on the download's
 *   [androidx.media3.exoplayer.offline.DownloadRequest].
 */
@OptIn(UnstableApi::class)
internal fun MuxDrmSessionManagerProvider.renewOfflineLicense(
  playbackId: String,
  drmToken: String,
  licenseEndpointHost: String,
  keySetId: ByteArray,
): ByteArray {
  val licenseHelper = offlineLicenseHelper(playbackId, drmToken, licenseEndpointHost)
  return try {
    licenseHelper.renewLicense(keySetId)
  } finally {
    licenseHelper.release()
  }
}

/**
 * Builds an [OfflineLicenseHelper] for asking the device's CDM about licenses that are *already* on
 * this device.
 *
 * Unlike [MuxDrmSessionManagerProvider.offlineLicenseHelper], this one has no DRM token and no
 * network ([NoNetworkDrmCallback]), so it can't acquire or renew anything. It's only good for
 * [isOfflineLicenseExpired], which the CDM answers locally.
 *
 * Call [OfflineLicenseHelper.release] when finished; the helper owns a thread.
 */
@OptIn(UnstableApi::class)
internal fun localOfflineLicenseHelper(): OfflineLicenseHelper {
  val sessionManager = DefaultDrmSessionManager.Builder()
    .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
    .setMultiSession(false)
    .build(NoNetworkDrmCallback())

  return OfflineLicenseHelper(sessionManager, DrmSessionEventListener.EventDispatcher())
}

/**
 * Whether the offline license identified by [keySetId] has run out, according to the CDM. Blocking;
 * call it off the caller's looper. Build the receiver with [localOfflineLicenseHelper].
 *
 * Widevine tracks two windows and reports how much is left of each: the *license* (rental) window,
 * which starts when the license is issued, and the *play* window, which starts the first time the
 * content is played. Whichever runs out first ends the license, so the check is just
 * `min(the two) <= 0` — the same one media3 makes in `DefaultDrmSession.doLicense`.
 *
 * Notably, nothing here has to remember whether playback ever started: the CDM reports the play
 * window's full configured duration until the first decrypt, and only counts it down after that.
 *
 * Anything other than the CDM saying the license is spent — a CDM that won't open, a `keySetId` it
 * doesn't recognize, a window it declines to report — is a failed query rather than an answer, and
 * reads as not-expired. Downloads stay playable-looking (and playback reports the real DRM error)
 * instead of being written off over a flaky query.
 */
@OptIn(UnstableApi::class)
internal fun OfflineLicenseHelper.isOfflineLicenseExpired(keySetId: ByteArray): Boolean {
  val remaining = try {
    // media3 turns the CDM's "these keys are expired" into 0s remaining on both windows
    getLicenseDurationRemainingSec(keySetId)
  } catch (e: Exception) {
    Log.w(TAG, "couldn't read license expiration from the CDM", e)
    return false
  }

  return offlineLicenseExpired(
    licenseDurationRemainingSec = remaining.first,
    playbackDurationRemainingSec = remaining.second,
  )
}

/**
 * The expiry decision for the two remaining durations the CDM reports, in seconds. See
 * [isOfflineLicenseExpired].
 *
 * A window reported as [C.TIME_UNSET] wasn't reported at all, so it doesn't get a vote. If neither
 * window was reported, there's nothing to conclude and the license isn't treated as expired.
 */
@OptIn(UnstableApi::class)
internal fun offlineLicenseExpired(
  licenseDurationRemainingSec: Long,
  playbackDurationRemainingSec: Long,
): Boolean {
  val reportedWindows = listOf(licenseDurationRemainingSec, playbackDurationRemainingSec)
    .filter { it != C.TIME_UNSET }

  return reportedWindows.isNotEmpty() && reportedWindows.min() <= 0L
}

/**
 * Local-only purge of the offline license keyed by [keySetId]. No network and no DRM token — Mux
 * enforces no offline-license quota, so there is no server-side release. Best-effort.
 *
 * Only call this for a license nothing references any more: a download that was removed, or one whose
 * license [renewOfflineLicense] replaced with a different `keySetId`.
 */
@OptIn(UnstableApi::class)
internal fun dropOfflineLicense(keySetId: ByteArray) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return // no per-license purge API pre-29
  val mediaDrm = try {
    MediaDrm(C.WIDEVINE_UUID)
  } catch (_: Exception) {
    return
  }
  try {
    mediaDrm.removeOfflineLicense(keySetId)
  } catch (_: Exception) {
    // best-effort; no DownloadRequest points at this keySetId, so any residue is unreferenced
  } finally {
    mediaDrm.close()
  }
}

/**
 * A [MediaDrmCallback] for DRM work that must stay local: playing back a download, or asking the CDM
 * about a license it already holds. Both are answered on-device, so any request out to the network
 * means something we didn't plan for, and failing is better than quietly going online.
 */
@OptIn(UnstableApi::class)
internal class NoNetworkDrmCallback : MediaDrmCallback {
  override fun executeProvisionRequest(
    uuid: UUID,
    request: ExoMediaDrm.ProvisionRequest
  ): MediaDrmCallback.Response {
    throw IOException("This device needs to be provisioned for DRM, which needs a network")
  }

  override fun executeKeyRequest(
    uuid: UUID,
    request: ExoMediaDrm.KeyRequest
  ): MediaDrmCallback.Response {
    // Reached when the CDM won't play the offline license as-is and media3 tries to renew it, which
    // in practice means the license is expired or about to be. See MuxDownload.State.EXPIRED.
    throw IOException(
      "This download's offline license can't be used, and renewing it would need a network. " +
          "It has probably expired; renew it with MuxDownloadManager.renewOfflineLicense while " +
          "online, or remove the download."
    )
  }
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
