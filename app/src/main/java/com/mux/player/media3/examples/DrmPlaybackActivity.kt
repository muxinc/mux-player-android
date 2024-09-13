package com.mux.player.media3.examples

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
import com.mux.stats.sdk.core.model.CustomData
import com.mux.stats.sdk.core.model.CustomerData
import com.mux.stats.sdk.core.model.CustomerVideoData
import com.mux.stats.sdk.core.model.CustomerViewData
import com.mux.stats.sdk.core.util.UUID
import com.mux.player.MuxPlayer
import com.mux.player.media.MediaItems
import com.mux.player.media.PlaybackResolution
import com.mux.player.media3.PlaybackIds
import com.mux.player.media3.databinding.ActivityBasicPlayerBinding
import com.mux.player.internal.Constants
import com.mux.stats.sdk.core.model.CustomerPlayerData

/**
 * A simple example that uses the normal media3 player UI to play a video in the foreground from
 * Mux Video, using a Playback ID
 */
class DrmPlaybackActivity : AppCompatActivity() {

  companion object {
    const val TAG = "DrmPlaybackActivity"
  }

  private lateinit var binding: ActivityBasicPlayerBinding
  private val playerView get() = binding.player

  private var player: MuxPlayer? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityBasicPlayerBinding.inflate(layoutInflater)
    setContentView(binding.root)
  }

  override fun onStart() {
    super.onStart()

    playSomething()
  }

  override fun onStop() {
    tearDownPlayer()

    super.onStop()
  }

  private fun tearDownPlayer() {
    playerView.player = null
    player?.release()
  }

  private fun playSomething() {

    val player = createPlayer(this)
    val mediaItem = MediaItems.builderFromMuxPlaybackId(
      playbackId = "YOUR PLAYBACK_ID",
      playbackToken = "YOUR PLAYBACK TOKEN",
      drmToken = "YOUR DRM TOKEN",
    )
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle("DRM Playback Example")
          .build()
      )
      .build()
    player.setMediaItem(mediaItem)
    player.prepare()
    player.playWhenReady = true

    this.playerView.player = player
    this.player = player
  }

  @OptIn(UnstableApi::class)
  private fun createPlayer(context: Context): MuxPlayer {
    val out: MuxPlayer = MuxPlayer.Builder(context)
      .addMonitoringData(
        CustomerData().apply {
          customerViewData = CustomerViewData().apply {
            viewSessionId = UUID.generateUUID()
          }
          customerVideoData = CustomerVideoData().apply {
            videoTitle = "DRM Playback Example"
            videoSeries = "Mux Player for Android"
            videoId = "abc1234zyxw"
          }
        }
      )
      .build()

    out.addListener(object : Player.Listener {
      override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "player error!", error)
        Toast.makeText(
          this@DrmPlaybackActivity,
          "Playback error! ${error.localizedMessage}",
          Toast.LENGTH_LONG
        ).show()
      }
    })

    return out
  }
}
