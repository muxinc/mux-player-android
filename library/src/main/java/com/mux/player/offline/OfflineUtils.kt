package com.mux.player.offline

import androidx.annotation.OptIn
import androidx.media3.common.DrmInitData
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.ParsingLoadable

// TODO: Many of these functions should be internal-viz
// Preliminary Design Note: it's my hope that most of the logic a custom integration would need will
//  be here, and could be lifted out without our storage or playback-oriented code

fun createMuxOfflineLicenseHelper(): OfflineLicenseHelper {
  TODO("Not yet implemented")
}

/**
 * Creates a [DownloadHelper] that can download Mux Video HLS streams, including DRM-protected ones
 *
 * This download helper will:
 * * select the top rendition for the given asset (restricted by playback parameters if necessary)
 * * download all audio and subtitle tracks for a given asset
 * * extract widevine PSSH's from the stream and acquire any necessary widevine licenses
 */
fun createMuxHlsDownloadHelper(): DownloadHelper {
  TODO("Not yet implemented")
}

/**
 * An [HlsPlaylistParserFactory] that observes the parsed result so callers can capture playlists as
 * they are loaded by the [androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker].
 *
 * This rides the tracker's existing playlist loads, so observation costs no extra network calls and
 * no segment downloads (chunkless preparation can stay on). It is **observe-only** — the parsed
 * playlists are never mutated.
 */
@OptIn(UnstableApi::class)
class CapturingHlsPlaylistParserFactory(
  val delegate: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
  val onMainManifest: (HlsMultivariantPlaylist) -> Unit,
  val onMediaPlaylist: (HlsMediaPlaylist) -> Unit,
) : HlsPlaylistParserFactory {

  // The initial tracker load uses this; the result may be either a multivariant OR (for a
  // single-rendition master-less stream) a media playlist, so we observe both types.
  override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
    observing(delegate.createPlaylistParser())

  // Media playlists referenced by a multivariant playlist — the #EXT-X-KEY lives here.
  override fun createPlaylistParser(
    multivariantPlaylist: HlsMultivariantPlaylist,
    previousMediaPlaylist: HlsMediaPlaylist?,
  ): ParsingLoadable.Parser<HlsPlaylist> =
    observing(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

  private fun observing(
    inner: ParsingLoadable.Parser<HlsPlaylist>,
  ): ParsingLoadable.Parser<HlsPlaylist> =
    ParsingLoadable.Parser { uri, input ->
      inner.parse(uri, input).also { parsed ->
        when (parsed) {
          is HlsMultivariantPlaylist -> onMainManifest(parsed)
          is HlsMediaPlaylist -> onMediaPlaylist(parsed)
        }
      }
    }
}

/**
 * The [DrmInitData] decoded from the (single) `#EXT-X-KEY`. The stock parser attaches the full PSSH
 * to each segment's [DrmInitData], so we read it off the first segment.
 *
 * (Deliberately NOT [HlsMediaPlaylist.protectionSchemes] — that copy has its PSSH data stripped.)
 */
@OptIn(UnstableApi::class)
fun HlsMediaPlaylist.firstSegmentDrmInitData(): DrmInitData? =
  segments.firstOrNull()?.drmInitData

/** The [DrmInitData] decoded from the multivariant playlist's `#EXT-X-SESSION-KEY`, if any. */
@OptIn(UnstableApi::class)
fun HlsMultivariantPlaylist.firstSessionKeyDrmInitData(): DrmInitData? =
  sessionKeyDrmInitData.firstOrNull()

/**
 * An [HlsMediaSource] (wrapped, since [HlsMediaSource] is final) that uses a
 * [CapturingHlsPlaylistParserFactory] to capture, as the source prepares:
 *
 * * a [CapturedMediaPlaylist] — the primary media playlist plus the [DrmInitData] from its first
 *   segment (assumes a single `#EXT-X-KEY`)
 * * a [CapturedMultivariantPlaylist] — the multivariant playlist plus the [DrmInitData] from its
 *   `#EXT-X-SESSION-KEY`.
 *
 * Build one with [create]. The captured fields are populated on a loader thread during preparation,
 * so they are `@Volatile`; read them after the source has prepared (e.g. from
 * `DownloadHelper.Callback.onPrepared`).
 */
@OptIn(UnstableApi::class)
class MuxOfflineCmafHlsMediaSource private constructor(
  wrapped: HlsMediaSource,
  private val captures: Captures,
) : WrappingMediaSource(wrapped) {

  /** The primary media playlist + its `#EXT-X-KEY` [DrmInitData], once parsed. */
  val capturedMediaPlaylist: CapturedMediaPlaylist? get() = captures.media

  /** The multivariant playlist + its `#EXT-X-SESSION-KEY` [DrmInitData], once parsed. */
  val capturedMultivariantPlaylist: CapturedMultivariantPlaylist? get() = captures.multivariant

  /** A media playlist paired with the [DrmInitData] from its (single) `#EXT-X-KEY`. */
  @OptIn(UnstableApi::class)
  data class CapturedMediaPlaylist(
    val drmInitData: DrmInitData?,
    val playlist: HlsMediaPlaylist,
  )

  /** A multivariant playlist paired with the [DrmInitData] from its `#EXT-X-SESSION-KEY`. */
  @OptIn(UnstableApi::class)
  data class CapturedMultivariantPlaylist(
    val drmInitData: DrmInitData?,
    val playlist: HlsMultivariantPlaylist,
  )

  /** Thread-safe sink shared with the parser-factory callbacks. */
  internal class Captures {
    @Volatile var media: CapturedMediaPlaylist? = null
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
            drmInitData = multivariant.firstSessionKeyDrmInitData(),
            playlist = multivariant,
          )
        },
        onMediaPlaylist = { media ->
          captures.media = CapturedMediaPlaylist(
            drmInitData = media.firstSegmentDrmInitData(),
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
