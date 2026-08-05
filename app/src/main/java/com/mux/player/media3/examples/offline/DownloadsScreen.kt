package com.mux.player.media3.examples.offline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import com.mux.player.offline.MuxDownload

/**
 * Screen 1: everything Mux Player has downloaded or is downloading, plus a row for adding more.
 *
 * Stateless — the caller supplies [downloads] and handles all of the callbacks. Tapping a completed
 * download plays it; tapping anything else does nothing (there's nothing to play yet).
 */
@Composable
fun DownloadsScreen(
  downloads: List<MuxDownload>,
  onAddDownloadClick: () -> Unit,
  onPlayDownload: (MuxDownload) -> Unit,
  onRemoveDownload: (MuxDownload) -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = { DownloadsTopBar() },
  ) { insets ->
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = insets,
    ) {
      item(key = "add-download") {
        AddDownloadListItem(onClick = onAddDownloadClick)
        HorizontalDivider()
      }

      items(downloads, key = { it.playbackId }) { download ->
        DownloadListItem(
          title = DownloadableAssets.titleFor(download.playbackId),
          state = download.state,
          percentDownloaded = download.percentDownloaded,
          onClick = { onPlayDownload(download) },
          onRemoveClick = { onRemoveDownload(download) },
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsTopBar() {
  TopAppBar(title = { Text("Offline Downloads") })
}

/**
 * One download. Simplified for now: a title, its state, and a progress bar while it's in flight.
 *
 * Takes the download's fields individually rather than a [MuxDownload] so it can be previewed —
 * [MuxDownload]'s constructor is internal to the SDK, since only the SDK ever creates one.
 *
 * @param percentDownloaded `0.0..100.0`, or [C.PERCENTAGE_UNSET] if not yet known.
 * @param onClick Invoked only when this download is [MuxDownload.State.COMPLETED], i.e. playable.
 */
@Composable
fun DownloadListItem(
  title: String,
  state: MuxDownload.State,
  percentDownloaded: Float,
  onClick: () -> Unit,
  onRemoveClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val playable = state == MuxDownload.State.COMPLETED

  ListItem(
    modifier = modifier.clickable(enabled = playable, onClick = onClick),
    headlineContent = {
      Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    },
    supportingContent = {
      Column {
        Text(text = state.label(percentDownloaded))
        if (state.isInFlight()) {
          DownloadProgressBar(percentDownloaded)
        }
      }
    },
    trailingContent = {
      TextButton(onClick = onRemoveClick) {
        Text("Delete")
      }
    },
  )
}

/** The row that opens the 'Select Asset' screen. */
@Composable
fun AddDownloadListItem(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  ListItem(
    modifier = modifier.clickable(onClick = onClick),
    headlineContent = { Text("+  Download an asset") },
  )
}

@Composable
private fun DownloadProgressBar(percentDownloaded: Float, modifier: Modifier = Modifier) {
  val barModifier = modifier
    .fillMaxWidth()
    .padding(top = 4.dp)

  // The DownloadManager doesn't know the content length until the download actually starts, so
  // there's a window where the only honest thing to show is an indeterminate bar.
  if (percentDownloaded == C.PERCENTAGE_UNSET.toFloat()) {
    LinearProgressIndicator(modifier = barModifier)
  } else {
    LinearProgressIndicator(
      progress = { percentDownloaded / 100f },
      modifier = barModifier,
    )
  }
}

/** True while the download is working toward completion, so progress is worth showing. */
private fun MuxDownload.State.isInFlight(): Boolean = when (this) {
  MuxDownload.State.STARTING,
  MuxDownload.State.QUEUED,
  MuxDownload.State.DOWNLOADING,
  MuxDownload.State.REMOVING -> true

  MuxDownload.State.COMPLETED,
  MuxDownload.State.FAILED,
  MuxDownload.State.STOPPED -> false
}

private fun MuxDownload.State.label(percentDownloaded: Float): String = when (this) {
  MuxDownload.State.STARTING -> "Preparing…"
  MuxDownload.State.QUEUED -> "Queued"
  MuxDownload.State.DOWNLOADING -> "Downloading ${percentDownloaded.toInt()}%"
  MuxDownload.State.COMPLETED -> "Ready to play offline"
  MuxDownload.State.FAILED -> "Failed"
  MuxDownload.State.REMOVING -> "Removing…"
  MuxDownload.State.STOPPED -> "Paused"
}

@Preview(showBackground = true)
@Composable
private fun DownloadListItemDownloadingPreview() {
  MaterialTheme {
    Surface {
      DownloadListItem(
        title = "Tears of Steel",
        state = MuxDownload.State.DOWNLOADING,
        percentDownloaded = 42f,
        onClick = {},
        onRemoveClick = {},
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun DownloadListItemStartingPreview() {
  MaterialTheme {
    Surface {
      DownloadListItem(
        title = "Elephants Dream",
        state = MuxDownload.State.STARTING,
        percentDownloaded = (-1).toFloat(),
        onClick = {},
        onRemoveClick = {},
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun DownloadListItemCompletedPreview() {
  MaterialTheme {
    Surface {
      DownloadListItem(
        title = "The Making of Big Buck Bunny",
        state = MuxDownload.State.COMPLETED,
        percentDownloaded = 100f,
        onClick = {},
        onRemoveClick = {},
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun DownloadListItemFailedPreview() {
  MaterialTheme {
    Surface {
      DownloadListItem(
        title = "Mux Marketing Video",
        state = MuxDownload.State.FAILED,
        percentDownloaded = 12f,
        onClick = {},
        onRemoveClick = {},
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun AddDownloadListItemPreview() {
  MaterialTheme {
    Surface {
      AddDownloadListItem(onClick = {})
    }
  }
}