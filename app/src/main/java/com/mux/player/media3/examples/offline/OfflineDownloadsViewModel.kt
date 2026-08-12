package com.mux.player.media3.examples.offline

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mux.player.offline.MuxDownload
import com.mux.player.offline.MuxDownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the list of Mux offline downloads and nothing else. The screens in this example are
 * stateless: they take [downloads] plus callbacks, and the composable that hosts them
 * ([OfflineDownloadsExample]) is the only thing that talks to this ViewModel.
 *
 * Two sources feed [downloads]:
 * 1. [MuxDownloadManager.allDownloads], read once, for downloads that already existed on disk when
 *    this screen opened (downloads survive app restarts).
 * 2. [MuxDownloadManager.Listener], for live progress and state changes from here on.
 *
 * Only the first of those reports [MuxDownload.State.EXPIRED] — a DRM license running out isn't a
 * download event, so it's something you query for, not something you're told about.
 */
class OfflineDownloadsViewModel(private val app: Application) : AndroidViewModel(app) {

  /**
   * Every download known to Mux Player, in any state — in-progress and completed alike. Ordered
   * oldest-first, with newly started downloads appended.
   */
  val downloads: StateFlow<List<MuxDownload>> get() = _downloads.asStateFlow()

  private val _downloads = MutableStateFlow<List<MuxDownload>>(emptyList())

  private val downloadListener = object : MuxDownloadManager.Listener {
    override fun onDownloadChanged(download: MuxDownload, error: Throwable?) {
      if (error != null) {
        Log.e(TAG, "Download failed for playback ID ${download.playbackId}", error)
      }
      upsert(download)
    }

    override fun onDownloadRemoved(download: MuxDownload) {
      _downloads.update { downloads ->
        downloads.filterNot { it.playbackId == download.playbackId }
      }
    }

    override fun onWaitingForRequirementsChanged(waitingForRequirements: Boolean) {
      // Downloads are stalled only because the DownloadManager's Requirements aren't met (by
      // default, that means no network). A real app might surface this in its UI.
      Log.i(TAG, "Waiting for download requirements: $waitingForRequirements")
    }
  }

  init {
    // Listen before reading the index, so a change that lands mid-read isn't missed.
    MuxDownloadManager.addListener(app, downloadListener)

    viewModelScope.launch {
      val alreadyOnDisk = MuxDownloadManager.allDownloads(app)
      _downloads.update { live ->
        // Anything the listener already told us about is fresher than the index read, so it wins.
        val livePlaybackIds = live.mapTo(mutableSetOf()) { it.playbackId }
        alreadyOnDisk.filterNot { it.playbackId in livePlaybackIds } + live
      }
    }
  }

  override fun onCleared() {
    MuxDownloadManager.removeListener(downloadListener)
    super.onCleared()
  }

  /**
   * Starts downloading [asset]. Progress arrives via [downloads]; the download continues in
   * [com.mux.player.offline.MuxDownloadService] even if this screen goes away.
   */
  fun startDownload(asset: DownloadableAsset) {
    MuxDownloadManager.startDownload(app, asset.toMediaItem())
  }

  /** Deletes the download for [playbackId], including its media and any offline DRM license. */
  fun removeDownload(playbackId: String) {
    MuxDownloadManager.removeDownload(app, playbackId)
  }

  private fun upsert(download: MuxDownload) {
    _downloads.update { downloads ->
      val index = downloads.indexOfFirst { it.playbackId == download.playbackId }
      if (index < 0) {
        downloads + download
      } else {
        downloads.toMutableList().apply { this[index] = download }
      }
    }
  }

  private companion object {
    const val TAG = "OfflineDownloadsVM"
  }
}