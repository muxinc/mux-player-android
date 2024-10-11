package com.mux.player.media3.examples

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.mux.player.MuxPlayer
import com.mux.player.media3.R
import com.mux.player.media3.databinding.ActivityConfigurablePlayerBinding

/**
 * Example Activity that enables smart caching
 */
class SmartCacheActivity : AppCompatActivity() {

  private lateinit var binding: ActivityConfigurablePlayerBinding
  private val playerView get() = binding.player

  private val playbackParamsHelper = PlaybackParamsHelper()

  private var player: MuxPlayer? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityConfigurablePlayerBinding.inflate(layoutInflater)
    setContentView(binding.root)

    if (savedInstanceState != null) {
      playbackParamsHelper.restoreInstanceState(savedInstanceState)
    }

    binding.basicPlayerPlaybackIdIn.hint = playbackParamsHelper.playbackIdOrDefault()

    binding.basicPlayerUpdateMediaItem.setOnClickListener {
      playbackParamsHelper.playbackId = binding.basicPlayerPlaybackIdIn.text?.trim()?.toString()
      playbackParamsHelper.playbackToken =
        binding.basicPlayerPlaybackTokenIn.text?.trim()?.toString()
      playbackParamsHelper.drmToken = binding.basicPlayerDrmTokenIn.text?.trim()?.toString()
      playbackParamsHelper.customDomain = binding.basicPlayerDomainIn.text?.trim()?.toString()

      maybePlayMediaItem(playbackParamsHelper.createMediaItem())
    }
    binding.playbackIdClear.setOnClickListener {
      binding.basicPlayerPlaybackIdIn.text = null
      playbackParamsHelper.playbackId = null
    }
    binding.drmTokenClear.setOnClickListener {
      binding.basicPlayerDrmTokenIn.text = null
      playbackParamsHelper.drmToken = null
    }
    binding.playbackTokenClear.setOnClickListener {
      binding.basicPlayerPlaybackTokenIn.text = null
      playbackParamsHelper.playbackToken = null
    }
    binding.domainClear.setOnClickListener {
      binding.basicPlayerDomainIn.text = null
      playbackParamsHelper.customDomain = null
    }
  }

  override fun onStart() {
    super.onStart()

    val mediaItem = playbackParamsHelper.createMediaItem()

    maybePlayMediaItem(mediaItem)
  }

  override fun onStop() {
    tearDownPlayer()

    super.onStop()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    playbackParamsHelper.saveInstanceState(outState)

    super.onSaveInstanceState(outState)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.basic_player_menu, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    val helperHandled = playbackParamsHelper.handleMenuClick(item)
    if (helperHandled) {
      val newMediaItem = playbackParamsHelper.createMediaItem()
      maybePlayMediaItem(newMediaItem)
      return true
    } else {
      return super.onOptionsItemSelected(item)
    }
  }

  private fun maybePlayMediaItem(mediaItem: MediaItem) {
    val item = mediaItem.buildUpon().setMediaMetadata(createMediaMetadata()).build()
    if (item != player?.currentMediaItem) {
      playSomething(item)
    }
  }

  private fun createMediaMetadata(): MediaMetadata {
    return MediaMetadata.Builder()
      .setTitle("Mux Player Caching Example")
      .build()
  }

  private fun tearDownPlayer() {
    playerView.player = null
    player?.release()
  }

  private fun playSomething(mediaItem: MediaItem) {
    val player = createPlayer(this)
    player.setMediaItem(mediaItem)
    player.prepare()
    player.playWhenReady = true

    this.playerView.player = player
    this.player = player
  }

  @OptIn(UnstableApi::class)
  private fun createPlayer(context: Context): MuxPlayer {
    val out: MuxPlayer = MuxPlayer.Builder(context)
      .enableSmartCache(true)
      .build()

    out.addListener(object : Player.Listener {
      override fun onPlayerError(error: PlaybackException) {
        // todo - better error info than this, inline in ui
        Log.e(TAG, "player error!", error)
        Toast.makeText(
          this@SmartCacheActivity,
          "Playback error! ${error.localizedMessage}",
          Toast.LENGTH_LONG
        ).show()
      }
    })

    return out
  }

  companion object {
    val TAG = SmartCacheActivity::class.simpleName
  }
}
