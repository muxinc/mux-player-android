package com.mux.player.media

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.RequestMetadata
import com.mux.player.internal.Constants

/**
 * Creates instances of [MediaItem] or [MediaItem.Builder] configured for easy use with MuxPlayer`.
 */
object MediaItems {

  private const val TAG = "MediaItems"

  /**
   * Default domain + tld for Mux Video
   */
  @Suppress("MemberVisibilityCanBePrivate")
  internal const val MUX_VIDEO_DEFAULT_DOMAIN = "mux.com"

  internal const val MUX_VIDEO_SUBDOMAIN = "stream"
  internal const val EXTRA_VIDEO_DATA = "com.mux.video.customerdata"

  /**
   * Creates a new [MediaItem] that points to a given Mux Playback ID.
   *
   * ## Controlling resolution
   * You can use the [maxResolution] and [minResolution] parameters to control the possible video
   * resolutions that Mux Player can stream. You can use these parameters to control your overall
   * playback experience and platform usage. Lower resolution generally means smoother playback
   * experience and lower costs, higher resolution generally means nicer-looking videos that may
   * take longer to start or stall on unfavorable networks.
   *
   * ## Custom domains
   * If you are using Mux Video [custom domains](https://docs.mux.com/guides/use-a-custom-domain-for-streaming#use-your-own-domain-for-delivering-videos-and-images),
   * you can configure your MediaItem with your custom domain using the [domain] parameter
   *
   * ## DRM and Secure playback
   * Mux player provides two types of playback security, signed playback and DRM playback. Signed
   * playback protects your assets from being played by third parties by using a Playback Token
   * you generate securely on your application backend. DRM playbacks adds additional system-level
   * protections against unauthorized copying and recording of your media, but requires additional
   * setup.
   *
   * ### Secure Playback
   * To use secure playback, you must provide a valid [playbackToken]
   *
   * ### DRM Playback
   * To use DRM playback, you must provide *both* a valid [playbackToken] and a valid [drmToken]
   *
   * DRM is currently in beta. If you are interested in participating, or have questions, please
   * email support@mux.com
   *
   * @param playbackId A playback ID for a Mux Asset
   * @param maxResolution The maximum resolution that should be requested over the network
   * @param minResolution The minimum resolution that should be requested over the network
   * @param renditionOrder [RenditionOrder.Descending] to emphasize quality, [RenditionOrder.Ascending] to emphasize performance
   * @param domain Optional custom domain for Mux Video. The default is [MUX_VIDEO_DEFAULT_DOMAIN]
   * @param playbackToken Playback Token required for Secure Video Playback and DRM Playback
   * @param drmToken DRM Token required for DRM Playback. For DRM, you also need a [playbackToken]
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
    drmToken: String? = null,
  ): MediaItem = builderFromMuxPlaybackId(
    playbackId,
    maxResolution,
    minResolution,
    renditionOrder,
    domain,
    playbackToken,
    drmToken
  ).build()


  /**
   * Creates a new [MediaItem] that points to a given Mux Playback ID.
   *
   * ## Controlling resolution
   * You can use the [maxResolution] and [minResolution] parameters to control the possible video
   * resolutions that Mux Player can stream. You can use these parameters to control your overall
   * playback experience and platform usage. Lower resolution generally means smoother playback
   * experience and lower costs, higher resolution generally means nicer-looking videos that may
   * take longer to start or stall on unfavorable networks.
   *
   * ## Custom domains
   * If you are using Mux Video [custom domains](https://docs.mux.com/guides/use-a-custom-domain-for-streaming#use-your-own-domain-for-delivering-videos-and-images),
   * you can configure your MediaItem with your custom domain using the [domain] parameter
   *
   * ## DRM and Secure playback
   * Mux player provides two types of playback security, signed playback and DRM playback. Signed
   * playback protects your assets from being played by third parties by using a Playback Token
   * you generate securely on your application backend. DRM playbacks adds additional system-level
   * protections against unauthorized copying and recording of your media, but requires additional
   * setup.
   *
   * ### Secure Playback
   * To use secure playback, you must provide a valid [playbackToken]
   *
   * ### DRM Playback
   * To use DRM playback, you must provide *both* a valid [playbackToken] and a valid [drmToken].
   *
   * DRM is currently in beta. If you are interested in participating, or have questions, please
   * email support@mux.com
   *
   * ## Controlling resolution
   * You can use the [maxResolution] and [minResolution] parameters to control the possible video
   * resolutions that Mux Player can stream. You can use these parameters to control your overall
   * playback experience and platform usage. Lower resolution generally means smoother playback
   * experience and lower costs, higher resolution generally means nicer-looking videos that may
   * take longer to start or stall on unfavorable networks.
   *
   * ## Custom domains
   * If you are using Mux Video [custom domains](https://docs.mux.com/guides/use-a-custom-domain-for-streaming#use-your-own-domain-for-delivering-videos-and-images),
   * you can configure your MediaItem with your custom domain using the [domain] parameter
   *
   * @param playbackId A playback ID for a Mux Asset
   * @param maxResolution The maximum resolution that should be requested over the network
   * @param minResolution The minimum resolution that should be requested over the network
   * @param renditionOrder [RenditionOrder.Descending] to emphasize quality, [RenditionOrder.Ascending] to emphasize performance
   * @param domain Optional custom domain for Mux Video. The default is [MUX_VIDEO_DEFAULT_DOMAIN]
   * @param playbackToken Playback Token required for Secure Video Playback and DRM Playback
   * @param drmToken DRM Token required for DRM Playback. For DRM, you also need a [playbackToken]
   *
   * @see builderFromMuxPlaybackId
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
    drmToken: String? = null,
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
          .setExtras(
            Bundle().apply {
              putString(Constants.BUNDLE_DRM_TOKEN, drmToken)
              putString(Constants.BUNDLE_PLAYBACK_ID, playbackId)
              putString(Constants.BUNDLE_PLAYBACK_DOMAIN, domain)
            }
          )
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
    renditionOrder?.takeIf { it != RenditionOrder.Default }
      ?.let { base.appendQueryParameter("rendition_order", resolutionValue(it)) }
    playbackToken?.let { base.appendQueryParameter("token", it) }

    base.appendQueryParameter("redundant_streams", "true");

    return base.build().toString()
  }

  private fun resolutionValue(renditionOrder: RenditionOrder): String {
    return when (renditionOrder) {
      RenditionOrder.Descending -> "desc"
      else -> "" // should be avoided by createPlaybackUrl
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
 */
enum class RenditionOrder {
  /**
   * The highest-resolution renditions will be chosen first, adjusting downward if needed. This
   * setting emphasizes video quality, but may lead to more interruptions on unfavorable networks
   */
  Descending,

  /**
   * The default rendition order will be used, which may be optimized for delivery
   */
  Default,
}
