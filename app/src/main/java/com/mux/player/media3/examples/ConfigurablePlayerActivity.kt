package com.mux.player.media3.examples

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.mux.stats.sdk.core.model.CustomData
import com.mux.stats.sdk.core.model.CustomerData
import com.mux.stats.sdk.core.model.CustomerVideoData
import com.mux.stats.sdk.core.model.CustomerViewData
import com.mux.stats.sdk.core.util.UUID
import com.mux.player.MuxPlayer
import com.mux.player.media3.R
import com.mux.player.media3.databinding.ActivityConfigurablePlayerBinding

/**
 * A configurable example that uses the normal media3 player UI to play a video in the foreground from
 * Mux Video, using a Playback ID
 *
 * You can configure the Activity via the UI
 */
class ConfigurablePlayerActivity : AppCompatActivity() {

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

    binding.configurablePlayerPlaybackIdIn.hint = playbackParamsHelper.playbackIdOrDefault()

    binding.configurablePlayerUpdateMediaItem.setOnClickListener {
      playbackParamsHelper.playbackId = binding.configurablePlayerPlaybackIdIn.text?.trim()?.toString()
      playbackParamsHelper.playbackToken =
        binding.configurablePlayerPlaybackTokenIn.text?.trim()?.toString()
      playbackParamsHelper.drmToken = binding.configurablePlayerDrmTokenIn.text?.trim()?.toString()
      playbackParamsHelper.customDomain = binding.configurablePlayerDomainIn.text?.trim()?.toString()

      maybePlayMediaItem(playbackParamsHelper.createMediaItem())
    }
    binding.configurablePlaybackIdClear.setOnClickListener {
      binding.configurablePlayerPlaybackIdIn.text = null
      playbackParamsHelper.playbackId = null
    }
    binding.configurablePlayerDrmTokenClear.setOnClickListener {
      binding.configurablePlayerDrmTokenIn.text = null
      playbackParamsHelper.drmToken = null
    }
    binding.configurablePlayerPlaybackTokenClear.setOnClickListener {
      binding.configurablePlayerPlaybackTokenIn.text = null
      playbackParamsHelper.playbackToken = null
    }
    binding.configurablePlayerDomainClear.setOnClickListener {
      binding.configurablePlayerDomainIn.text = null
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
      .setTitle("Mux Player Example")
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
      .addMonitoringData(
        CustomerData().apply {
          customerViewData = CustomerViewData().apply {
            viewSessionId = UUID.generateUUID()
          }
          customerVideoData = CustomerVideoData().apply {
            videoSeries = "My Series"
            videoId = "abc1234zyxw"
          }
          customData = CustomData().apply {
            customData1 = "my custom metadata field"
            customData2 = "another custom metadata field"
            customData10 = "up to 10 custom fields"
          }
        }
      )
      .applyExoConfig {
        // Call ExoPlayer.Builder methods here
        setHandleAudioBecomingNoisy(true)
        setSeekBackIncrementMs(10_000)
        setSeekForwardIncrementMs(30_000)
      }
      .build()

    out.addListener(object : Player.Listener {
      override fun onPlayerError(error: PlaybackException) {
        // todo - better error info than this, inline in ui
        Log.e(TAG, "player error!", error)
        Toast.makeText(
          this@ConfigurablePlayerActivity,
          "Playback error! ${error.localizedMessage}",
          Toast.LENGTH_LONG
        ).show()
      }
    })

    return out
  }

  companion object {
    val TAG = ConfigurablePlayerActivity::class.simpleName
  }
}

// todo - the viewholder thing is kind of annoying here but like a View is not out of line

class TextParamEntryView constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

  private val binding: TextParamEntryBinding

  init {
    binding = TextParamEntryBinding.inflate(LayoutInflater.from(context), this, true)
    // You can now access views in the layout using binding.viewId
    // For example: binding.textView.text = "Hello"
  }
}