package com.mux.player.offline

/**
 * A **snapshot** of a single Mux offline download, keyed by its playback ID.
 *
 * Instances are immutable point-in-time readings. The [state] and progress values
 * ([percentDownloaded], [bytesDownloaded], [totalBytes]) reflect the download **at the moment the
 * snapshot was created** and are *never* updated afterward. To observe changes over time, register a
 * [Listener] with [MuxOfflineDownloads.addListener] (each callback delivers a fresh snapshot) or
 * re-query [MuxOfflineDownloads.getDownload]/[MuxOfflineDownloads.allDownloads].
 *
 * You don't create these yourself; the SDK hands them to you from [MuxOfflineDownloads].
 */
class MuxDownload internal constructor(
  /** The Mux playback ID this download was started for. Stable for the life of the download. */
  val playbackId: String,
  /** The download's state at the time this snapshot was taken. See [State]. */
  val state: State,
  /**
   * How much of the download has completed, in the range `0.0..100.0`, or `-1f`
   * ([androidx.media3.common.C.PERCENTAGE_UNSET]) if not yet known. Snapshot only — not live.
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
   * Beyond the states Media3's `DownloadManager` reports, this adds [STARTING] to cover the work Mux
   * does *before* a download is handed to the `DownloadManager`: fetching the HLS manifests and, for
   * DRM content, acquiring the offline license. During that window there is no `DownloadManager`
   * entry yet, so [STARTING] is the only signal that a download is in flight.
   */
  enum class State {
    /**
     * Mux is preparing the download — fetching manifests and (for DRM) acquiring the offline
     * license — but it has **not** been queued on the `DownloadManager` yet. This precedes [QUEUED].
     * If preparation fails, the next snapshot is [FAILED]; there is no `DownloadManager` entry for a
     * download that never left this state.
     */
    STARTING,

    /** Queued on the `DownloadManager`, waiting for a slot (or for requirements to be met). */
    QUEUED,

    /** Actively downloading media to disk. */
    DOWNLOADING,

    /** Fully downloaded and available for offline playback. */
    COMPLETED,

    /** The download failed. When reported via [Listener.onDownloadChanged], the cause is provided. */
    FAILED,

    /** The download is being removed and its media deleted. */
    REMOVING,

    /** Stopped (e.g. paused) and will not progress until resumed. */
    STOPPED,
  }

  /**
   * Observes offline-download progress and lifecycle changes.
   *
   * Callbacks are driven by Media3's `DownloadManager.Listener` (plus Mux's [State.STARTING] phase),
   * translated to deliver [MuxDownload] snapshots instead of raw Media3 types. All callbacks are
   * delivered on the `DownloadManager`'s application looper (the main thread in normal use).
   *
   * Register with [MuxOfflineDownloads.addListener] and unregister with
   * [MuxOfflineDownloads.removeListener].
   */
  interface Listener {
    /**
     * Called whenever a download's [state][State] or progress changes, including the initial
     * [State.STARTING] snapshot emitted by [MuxOfflineDownloads.startDownload].
     *
     * @param download a fresh snapshot of the download at this transition.
     * @param error the cause when [download].state is [State.FAILED], otherwise `null`.
     */
    fun onDownloadChanged(download: MuxDownload, error: Throwable?)

    /**
     * Called when a download has been removed. [download] is the last snapshot before removal.
     */
    fun onDownloadRemoved(download: MuxDownload) {}

    /**
     * Called when there is a change in whether one or more downloads are stalled *solely* because
     * the `DownloadManager`'s requirements (e.g. network connectivity) are not met.
     */
    fun onWaitingForRequirementsChanged(waitingForRequirements: Boolean) {}
  }

  override fun toString(): String =
    "MuxDownload(playbackId='$playbackId', state=$state, percentDownloaded=$percentDownloaded, " +
      "bytesDownloaded=$bytesDownloaded, totalBytes=$totalBytes)"

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is MuxDownload) return false
    return playbackId == other.playbackId
      && state == other.state
      && percentDownloaded == other.percentDownloaded
      && bytesDownloaded == other.bytesDownloaded
      && totalBytes == other.totalBytes
  }

  override fun hashCode(): Int {
    var result = playbackId.hashCode()
    result = 31 * result + state.hashCode()
    result = 31 * result + percentDownloaded.hashCode()
    result = 31 * result + bytesDownloaded.hashCode()
    result = 31 * result + totalBytes.hashCode()
    return result
  }
}