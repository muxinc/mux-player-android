package com.mux.player.media3.examples.offline

import androidx.media3.common.MediaItem
import com.mux.player.media.MediaItems
import com.mux.player.media3.PlaybackIds

/**
 * One Mux asset this example knows how to download.
 *
 * @param title A human-readable name, shown in the UI. Not used by the download itself.
 * @param playbackId The Mux playback ID. This is also the download's ID.
 * @param playbackToken Required for signed playback and DRM. See [MediaItems.fromMuxPlaybackId].
 * @param drmToken Required for DRM playback. DRM assets need *both* tokens.
 */
data class DownloadableAsset(
  val title: String,
  val playbackId: String,
  val playbackToken: String? = null,
  val drmToken: String? = null,
) {

  /**
   * The [MediaItem] to hand to [com.mux.player.offline.MuxDownloadManager.startDownload]. It's an
   * ordinary Mux MediaItem — the same one you'd use for streaming playback.
   */
  fun toMediaItem(): MediaItem = MediaItems.fromMuxPlaybackId(
    playbackId = playbackId,
    playbackToken = playbackToken,
    drmToken = drmToken,
  )
}

/**
 * The assets offered on the 'Select Asset' screen. Compiled in for the sake of the example; a real
 * app would fetch its catalog from its own backend.
 */
object DownloadableAssets {

  val all: List<DownloadableAsset> = listOf(
    DownloadableAsset(
      title = "Tears of Steel",
      playbackId = PlaybackIds.TEARS_OF_STEEL,
    ),
    DownloadableAsset(
      title = "The Making of Big Buck Bunny",
      playbackId = PlaybackIds.MAKING_OF_BUCK_BUNNY,
    ),
    DownloadableAsset(
      title = "Elephants Dream",
      playbackId = PlaybackIds.ELEPHANTS_DREAM,
    ),
    DownloadableAsset(
      title = "Mux Marketing Video",
      playbackId = PlaybackIds.MUX_MARKETING_VIDEO,
    ),
    // To download a DRM asset, add both tokens, which your backend generates:
    // DownloadableAsset(
    //   title = "My DRM Asset",
    //   playbackId = "...",
    //   playbackToken = "...",
    //   drmToken = "...",
    // ),
  )

  /**
   * The title for [playbackId], or the playback ID itself if it isn't in [all]. Downloads outlive
   * the catalog they came from (they're on disk between app launches), so the UI can't assume a
   * download's asset is still listed.
   */
  fun titleFor(playbackId: String): String =
    all.firstOrNull { it.playbackId == playbackId }?.title ?: playbackId
}
