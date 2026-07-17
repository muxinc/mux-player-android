package com.mux.player.offline

import androidx.annotation.OptIn
import androidx.media3.common.DrmInitData
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.source.WrappingMediaSource
import java.util.concurrent.ConcurrentHashMap

/**
 * A WrappingMediaSource that wraps an HlsMediaSource and captures the playlists is parses, along
 * with their DrmInitData. If the HLS stream had widevine protection data, it will be captured
 * and accessible via [selectedMediaPlaylists] and [capturedMultivariantPlaylist]
 *
 * Build one with [create]. The captures are populated from the tracker's loader threads (one per
 * media playlist, so concurrently) during preparation, and are held in thread-safe containers keyed
 * by playlist URI.
 *
 * This class should only be used to download VOD streams, especially if they are DRM'd
 */
@OptIn(UnstableApi::class)
class MuxOfflineCmafHlsMediaSource private constructor(
  wrapped: HlsMediaSource,
  private val captures: Captures
) : WrappingMediaSource(wrapped) {

  /**
   * A snapshot of the media playlists parsed so far, each paired with its `#EXT-X-KEY`
   * [androidx.media3.common.DrmInitData] — deduped by playlist URI.
   *
   * The tracker only loads the media playlist for a rendition once that rendition is selected.
   *
   * This is only guaranteed complete after `DownloadHelper` preparation has completed (i.e. from
   * `DownloadHelper.Callback.onPrepared`)
   */
  val selectedMediaPlaylists: List<CapturedMediaPlaylist> get() = captures.media.values.toList()

  /** The multivariant playlist + its `#EXT-X-SESSION-KEY` [androidx.media3.common.DrmInitData], once parsed. */
  val capturedMultivariantPlaylist: CapturedMultivariantPlaylist? get() = captures.multivariant

  /** A media playlist paired with the [androidx.media3.common.DrmInitData] from its (single) `#EXT-X-KEY`. */
  @OptIn(UnstableApi::class)
  data class CapturedMediaPlaylist(
    val capturedDrmInitData: DrmInitData?,
    val playlist: HlsMediaPlaylist,
  )

  /** A multivariant playlist paired with the [DrmInitData] from its `#EXT-X-SESSION-KEY`. */
  @OptIn(UnstableApi::class)
  data class CapturedMultivariantPlaylist(
    val capturedWidevineData: DrmInitData?,
    val playlist: HlsMultivariantPlaylist,
  )

  /** Thread-safe sink shared with the parser-factory callbacks. */
  private class Captures {
    /** Keyed by playlist [androidx.media3.exoplayer.hls.playlist.HlsPlaylist.baseUri]: concurrent
     *  puts from per-bundle loader threads are safe, and subsequent parses of the same rendition
     *  (live refresh) overwrite existing entries
     */
    val media = ConcurrentHashMap<String, CapturedMediaPlaylist>()

    /** Only one multivariant playlist exists, so a single safely-published reference is enough. */
    @Volatile var multivariant: CapturedMultivariantPlaylist? = null
  }

  companion object {
    /**
     * Builds a [MuxOfflineCmafHlsMediaSource] for [mediaItem].
     *
     * @param dataSourceFactory upstream used to fetch playlists (and, when played, segments).
     * @param drmSessionManagerProvider optional — supply it so the DRM track isn't dropped as
     *   unselectable during preparation.
     */
    fun create(
      dataSourceFactory: DataSource.Factory,
      mediaItem: MediaItem,
      drmSessionManagerProvider: DrmSessionManagerProvider? = null,
    ): MuxOfflineCmafHlsMediaSource {
      val captures = Captures()
      val parserFactory = CapturingHlsPlaylistParserFactory(
        onMainManifest = { multivariant ->
          captures.multivariant = CapturedMultivariantPlaylist(
            capturedWidevineData = multivariant.firstWidevineSessionKeyDrmInitData(),
            playlist = multivariant,
          )
        },
        onMediaPlaylist = { media ->
          captures.media[media.baseUri] = CapturedMediaPlaylist(
            capturedDrmInitData = media.firstSegmentDrmInitData(),
            playlist = media,
          )
        },
      )

      val wrapped = HlsMediaSource.Factory(dataSourceFactory)
        .setPlaylistParserFactory(parserFactory)
        .apply {
          drmSessionManagerProvider?.let { setDrmSessionManagerProvider(it) }
        }
        .createMediaSource(mediaItem)

      return MuxOfflineCmafHlsMediaSource(wrapped, captures)
    }
  }
}