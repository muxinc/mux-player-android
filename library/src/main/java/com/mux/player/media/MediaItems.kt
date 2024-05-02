package com.mux.player.media

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.RequestMetadata

/**
 * Creates instances of [MediaItem] or [MediaItem.Builder] configured for easy use with MuxPlayer`.
 */
object MediaItems {

  /**
   * Default domain + tld for Mux Video
   */
  @Suppress("MemberVisibilityCanBePrivate")
  internal const val MUX_VIDEO_DEFAULT_DOMAIN = "mux.com"

  private const val MUX_VIDEO_SUBDOMAIN = "stream"
  private const val EXTRA_VIDEO_DATA = "com.mux.video.customerdata"

  /**
   * Creates a new [MediaItem] that points to a given Mux Playback ID.
   *
   * @param playbackId A playback ID for a Mux Asset
   * @param maxResolution The maximum resolution that should be requested over the network
   * @param minResolution The minimum resolution that should be requested over the network
   * @param renditionOrder [RenditionOrder.Descending] to emphasize quality, [RenditionOrder.Ascending] to emphasize performance
   * @param domain Optional custom domain for Mux Video. The default is [MUX_VIDEO_DEFAULT_DOMAIN]
   * @param playbackToken Playback token for secure playback
   *
   * @see builderFromMuxPlaybackId
   */
  @JvmStatic
  @JvmOverloads
  fun fromMuxPlaybackId(
    playbackId: String,
    maxResolution: PlaybackResolution? = null,
    minResolution: PlaybackResolution? = null,
    renditionOrder: RenditionOrder? = null,
    domain: String? = MUX_VIDEO_DEFAULT_DOMAIN,
    playbackToken: String? = null,
  ): MediaItem = builderFromMuxPlaybackId(
    playbackId,
    maxResolution,
    minResolution,
    renditionOrder,
    domain,
    playbackToken,
  ).build()

  /**
   * Creates a new [MediaItem.Builder] that points to a given Mux Playback ID. You can add
   * additional configuration to the `MediaItem` before you build it
   *
   * @param playbackId A playback ID for a Mux Asset
   * @param maxResolution The maximum resolution that should be requested over the network
   * @param minResolution The minimum resolution that should be requested over the network
   * @param renditionOrder [RenditionOrder.Descending] to emphasize quality, [RenditionOrder.Ascending] to emphasize performance
   * @param domain Optional custom domain for Mux Video. The default is [MUX_VIDEO_DEFAULT_DOMAIN]
   *
   * @see fromMuxPlaybackId
   */
  @JvmStatic
  @JvmOverloads
  fun builderFromMuxPlaybackId(
    playbackId: String,
    maxResolution: PlaybackResolution? = null,
    minResolution: PlaybackResolution? = null,
    renditionOrder: RenditionOrder? = null,
    domain: String? = MUX_VIDEO_DEFAULT_DOMAIN,
    playbackToken: String? = null,
  ): MediaItem.Builder {
    return MediaItem.Builder()
      .setUri(
        createPlaybackUrl(
          playbackId = playbackId,
          domain = domain ?: MUX_VIDEO_DEFAULT_DOMAIN,
          maxResolution = maxResolution,
          minResolution = minResolution,
          renditionOrder = renditionOrder,
          playbackToken = playbackToken,
        )
      )
      .setRequestMetadata(
        RequestMetadata.Builder()
          .build()
      )
  }

  private fun createPlaybackUrl(
    playbackId: String,
    domain: String = MUX_VIDEO_DEFAULT_DOMAIN,
    subdomain: String = MUX_VIDEO_SUBDOMAIN,
    maxResolution: PlaybackResolution? = null,
    minResolution: PlaybackResolution? = null,
    renditionOrder: RenditionOrder? = null,
    playbackToken: String? = null,
  ): String {
    val base = Uri.parse("https://$subdomain.$domain/$playbackId.m3u8").buildUpon()

    minResolution?.let { base.appendQueryParameter("min_resolution", resolutionValue(it)) }
    maxResolution?.let { base.appendQueryParameter("max_resolution", resolutionValue(it)) }
    renditionOrder?.let { base.appendQueryParameter("rendition_order", resolutionValue(it)) }
    playbackToken?.let { base.appendQueryParameter("token", it) }

    base.appendQueryParameter("redundant_streams", "true");

    return base.build().toString()
  }

  private fun resolutionValue(renditionOrder: RenditionOrder): String {
    return when (renditionOrder) {
      RenditionOrder.Ascending -> "asc"
      RenditionOrder.Descending -> "desc"
    }
  }

  private fun resolutionValue(playbackResolution: PlaybackResolution): String {
    return when (playbackResolution) {
      PlaybackResolution.LD_480 -> "480p"
      PlaybackResolution.LD_540 -> "540p"
      PlaybackResolution.HD_720 -> "720p"
      PlaybackResolution.FHD_1080 -> "1080p"
      PlaybackResolution.QHD_1440 -> "1440p"
      PlaybackResolution.FOUR_K_2160 -> "2160p"
    }
  }
}

/**
 * A resolution for playing back Mux assets. If specified in [MediaItems.fromMuxPlaybackId], or
 * similar methods, the video's resolution will be limited to the given value
 */
enum class PlaybackResolution {
  LD_480,
  LD_540,
  HD_720,
  FHD_1080,
  QHD_1440,
  FOUR_K_2160,
}

/**
 * The order of preference for adaptive streaming.
 *
 * version will be tried first, switching toward higher ones. [Descending] means the opposite,
 * that
 */
enum class RenditionOrder {
  /**
   * The highest-resolution renditions will be chosen first, adjusting downward if needed. This
   * setting emphasizes video quality, but may lead to more interruptions on unfavorable networks
   */
  Descending,

  /**
   * The highest-resolution renditions will be chosen first, adjusting downward if needed. This
   * setting emphasizes video quality, but may lead to more interruptions on unfavorable networks
   */
  Ascending,
}