package com.mux.player.media3.examples.carousel

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.mux.player.MuxPlayer
import java.lang.ref.WeakReference

class PlayerCarouselViewModel(val app: Application): AndroidViewModel(app) {

  val player: MuxPlayer get() = _player

  // player only settable from here
  private var _player: MuxPlayer = createPlayer()
  private var currentMediaItem: MediaItem? = null
  // Gotta remember the old current view so we can unset it's `player` when we play a new item
  private var currentPlayerView: WeakReference<PlayerView> = WeakReference(null)

  override fun onCleared() {
    _player.release()
    currentMediaItem = null
    // no need to blank out currentPlayerView, it's a WeakReference

    super.onCleared()
  }

  fun playIntoView(playerView: PlayerView) {
    Log.d("PlayerCarouselViewModel", "playIntoView(): called")
    val oldView = currentPlayerView.get()
    if (oldView != null) {
      oldView.player = null
    }

    currentPlayerView = WeakReference(playerView)
    playerView.player = _player
  }

  fun changeMediaItem(item: MediaItem) {
    _player.stop()
    _player.setMediaItem(item)
    _player.prepare()
  }

  fun play() {
    _player.play()
  }

  fun pause() {
    _player.pause()
  }

  fun togglePlay() {
    if (_player.playWhenReady) {
      pause()
    } else {
      play()
    }
  }

  @OptIn(UnstableApi::class)
  private fun createPlayer(): MuxPlayer {
    val player = MuxPlayer.Builder(app)
      .enableSmartCache(true)
      .enableLogcat(true)
      .applyExoConfig {
        setSeekBackIncrementMs(1000)
        setSeekForwardIncrementMs(1000)
      }
      .build()

    player.addListener(object : Player.Listener {
      override fun onPlayerError(error: PlaybackException) {
        Log.e("PlayerCarouselViewModel", "Player error!", error)
        Toast.makeText(app, error.localizedMessage, Toast.LENGTH_LONG).show()
      }
    })
    return player
  }
}
