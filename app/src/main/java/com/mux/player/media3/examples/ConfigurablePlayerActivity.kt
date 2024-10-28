package com.mux.player.media3.examples

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
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
import com.mux.player.media3.databinding.NumericParamEntryBinding
import com.mux.player.media3.databinding.TextParamEntryBinding

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

    binding.configurablePlayerPlaybackId.hint = playbackParamsHelper.playbackIdOrDefault()
    binding.configurablePlayerPlaybackId.onClear = { playbackParamsHelper.playbackId = null }
    binding.configurablePlayerCustomDomain.onClear = { playbackParamsHelper.customDomain = null }
    binding.configurablePlayerInstantclipStart.onClear =
      { playbackParamsHelper.assetStartTime = null }
    binding.configurablePlayerInstantclipEnd.onClear = { playbackParamsHelper.assetEndTime = null }
    binding.configurablePlayerPlaybackToken.onClear = { playbackParamsHelper.playbackToken = null }
    binding.configurablePlayerDrmToken.onClear = { playbackParamsHelper.playbackToken = null }

    binding.configurablePlayerUpdateMediaItem.setOnClickListener {
      playbackParamsHelper.playbackId = binding.configurablePlayerPlaybackId.entry
      playbackParamsHelper.playbackToken = binding.configurablePlayerPlaybackToken.entry
      playbackParamsHelper.drmToken = binding.configurablePlayerDrmToken.entry
      playbackParamsHelper.customDomain = binding.configurablePlayerCustomDomain.entry
      playbackParamsHelper.assetStartTime = binding.configurablePlayerInstantclipStart.entry
      playbackParamsHelper.assetEndTime = binding.configurablePlayerInstantclipEnd.entry

      maybePlayMediaItem(playbackParamsHelper.createMediaItem())
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

class TextParamEntryView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

  private val binding: TextParamEntryBinding = TextParamEntryBinding.inflate(
    LayoutInflater.from(context),
    this,
    true
  )

  init {
    context.theme.obtainStyledAttributes(attrs, R.styleable.TextParamEntryView, 0, R.style.Theme_MuxVideoMedia3).apply {
      try {
        hint = getString(R.styleable.TextParamEntryView_hint)
      } finally {
        recycle()
      }
    }
    context.theme.obtainStyledAttributes(attrs, R.styleable.ParamEntry, 0, 0).apply {
      try {
        title = getString(R.styleable.ParamEntry_title)
      } finally {
        recycle()
      }
    }
    binding.textParamEntryClear.setOnClickListener {
      binding.textParamEntryIn.text = null
      onClear?.invoke()
    }
  }

  var title: CharSequence? = null
    set(value) {
      binding.textParamEntryLbl.text = value
      field = value
    }
  var hint: CharSequence? = null
    set(value) {
      binding.textParamEntryIn.hint = value
      field = value
    }

  var onClear: (() -> Unit)? = null
  val entry: String? get() {
    val text = binding.textParamEntryIn.text?.trim()?.ifEmpty { null }?.toString()
    return text
  }
}

class NumericParamEntryView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

  private val binding: NumericParamEntryBinding = NumericParamEntryBinding.inflate(
    LayoutInflater.from(context),
    this,
    true
  )

  init {
    context.theme.obtainStyledAttributes(attrs, R.styleable.NumericParamEntryView, 0, 0).apply {
      try {
        hint = getFloat(R.styleable.NumericParamEntryView_hint_num, Float.NaN)
          .toDouble()
          .takeIf { !it.isNaN() }
      } finally {
        recycle()
      }
    }
    context.theme.obtainStyledAttributes(attrs, R.styleable.ParamEntry, 0, 0).apply {
      try {
        title = getString(R.styleable.ParamEntry_title)
      } finally {
        recycle()
      }
    }

    binding.numericParamEntryClear.setOnClickListener {
      binding.numericParamEntryIn.text = null
      onClear?.invoke()
    }
  }

  var title: CharSequence? = null
    set(value) {
      binding.numericParamEntryLbl.text = value
      field = value
    }
  var hint: Double? = null
    set(value) {
      binding.numericParamEntryIn.hint = value?.toString()
      field = value
    }

  var onClear: (() -> Unit)? = null
  val entry: Double? get() {
    val text =
      binding.numericParamEntryIn.text?.trim()?.ifEmpty { null }?.toString()?.toDoubleOrNull()
    return text
  }
}
