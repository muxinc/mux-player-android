package com.mux.player.offline

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable

/**
 * An [androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory] that observes the parsed result so callers can capture playlists as
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
    observing(
      delegate.createPlaylistParser(
        multivariantPlaylist, previousMediaPlaylist
      )
    )

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