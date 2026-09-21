package com.mux.player.media3.examples.offline

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * An example of Mux Player's offline downloads: pick an asset, watch it download, then play it back
 * from disk.
 *
 * The three screens are:
 * - [DownloadsScreen], the downloads you have (in progress and complete), plus a row to add more
 * - [SelectAssetScreen], the assets available to download
 * - [OfflinePlayerScreen], playback of a completed download
 *
 * Downloads run in `MuxDownloadService`, not here — see this app's `AndroidManifest.xml` for the
 * `<service>` and permissions an app needs in order to use downloads at all.
 */
class OfflineDownloadsActivity : AppCompatActivity() {

  private val requestNotificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* advisory only */ }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Downloads run in a foreground service that posts a progress notification. Without this
    // permission the downloads still run; the user just doesn't see them.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    setContent {
      MaterialTheme {
        OfflineDownloadsExample()
      }
    }
  }
}

/**
 * Hosts the example's three screens and is the only place that talks to
 * [OfflineDownloadsViewModel]. The screens below it are stateless: they take data and emit events.
 */
@Composable
fun OfflineDownloadsExample(viewModel: OfflineDownloadsViewModel = viewModel()) {
  val downloads by viewModel.downloads.collectAsStateWithLifecycle()
  val renewingPlaybackIds by viewModel.renewingPlaybackIds.collectAsStateWithLifecycle()
  var screen: Screen by remember { mutableStateOf(Screen.Downloads) }

  when (val current = screen) {
    Screen.Downloads -> DownloadsScreen(
      downloads = downloads,
      onAddDownloadClick = { screen = Screen.SelectAsset },
      onPlayDownload = { screen = Screen.Player(it.playbackId) },
      onRemoveDownload = { viewModel.removeDownload(it.playbackId) },
      onRenewLicense = { viewModel.renewLicense(it.playbackId) },
      renewingPlaybackIds = renewingPlaybackIds,
    )

    Screen.SelectAsset -> SelectAssetScreen(
      assets = DownloadableAssets.all,
      onAssetClick = { asset ->
        viewModel.startDownload(asset)
        // Progress shows up on the downloads screen, so go watch it there.
        screen = Screen.Downloads
      },
      onBackClick = { screen = Screen.Downloads },
    )

    is Screen.Player -> OfflinePlayerScreen(
      playbackId = current.playbackId,
      onBackClick = { screen = Screen.Downloads },
    )
  }

  // Downloads is the root screen; anywhere else, back returns to it instead of finishing.
  BackHandler(enabled = screen != Screen.Downloads) {
    screen = Screen.Downloads
  }
}

/**
 * Which screen is showing. Deliberately hand-rolled rather than using a navigation library — this
 * example is about downloads, and three screens don't need one.
 */
private sealed interface Screen {
  data object Downloads : Screen
  data object SelectAsset : Screen
  data class Player(val playbackId: String) : Screen
}