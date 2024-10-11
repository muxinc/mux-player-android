package com.mux.player.media3.examples

import android.os.Bundle
import android.view.MenuItem
import androidx.media3.common.MediaItem
import com.mux.player.media.MediaItems
import com.mux.player.media.PlaybackResolution
import com.mux.player.media.RenditionOrder
import com.mux.player.media3.PlaybackIds
import com.mux.player.media3.R

/**
 * Helper class for the example Activities that handles setting playback params and stuff
 */
class PlaybackParamsHelper {
  // todo: set data from Intent (ie, deep linking)

  var maxRes: PlaybackResolution? = null
  var minRes: PlaybackResolution? = null
  var renditionOrder: RenditionOrder? = null

  var playbackToken: String? = null
  var drmToken: String? = null
  var playbackId: String? = null
  var customDomain: String? = null

  fun createMediaItemBuilder(): MediaItem.Builder {
    return MediaItems.builderFromMuxPlaybackId(
      playbackId = playbackIdOrDefault(),
      minResolution = minRes,
      maxResolution = maxRes,
      renditionOrder = renditionOrder,
      playbackToken = playbackToken?.ifEmpty { null },
      drmToken = drmToken?.ifEmpty { null },
      domain = customDomain?.ifEmpty { null },
    )
  }

  fun playbackIdOrDefault(): String {
    return playbackId?.ifEmpty { DEFAULT_PLAYBACK_ID } ?: DEFAULT_PLAYBACK_ID
  }

  fun createMediaItem(): MediaItem {
    return createMediaItemBuilder().build()
  }

  fun saveInstanceState(state: Bundle) {
    state.putInt("PlaybackParamsHelper.maxRes", maxRes?.ordinal ?: -1)
    state.putInt("PlaybackParamsHelper.minRes", minRes?.ordinal ?: -1)
    state.putInt("PlaybackParamsHelper.renditionOrder", renditionOrder?.ordinal ?: -1)

    state.putString("PlaybackParamsHelper.playbackToken", playbackToken)
    state.putString("PlaybackParamsHelper.drmToken", drmToken)
    state.putString("PlaybackParamsHelper.customDomain", customDomain)
    state.putString("PlaybackParamsHelper.playbackId", playbackId)
  }

  fun restoreInstanceState(state: Bundle) {
    maxRes = state.getInt("PlaybackParamsHelper.maxRes", -1)
      .takeIf { it >= 0 }?.let { PlaybackResolution.entries[it] }
    minRes = state.getInt("PlaybackParamsHelper.minRes", -1)
      .takeIf { it >= 0 }?.let { PlaybackResolution.entries[it] }
    renditionOrder = state.getInt("PlaybackParamsHelper.renditionOrder", -1)
      .takeIf { it >= 0 }?.let { RenditionOrder.entries[it] }

    playbackToken = state.getString("PlaybackParamsHelper.playbackToken", null)
    drmToken = state.getString("PlaybackParamsHelper.drmToken", null)
    playbackId = state.getString("PlaybackParamsHelper.playbackId", null)
    customDomain = state.getString("PlaybackParamsHelper.customDomain", null)
  }

  fun handleMenuClick(item: MenuItem): Boolean {
    return when (item.itemId) {
      R.id.player_menu_min_2160 -> {
        minRes = PlaybackResolution.FOUR_K_2160
        true
      }
      R.id.player_menu_min_1440 -> {
        minRes = PlaybackResolution.QHD_1440
        true
      }
      R.id.player_menu_min_1080 -> {
        minRes = PlaybackResolution.FHD_1080
        true
      }
      R.id.player_menu_min_720 -> {
        minRes = PlaybackResolution.HD_720
        true
      }
      R.id.player_menu_min_540 -> {
        minRes = PlaybackResolution.LD_540
        true
      }
      R.id.player_menu_min_480 -> {
        minRes = PlaybackResolution.LD_480
        true
      }
      R.id.player_menu_min_unspecified -> {
        minRes = null
        true
      }
      R.id.player_menu_max_2160 -> {
        maxRes = PlaybackResolution.FOUR_K_2160
        true
      }
      R.id.player_menu_max_1440 -> {
        maxRes = PlaybackResolution.QHD_1440
        true
      }
      R.id.player_menu_max_1080 -> {
        maxRes = PlaybackResolution.FHD_1080
        true
      }
      R.id.player_menu_max_720 -> {
        maxRes = PlaybackResolution.HD_720
        true
      }
      R.id.player_menu_max_unspecified -> {
        maxRes = null
        true
      }
      R.id.player_menu_rendntion_unspecified -> {
        renditionOrder = null
        true
      }
      R.id.player_menu_descending -> {
        renditionOrder = RenditionOrder.Descending
        true
      }
      R.id.player_menu_default -> {
        renditionOrder = RenditionOrder.Default
        true
      }
      else -> false
    }
  }

  companion object {
    const val DEFAULT_PLAYBACK_ID = PlaybackIds.TEARS_OF_STEEL
  }
}
