package com.mux.video.media3.examples

import android.os.Bundle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.mux.video.MuxExoPlayer
import com.mux.video.media.MediaItems
import com.mux.video.media3.PlaybackIds
import com.mux.video.media3.databinding.ActivityBasicPlayerBinding

/**
 * A simple example that uses the normal media3 player UI to play a video in the foreground from
 * Mux Video, using a Playback ID
 */
class BasicPlayerActivity : AppCompatActivity() {

  private lateinit var binding: ActivityBasicPlayerBinding
  private val playerView get() = binding.player

  private var player: MuxExoPlayer? = null

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
    val player = createPlayer()
    val mediaItem = MediaItems.builderFromMuxPlaybackId(PlaybackIds.TEARS_OF_STEEL)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle("Basic MuxExoPlayer Example")
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
  private fun createPlayer(): MuxExoPlayer {
    val out = MuxExoPlayer.Builder(this)
      .applyExoConfig {
        // Call ExoPlayer.Builder methods here
        setHandleAudioBecomingNoisy(true)
        setSeekBackIncrementMs(10_000)
        setSeekForwardIncrementMs(30_000)
      }
      .build()

    out.addListener(object : Player.Listener {
      override fun onPlayerError(error: PlaybackException) {
        Toast.makeText(
          this@BasicPlayerActivity,
          "Playback error! ${error.localizedMessage}",
          Toast.LENGTH_LONG
        ).show()
      }
    })

    return out
  }
}
