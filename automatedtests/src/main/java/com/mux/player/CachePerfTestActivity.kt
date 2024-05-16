package com.mux.player

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.mux.player.media.MediaItems
import com.mux.player.media.PlaybackResolution
import com.mux.player.media3.databinding.ActivityCachePerfTestBinding
import com.mux.stats.sdk.core.model.CustomData
import com.mux.stats.sdk.core.model.CustomerData
import com.mux.stats.sdk.core.model.CustomerVideoData
import androidx.media3.exoplayer.util.EventLogger



class CachePerfTestActivity : AppCompatActivity() {

  private lateinit var binding: ActivityCachePerfTestBinding
  private val playerView get() = binding.player

  var player: MuxPlayer? = null
    private set

  private var playerListener: Player.Listener? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityCachePerfTestBinding.inflate(layoutInflater)
    setContentView(binding.root)
  }

  override fun onStart() {
    super.onStart()
  }

  override fun onStop() {
    tearDownPlayer()
    super.onStop()
  }

  private fun tearDownPlayer() {
    playerView.player = null
    player?.release()
  }

  // called by tests
  fun setPlayerListener(listener: Player.Listener?) {
    this.playerListener = listener
  }

  fun playTestCase(case: LoopingTestCase) {
    val player = createPlayer(this, case)
    Log.d("WHY", "createPlayer returned $player")

    repeat(case.loopsOverall) {
      for (playbackId in case.playbackIds) {
        val mediaItem = MediaItems.builderFromMuxPlaybackId(
          playbackId = playbackId,
          maxResolution = case.resolution,
          minResolution = case.resolution,
        ).setMediaMetadata(
          MediaMetadata.Builder()
            .setTitle(case.title())
            .build()
        ).build()
        // note: but this isn't what strava is doing, they would be playing from different views,
        //  making and preparing a new player each time. The same number of segments should be
        //  fetched in that case though.
        player.addMediaItem(mediaItem)
      }
    }

    player.prepare()
    player.playWhenReady = true

    Log.d("WHY", "createPlayer: setting player $player on view $playerView")
    this.playerView.player = player
    this.player = player
  }

  @OptIn(UnstableApi::class)
  private fun createPlayer(
    context: Context,
    testCase: LoopingTestCase
  ): MuxPlayer {
    val out: MuxPlayer = MuxPlayer.Builder(context)
      .addMonitoringData(
        CustomerData().apply {
          customerVideoData = CustomerVideoData().apply {
            videoSeries = "Cache Looping Perf Tests"
            videoTitle = testCase.title()
          }
          customData = CustomData().apply {
            customData2 = "Suite: ${testCase.name}"
            customData3 = "Chosen Res: ${testCase.resolution.name}"
            customData4 = "${testCase.loopsOverall} loops"
            customData5 = "Cache? ${testCase.cacheEnabled}"
          }
        }
      )
      .enableSmartCache(testCase.cacheEnabled)
      .build()

    out.addListener(object : Player.Listener {
      override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "player error!", error)
        Toast.makeText(
          this@CachePerfTestActivity,
          "Playback error! ${error.localizedMessage}",
          Toast.LENGTH_LONG
        ).show()
      }

      override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
      ) {
        Log.i(
          TAG,
          "onPositionDiscontinuity: oldPosition=${oldPosition.positionMs}, " +
              "newPosition=${newPosition.positionMs}, reason=$reason"
        )
      }

      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        Log.i(
          TAG,
          "onMediaItemTransition: mediaItem=$mediaItem, reason=$reason"
        )
      }
    })

    playerListener?.let { out.addListener(it) }
    out.addAnalyticsListener(EventLogger("AAAA"))

    return out
  }

  companion object {
    val TAG = CachePerfTestActivity::class.simpleName
  }
}

data class LoopingTestCase(
  val playbackIds: List<String>,
  val name: String,
  val resolution: PlaybackResolution,
  val loopsOverall: Int,
  // todo - loopsPerVideo
  val cacheEnabled: Boolean,
) {
  fun title(): String = "CacheTestCase | $name | at $resolution, $loopsOverall loops," +
      " Cache? $cacheEnabled"
}
