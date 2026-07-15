package com.mux.player.offline

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import androidx.media3.exoplayer.upstream.ParsingLoadable
import com.mux.player.AbsRobolectricTest
import com.mux.player.media.MuxDrmSessionManagerProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.Executor

@OptIn(UnstableApi::class)
class OfflineUtilsTests : AbsRobolectricTest() {

  @After
  fun tearDown() {
    unmockkAll()
  }

  // --- CapturingHlsPlaylistParserFactory -----------------------------------------------------

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

  // --- MuxDownloadCallback -------------------------------------------------------------------

  @Test
  fun `clear content emits a request keyed by playbackId with no keySetId`() {
    val playbackId = "fake-playback-id"
    val baseRequest = DownloadRequest.Builder(playbackId, MANIFEST_URI).build()
    val helper = mockk<DownloadHelper>(relaxed = true) {
      every { getDownloadRequest(any<String>(), any()) } returns baseRequest
    }
    val mediaSource = mockk<MuxOfflineCmafHlsMediaSource>(relaxed = true) {
      every { capturedMultivariantPlaylist } returns null
      every { selectedMediaPlaylists } returns emptyList()
    }

    var ready: DownloadRequest? = null
    var errored: IOException? = null
    val callback = MuxDownloadCallback(
      mediaSource = mediaSource,
      drmProvider = mockk(relaxed = true),
      playbackId = playbackId,
      drmToken = null,
      ioExecutor = directExecutor(),
      onReady = { ready = it },
      onError = { errored = it },
    )

    callback.onPrepared(helper, /* tracksInfoAvailable = */ false)

    assertNull("clear content should not fail", errored)
    assertEquals("request should be keyed by playbackId", playbackId, ready?.id)
    assertNull("clear content should carry no keySetId", ready?.keySetId)
    verify(exactly = 1) { helper.getDownloadRequest(playbackId, null) }
  }

  @Test
  fun `onPrepareError releases the helper and forwards the error`() {
    val helper = mockk<DownloadHelper>(relaxed = true)
    val thrown = IOException("prepare failed")

    var errored: IOException? = null
    val callback = MuxDownloadCallback(
      mediaSource = mockk(relaxed = true),
      drmProvider = mockk(relaxed = true),
      playbackId = "id",
      drmToken = null,
      ioExecutor = directExecutor(),
      onReady = { },
      onError = { errored = it },
    )

    callback.onPrepareError(helper, thrown)

    assertSame(thrown, errored)
    verify { helper.release() }
  }

  @Test
  fun `DRM content acquires a license and attaches its keySetId, preferring the session key`() {
    val playbackId = "fake-playback-id"
    val drmToken = "fake-drm-token"
    val keySetId = "++keyset".toByteArray()
    val sessionKeyInitData = widevineInitData()

    val baseRequest = DownloadRequest.Builder(playbackId, MANIFEST_URI).build()
    val helper = mockk<DownloadHelper>(relaxed = true) {
      every { getDownloadRequest(any<String>(), any()) } returns baseRequest
    }
    val mediaSource = mockk<MuxOfflineCmafHlsMediaSource>(relaxed = true) {
      every { capturedMultivariantPlaylist } returns
          MuxOfflineCmafHlsMediaSource.CapturedMultivariantPlaylist(sessionKeyInitData, mockk())
      // present too, but the session key wins
      every { selectedMediaPlaylists } returns
          listOf(MuxOfflineCmafHlsMediaSource.CapturedMediaPlaylist(widevineInitData(), mockk()))
    }

    val drmProvider = mockk<MuxDrmSessionManagerProvider>(relaxed = true)
    val licenseHelper = mockk<OfflineLicenseHelper>(relaxed = true) {
      every { downloadLicense(any()) } returns keySetId
    }
    mockkStatic("com.mux.player.offline.OfflineUtilsKt")
    every { drmProvider.offlineLicenseHelper(playbackId, drmToken, any()) } returns licenseHelper

    var ready: DownloadRequest? = null
    var errored: IOException? = null
    val callback = MuxDownloadCallback(
      mediaSource = mediaSource,
      drmProvider = drmProvider,
      playbackId = playbackId,
      drmToken = drmToken,
      ioExecutor = directExecutor(),
      onReady = { ready = it },
      onError = { errored = it },
    )

    callback.onPrepared(helper, /* tracksInfoAvailable = */ false)

    assertNull(errored)
    assertEquals(playbackId, ready?.id)
    assertArrayEquals("the acquired keySetId should ride the request", keySetId, ready?.keySetId)
    // the session-key init data was the one sent for licensing
    verify { licenseHelper.downloadLicense(match { it.drmInitData == sessionKeyInitData }) }
    verify { licenseHelper.release() }
    verify { helper.release() }
  }

  @Test
  fun `DRM content with no drmToken fails instead of producing an unplayable download`() {
    val helper = mockk<DownloadHelper>(relaxed = true)
    val mediaSource = mockk<MuxOfflineCmafHlsMediaSource>(relaxed = true) {
      every { capturedMultivariantPlaylist } returns
          MuxOfflineCmafHlsMediaSource.CapturedMultivariantPlaylist(widevineInitData(), mockk())
      every { selectedMediaPlaylists } returns emptyList()
    }

    var ready: DownloadRequest? = null
    var errored: IOException? = null
    val callback = MuxDownloadCallback(
      mediaSource = mediaSource,
      drmProvider = mockk(relaxed = true),
      playbackId = "id",
      drmToken = null,
      ioExecutor = directExecutor(),
      onReady = { ready = it },
      onError = { errored = it },
    )

    callback.onPrepared(helper, /* tracksInfoAvailable = */ false)

    assertNull("no request should be emitted without a license", ready)
    assertTrue("missing token is an error", errored is IOException)
    verify { helper.release() }
  }

  @Test
  fun `every audio and subtitle group is selected, and the top video is left alone`() {
    val playbackId = "id"
    val baseRequest = DownloadRequest.Builder(playbackId, MANIFEST_URI).build()

    // renderer 0 = video (skipped), renderer 1 = audio with two renditions (groups)
    val mappedTrackInfo = mockk<MappingTrackSelector.MappedTrackInfo>(relaxed = true) {
      every { rendererCount } returns 2
      every { getRendererType(0) } returns C.TRACK_TYPE_VIDEO
      every { getRendererType(1) } returns C.TRACK_TYPE_AUDIO
      every { getTrackGroups(1) } returns twoTrackGroups()
    }
    val helper = mockk<DownloadHelper>(relaxed = true) {
      every { getMappedTrackInfo(0) } returns mappedTrackInfo
      every { getDownloadRequest(any<String>(), any()) } returns baseRequest
    }
    val mediaSource = mockk<MuxOfflineCmafHlsMediaSource>(relaxed = true) {
      every { capturedMultivariantPlaylist } returns null
      every { selectedMediaPlaylists } returns emptyList()
    }

    val callback = MuxDownloadCallback(
      mediaSource = mediaSource,
      drmProvider = mockk(relaxed = true),
      playbackId = playbackId,
      drmToken = null,
      ioExecutor = directExecutor(),
      onReady = { },
      onError = { },
    )

    callback.onPrepared(helper, /* tracksInfoAvailable = */ true)

    // one selection per audio rendition (group), on the audio renderer
    verify(exactly = 2) {
      helper.addTrackSelectionForSingleRenderer(
        0, 1, DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS, any()
      )
    }
    // the video renderer is never overridden — it keeps the default top-bitrate selection
    verify(exactly = 0) { helper.addTrackSelectionForSingleRenderer(0, 0, any(), any()) }
  }

  // --- helpers -------------------------------------------------------------------------------

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

  private fun widevineInitData(): DrmInitData =
    DrmInitData(DrmInitData.SchemeData(C.WIDEVINE_UUID, "video/mp4", byteArrayOf(1, 2, 3)))

  private fun twoTrackGroups(): TrackGroupArray =
    TrackGroupArray(
      TrackGroup(Format.Builder().setSampleMimeType("audio/mp4a-latm").build()),
      TrackGroup(Format.Builder().setSampleMimeType("audio/mp4a-latm").build()),
    )

  private fun emptyStream() = ByteArrayInputStream(ByteArray(0))

  private fun directExecutor() = Executor { it.run() }

  private companion object {
    private val MANIFEST_URI: Uri = Uri.parse("https://stream.mux.com/fake.m3u8")
  }
}