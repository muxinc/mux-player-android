package com.mux.player.offline

/**
 * A **snapshot** of a single Mux offline download, keyed by its playback ID.
 *
 * You don't create these yourself; the SDK returns them from [MuxDownloadManager].
 */
@ConsistentCopyVisibility
data class MuxDownload internal constructor(
  /** The Mux playback ID this download was started for. Stable for the life of the download. */
  val playbackId: String,
  /** The download's state at the time this snapshot was taken. See [State]. */
  val state: State,
  /**
   * How much of the download has completed, in the range `0.0..100.0`, or `-1f`
   * ([androidx.media3.common.C.PERCENTAGE_UNSET]) if not yet known.
   */
  val percentDownloaded: Float,
  /** Bytes downloaded to disk so far at the time of this snapshot. */
  val bytesDownloaded: Long,
  /**
   * Total size of the download in bytes, or `-1` ([androidx.media3.common.C.LENGTH_UNSET]) if not
   * yet known (the content length isn't resolved until the download begins).
   */
  val totalBytes: Long,
) {

  /**
   * The lifecycle state of a [MuxDownload].
   *
   * Beyond the states Media3's `DownloadManager` reports, this adds two of Mux's own:
   * [STARTING], for the work Mux does *before* a download is handed to the `DownloadManager`
   * (fetching the HLS manifests and, for DRM content, acquiring the offline license), and
   * [EXPIRED], for a download whose offline DRM license has run out.
   */
  enum class State {
    /**
     * Mux is preparing the download — fetching manifests and (for DRM) acquiring the offline
     * license. This precedes [QUEUED].
     * If preparation fails, the next snapshot is [FAILED]; there is no `DownloadManager` entry for
     * a download that never left this state.
     */
    STARTING,

    /** Queued on the `DownloadManager`, waiting for a slot (or for requirements to be met). */
    QUEUED,

    /** Actively downloading media to disk. */
    DOWNLOADING,

    /** Fully downloaded and available for offline playback. */
    COMPLETED,

    /**
     * Fully downloaded, but its offline DRM license has run out, so it can no longer be played.
     * The media is still on disk and still taking up space.
     *
     * Renew the license with [MuxDownloadManager.renewOfflineLicense] to make it playable again
     * without re-downloading anything — that needs a network and a fresh DRM token. Otherwise
     * delete it with [MuxDownloadManager.removeDownload].
     *
     * Only DRM-protected downloads can expire. Non-protected and signed-playback downloads are
     * playable until they are removed
     */
    EXPIRED,

    /**
     * The download failed. When reported via [MuxDownloadManager.Listener.onDownloadChanged], the
     * cause is provided.
     */
    FAILED,

    /** The download is being removed and its media deleted. */
    REMOVING,

    /** Stopped (e.g. paused) and will not progress until resumed. */
    STOPPED,
  }
}
