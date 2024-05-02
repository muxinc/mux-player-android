package com.mux.player.media

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.RequestMetadata
import com.mux.player.internal.Constants

/**
 * Creates instances of [MediaItem] or [MediaItem.Builder] configured for easy use with
 * `MuxPlayer`
 *
 * TODO: Alternative spelling: MuxMediaItems
 */
object MediaItems {

  private const val TAG = "MediaItems"

  /**
   * Default domain + tld for Mux Video
   */
  @Suppress("MemberVisibilityCanBePrivate")
  const val MUX_VIDEO_DEFAULT_DOMAIN = "mux.com"

  internal const val MUX_VIDEO_SUBDOMAIN = "stream"
  internal const val EXTRA_VIDEO_DATA = "com.mux.video.customerdata"

  /**
   * Creates a new [MediaItem] that points to a given Mux Playback ID.
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
   * @param playbackId A playback ID for a Mux Asset
   * @param domain Optional custom domain for Mux Video. The default is [MUX_VIDEO_DEFAULT_DOMAIN]
   * @param playbackToken Playback Token required for Secure Video Playback and DRM Playback
   * @param drmToken DRM Token required for DRM Playback. For DRM, you also need a [playbackToken]
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
    domain: String = MUX_VIDEO_DEFAULT_DOMAIN,
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
    maxResolution: PlaybackResolution? = null,
    minResolution: PlaybackResolution? = null,
    renditionOrder: RenditionOrder? = null,
    domain: String = MUX_VIDEO_DEFAULT_DOMAIN,
    playbackToken: String? = null,
    drmToken: String? = null,
  ): MediaItem.Builder {
    return MediaItem.Builder()
      .setUri(
        createPlaybackUrl(
          playbackId = playbackId,
          domain = domain,
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
    renditionOrder?.let { base.appendQueryParameter("rendition_order", resolutionValue(it)) }
    playbackToken?.let { base.appendQueryParameter("token", it) }

    base.appendQueryParameter("redundant_streams", "true");

    return base.build().toString()
      .also { Log.d(TAG, "playback URI is $it") }
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

enum class RenditionOrder {
  Ascending,
  Descending,
}