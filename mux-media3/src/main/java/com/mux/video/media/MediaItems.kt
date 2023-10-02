package com.mux.video.media

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.RequestMetadata
import com.mux.stats.sdk.core.model.CustomerVideoData

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
  private const val EXTRA_VIDEO_DATA = "com.mux.video.customerdata"

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
    maxResolution: PlaybackResolution? = null,
    domain: String = MUX_VIDEO_DEFAULT_DOMAIN,
  ): MediaItem = builderFromMuxPlaybackId(
    playbackId,
    maxResolution,
    domain,
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
    domain: String = MUX_VIDEO_DEFAULT_DOMAIN,
  ): MediaItem.Builder {
    return MediaItem.Builder()
      .setUri(
        createPlaybackUrl(
          playbackId = playbackId,
          domain = domain,
          maxResolution = maxResolution,
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
  ): String {
    val base = Uri.parse("https://$subdomain.$domain/$playbackId.m3u8").buildUpon()

    if (maxResolution != null) {
      base.appendQueryParameter("max_resolution", resolutionValue(maxResolution))
    }

    return base.build().toString()
  }

  private fun resolutionValue(playbackResolution: PlaybackResolution): String {
    return when (playbackResolution) {
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
  HD_720,
  FHD_1080,
  QHD_1440,
  FOUR_K_2160,
}
