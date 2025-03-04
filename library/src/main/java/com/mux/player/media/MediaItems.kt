package com.mux.player.media

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.RequestMetadata
import com.mux.player.internal.Constants
import com.mux.stats.sdk.core.model.CustomerData
import org.json.JSONObject

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
  internal const val EXTRA_CUSTOMER_DATA = "com.mux.video.customerdata"

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
    muxMetadata: CustomerData? = null,
    maxResolution: PlaybackResolution? = null,
    minResolution: PlaybackResolution? = null,
    renditionOrder: RenditionOrder? = null,
    assetStartTime: Double? = null,
    assetEndTime: Double? = null,
    domain: String? = MUX_VIDEO_DEFAULT_DOMAIN,
    playbackToken: String? = null,
    drmToken: String? = null,
  ): MediaItem = builderFromMuxPlaybackId(
    playbackId,
    muxMetadata,
    maxResolution,
    minResolution,
    renditionOrder,
    assetStartTime,
    assetEndTime,
    domain,
    playbackToken,
    drmToken,
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
   * @see fromMuxPlaybackId
   */
  @JvmStatic
  @JvmOverloads
  fun builderFromMuxPlaybackId(
    playbackId: String,
    muxMetadata: CustomerData? = null,
    maxResolution: PlaybackResolution? = null,
    minResolution: PlaybackResolution? = null,
    renditionOrder: RenditionOrder? = null,
    assetStartTime: Double? = null,
    assetEndTime: Double? = null,
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
          assetStartTime = assetStartTime,
          assetEndTime = assetEndTime,
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
              muxMetadata?.let {
                putBundle(EXTRA_CUSTOMER_DATA, BundledCustomerData(it).toBundle())
              }
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
    assetStartTime: Double? = null,
    assetEndTime: Double? = null,
    playbackToken: String? = null,
  ): String {
    val base = Uri.parse("https://$subdomain.$domain/$playbackId.m3u8").buildUpon()

    minResolution?.let { base.appendQueryParameter("min_resolution", resolutionValue(it)) }
    maxResolution?.let { base.appendQueryParameter("max_resolution", resolutionValue(it)) }
    renditionOrder?.takeIf { it != RenditionOrder.Default }
      ?.let { base.appendQueryParameter("rendition_order", resolutionValue(it)) }
    assetStartTime?.let { base.appendQueryParameter("asset_start_time", assetStartTime.toString()) }
    assetEndTime?.let { base.appendQueryParameter("asset_end_time", assetEndTime.toString()) }
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

internal class BundledCustomerData(val data: CustomerData) {

  companion object {
    const val BUNDLE_PLAYER_DATA = "player-data"
    const val BUNDLE_VIDEO_DATA = "video-data"
    const val BUNDLE_VIEW_DATA = "view-data"
    const val BUNDLE_VIEWER_DATA = "viewer-data"
    const val BUNDLE_CUSTOM_DATA = "custom-data"
  }

  constructor(bundle: Bundle) : this(CustomerData()) {
      bundle.getString(BUNDLE_PLAYER_DATA, null)
        ?.let { data.customerPlayerData.replace(JSONObject(it)) }
      bundle.getString(BUNDLE_VIDEO_DATA, null)
        ?.let { data.customerVideoData.replace(JSONObject(it)) }
      bundle.getString(BUNDLE_VIEW_DATA, null)
        ?.let { data.customerViewData.replace(JSONObject(it)) }
      bundle.getString(BUNDLE_VIEWER_DATA, null)
        ?.let { data.customerViewerData.replace(JSONObject(it)) }
      bundle.getString(BUNDLE_CUSTOM_DATA, null)
        ?.let { data.customData.replace(JSONObject(it)) }
  }

  fun toBundle(): Bundle {
    val bundle = Bundle()
    bundle.putString(BUNDLE_PLAYER_DATA, data.customerPlayerData.muxDictionary.toString())
    bundle.putString(BUNDLE_VIDEO_DATA, data.customerVideoData.muxDictionary.toString())
    bundle.putString(BUNDLE_VIEW_DATA, data.customerViewData.muxDictionary.toString())
    bundle.putString(BUNDLE_VIEWER_DATA, data.customerViewerData.muxDictionary.toString())
    bundle.putString(BUNDLE_CUSTOM_DATA, data.customData.muxDictionary.toString())
    return bundle
  }
}
