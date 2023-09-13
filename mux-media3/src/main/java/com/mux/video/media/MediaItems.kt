package com.mux.video.media

import androidx.media3.common.MediaItem

/**
 * Creates instances of [MediaItem] or [MediaItem.Builder] configured for easy use with
 * `MuxExoPlayer`
 *
 * TODO: Alternative spelling: MuxMediaItems
 */
object MediaItems {

  /**
   * Default domain + tld for Mux Video
   */
  @Suppress("MemberVisibilityCanBePrivate")
  const val MUX_VIDEO_DEFAULT_DOMAIN = "mux.com"
  private const val MUX_VIDEO_SUBDOMAIN = "stream"

  /**
   * Creates a new [MediaItem] that points to a given Mux Playback ID.
   *
   * @param playbackId A playback ID for a Mux Asset
   * @param domain Optional custom domain for Mux Video. The default is [MUX_VIDEO_DEFAULT_DOMAIN]
   *
   * @see builderFromMuxPlaybackId
   */
  @JvmStatic
  @JvmOverloads
  fun fromMuxPlaybackId(
    playbackId: String,
    domain: String = MUX_VIDEO_DEFAULT_DOMAIN
  ): MediaItem = builderFromMuxPlaybackId(playbackId, domain).build()

  /**
   * Creates a new [MediaItem.Builder] that points to a given Mux Playback ID. You can add
   * additional configuration to the `MediaItem` before you build it
   *
   * @param playbackId A playback ID for a Mux Asset
   * @param domain Optional custom domain for Mux Video. The default is [MUX_VIDEO_DEFAULT_DOMAIN]
   *
   * @see fromMuxPlaybackId
   */
  @JvmStatic
  @JvmOverloads
  fun builderFromMuxPlaybackId(
    playbackId: String,
    domain: String = MUX_VIDEO_DEFAULT_DOMAIN
  ): MediaItem.Builder {
    return MediaItem.Builder()
      .setUri(
        createPlaybackUrl(
          playbackId = playbackId,
          domain = domain
        )
      )
  }

  private fun createPlaybackUrl(
    playbackId: String,
    domain: String = MUX_VIDEO_DEFAULT_DOMAIN,
    subdomain: String = MUX_VIDEO_SUBDOMAIN
  ): String = "https://$subdomain.$domain/$playbackId.m3u8"
}
