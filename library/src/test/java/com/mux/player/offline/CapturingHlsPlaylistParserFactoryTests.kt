package com.mux.player.offline

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import com.mux.player.AbsRobolectricTest
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayInputStream

@OptIn(UnstableApi::class)
class CapturingHlsPlaylistParserFactoryTests : AbsRobolectricTest() {

  @Test
  fun `the no-arg parser reports a parsed multivariant playlist to onMainManifest`() {
    val parsed = mockk<HlsMultivariantPlaylist>(relaxed = true)
    var reportedMain: HlsMultivariantPlaylist? = null
    var reportedMedia: HlsMediaPlaylist? = null

    val factory = capturingFactory(
      innerResult = parsed,
      onMainManifest = { reportedMain = it },
      onMediaPlaylist = { reportedMedia = it },
    )

    val result = factory.createPlaylistParser().parse(Uri.EMPTY, emptyStream())

    assertSame("the delegate's parsed playlist is returned unchanged", parsed, result)
    assertSame("multivariant playlists go to onMainManifest", parsed, reportedMain)
    assertNull("onMediaPlaylist should not fire for a multivariant playlist", reportedMedia)
  }

  @Test
  fun `the no-arg parser reports a parsed media playlist to onMediaPlaylist`() {
    val parsed = mockk<HlsMediaPlaylist>(relaxed = true)
    var reportedMain: HlsMultivariantPlaylist? = null
    var reportedMedia: HlsMediaPlaylist? = null

    val factory = capturingFactory(
      innerResult = parsed,
      onMainManifest = { reportedMain = it },
      onMediaPlaylist = { reportedMedia = it },
    )

    factory.createPlaylistParser().parse(Uri.EMPTY, emptyStream())

    assertSame("media playlists go to onMediaPlaylist", parsed, reportedMedia)
    assertNull("onMainManifest should not fire for a media playlist", reportedMain)
  }

  @Test
  fun `the multivariant-scoped parser reports media playlists to onMediaPlaylist`() {
    val parsed = mockk<HlsMediaPlaylist>(relaxed = true)
    var reportedMedia: HlsMediaPlaylist? = null

    val factory = capturingFactory(
      innerResult = parsed,
      onMainManifest = { },
      onMediaPlaylist = { reportedMedia = it },
    )

    // the overload the tracker uses for playlists referenced by a multivariant playlist
    factory.createPlaylistParser(mockk(relaxed = true), null).parse(Uri.EMPTY, emptyStream())

    assertSame(parsed, reportedMedia)
  }

  private fun capturingFactory(
    innerResult: HlsPlaylist,
    onMainManifest: (HlsMultivariantPlaylist) -> Unit,
    onMediaPlaylist: (HlsMediaPlaylist) -> Unit,
  ): CapturingHlsPlaylistParserFactory {
    val innerParser = mockk<ParsingLoadable.Parser<HlsPlaylist>> {
      every { parse(any(), any()) } returns innerResult
    }
    val delegate = mockk<HlsPlaylistParserFactory> {
      every { createPlaylistParser() } returns innerParser
      every { createPlaylistParser(any(), any()) } returns innerParser
    }
    return CapturingHlsPlaylistParserFactory(
      delegate = delegate,
      onMainManifest = onMainManifest,
      onMediaPlaylist = onMediaPlaylist,
    )
  }

  private fun emptyStream() = ByteArrayInputStream(ByteArray(0))
}