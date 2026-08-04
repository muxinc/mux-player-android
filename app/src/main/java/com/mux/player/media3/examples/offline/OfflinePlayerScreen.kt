package com.mux.player.media3.examples.offline

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.mux.player.MuxPlayer
import com.mux.player.media.MediaItems

/**
 * Screen 3: plays a completed download, from disk.
 *
 * The only offline-specific part is the [MediaItem][androidx.media3.common.MediaItem]:
 * [MediaItems.forMuxDownload] points at the downloaded copy instead of the network, and
 * [MuxPlayer]'s default `MediaSource.Factory` knows how to read it. Nothing else about the player
 * setup changes, and no `MuxDownloadManager` lookup is needed — which is why this screen doesn't
 * touch [OfflineDownloadsViewModel].
 *
 * The player is owned by the composition (created here, released on the way out) rather than by a
 * ViewModel, so it can't outlive the screen.
 */
@Composable
fun OfflinePlayerScreen(
  playbackId: String,
  onBackClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val playerView = remember { PlayerView(context) }

  DisposableEffect(playbackId) {
    val player = MuxPlayer.Builder(context)
      .enableLogcat(true)
      .build()

    player.addListener(object : Player.Listener {
      override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "Offline playback error for $playbackId", error)
        Toast.makeText(context, "Playback error: ${error.localizedMessage}", Toast.LENGTH_LONG)
          .show()
      }
    })

    player.setMediaItem(MediaItems.forMuxDownload(playbackId))
    player.prepare()
    player.playWhenReady = true
    playerView.player = player

    onDispose {
      playerView.player = null
      player.release()
    }
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = { OfflinePlayerTopBar(playbackId = playbackId, onBackClick = onBackClick) },
  ) { insets ->
    Box(modifier = Modifier.fillMaxSize().padding(insets)) {
      AndroidView(
        factory = { playerView },
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfflinePlayerTopBar(playbackId: String, onBackClick: () -> Unit) {
  TopAppBar(
    title = { Text(DownloadableAssets.titleFor(playbackId)) },
    navigationIcon = {
      IconButton(onClick = onBackClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
      }
    },
  )
}

private const val TAG = "OfflinePlayerScreen"
