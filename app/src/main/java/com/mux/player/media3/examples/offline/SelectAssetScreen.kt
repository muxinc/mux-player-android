package com.mux.player.media3.examples.offline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview

/**
 * Screen 2: the assets available to download. Tapping one starts its download and returns to
 * [DownloadsScreen], where its progress shows up.
 *
 * Stateless — the caller supplies [assets] and handles the callbacks.
 */
@Composable
fun SelectAssetScreen(
  assets: List<DownloadableAsset>,
  onAssetClick: (DownloadableAsset) -> Unit,
  onBackClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = { SelectAssetTopBar(onBackClick = onBackClick) },
  ) { insets ->
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = insets,
    ) {
      items(assets, key = { it.playbackId }) { asset ->
        AssetListItem(
          title = asset.title,
          playbackId = asset.playbackId,
          onClick = { onAssetClick(asset) },
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectAssetTopBar(onBackClick: () -> Unit) {
  TopAppBar(
    title = { Text("Select Asset") },
    navigationIcon = {
      TextButton(onClick = onBackClick) { Text("Back") }
    },
  )
}

/** One downloadable asset. Simplified for now: its title over its playback ID. */
@Composable
fun AssetListItem(
  title: String,
  playbackId: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  ListItem(
    modifier = modifier.clickable(onClick = onClick),
    headlineContent = {
      Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    },
    supportingContent = {
      Text(text = playbackId, maxLines = 1, overflow = TextOverflow.Ellipsis)
    },
  )
}

@Preview(showBackground = true)
@Composable
private fun AssetListItemPreview() {
  MaterialTheme {
    Surface {
      AssetListItem(
        title = "Tears of Steel",
        playbackId = "rojBpoQ8QkSRwvKMsS8FUuCbaANJDN02HRWqFXNBtjH00",
        onClick = {},
      )
    }
  }
}