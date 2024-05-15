package com.mux.player

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
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
import com.mux.stats.sdk.core.model.CustomerViewData
import com.mux.stats.sdk.core.util.UUID

class CachePerfTestActivity : AppCompatActivity() {

  private lateinit var binding: ActivityCachePerfTestBinding
  private val playerView get() = binding.player

  private var player: MuxPlayer? = null

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

  fun playTestCase(case: CachePerfTestCase) {
    val player = createPlayer(this, case)
    val mediaItem = MediaItems.builderFromMuxPlaybackId(
      case.playbackId,
      maxResolution = case.resolution,
      minResolution = case.resolution,
    ).setMediaMetadata(
      MediaMetadata.Builder()
        .setTitle(case.title())
        .build()
    ).build()
    player.setMediaItem(mediaItem)
    player.prepare()
    player.playWhenReady = true

    this.playerView.player = player
    this.player = player
  }

  @OptIn(UnstableApi::class)
  private fun createPlayer(
    context: Context,
    testCase: CachePerfTestCase
  ): MuxPlayer {
    val out: MuxPlayer = MuxPlayer.Builder(context)
      .addMonitoringData(
        CustomerData().apply {
          customerVideoData = CustomerVideoData().apply {
            videoSeries = "Cache Looping Perf Tests"
            videoTitle = testCase.title()
          }
          customData = CustomData().apply {
            customData1 = testCase.playbackId
            customData2 = testCase.assetName
            customData3 = testCase.resolution.toString()
            customData4 = testCase.loops.toString()
          }
        }
      )
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
    })

    return out
  }

  companion object {
    val TAG = CachePerfTestActivity::class.simpleName
  }
}

data class CachePerfTestCase(
  val playbackId: String,
  val assetName: String,
  val resolution: PlaybackResolution,
  val loops: Int,
) {
  fun title(): String = "CachePerf | $assetName | at $resolution, $loops loops"
}
