package com.mux.player

import android.content.Context
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

class CachePerfTestActivity : AppCompatActivity() {

  private lateinit var binding: ActivityCachePerfTestBinding
  private val playerView get() = binding.player

  private var player: MuxPlayer? = null
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

  fun getPlayer(): MuxPlayer? {
    return player
  }

  fun playTestCase(case: CacheTestCase) {
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
    testCase: CacheTestCase
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
            customData3 = testCase.resolution.name
            customData4 = "${testCase.loops} loops"
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
    })

    return out
  }

  companion object {
    val TAG = CachePerfTestActivity::class.simpleName
  }
}

data class CacheTestCase(
  val playbackId: String,
  val assetName: String,
  val resolution: PlaybackResolution,
  val loops: Int,
  val cacheEnabled: Boolean,
) {
  fun title(): String = "CacheTestCase | $assetName | at $resolution, $loops loops"
}
