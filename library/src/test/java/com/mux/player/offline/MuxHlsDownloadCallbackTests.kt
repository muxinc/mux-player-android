package com.mux.player.offline

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import com.mux.player.AbsRobolectricTest
import com.mux.player.media.MuxDrmSessionManagerProvider
import io.mockk.Called
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
import java.io.IOException
import java.util.concurrent.Executor

@OptIn(UnstableApi::class)
class MuxHlsDownloadCallbackTests : AbsRobolectricTest() {

  @After
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `clear content emits a request keyed by playbackId with no keySetId`() {
    val playbackId = "fake-playback-id"
    val helper = mockk<DownloadHelper>(relaxed = true)
    val mediaSource = mockk<MuxOfflineCmafHlsMediaSource>(relaxed = true) {
      every { capturedMultivariantPlaylist } returns
          capturedMultivariant(
            widevineData = null,
            playlist = multivariantPlaylist(audios = listOf(rendition("audio"))),
          )
      every { selectedMediaPlaylists } returns emptyList()
    }
    val drmProvider = mockk<MuxDrmSessionManagerProvider>(relaxed = true)

    var ready: DownloadRequest? = null
    var errored: IOException? = null
    val callback = MuxHlsDownloadCallback(
      mediaSource = mediaSource,
      drmProvider = drmProvider,
      playbackId = playbackId,
      drmToken = null,
      ioExecutor = directExecutor(),
      onReady = { ready = it },
      onError = { errored = it },
    )

    callback.onPrepared(helper, /* tracksInfoAvailable = */ false)

    assertNull("clear content should not fail", errored)
    assertEquals("request should be keyed by playbackId", playbackId, ready?.id)
    assertEquals("request should target the manifest uri", MANIFEST_URI, ready?.uri)
    assertNull("clear content should carry no keySetId", ready?.keySetId)
    // clear content must never reach for a license
    verify { drmProvider wasNot Called }
  }

  @Test
  fun `onPrepareError releases the helper and forwards the error`() {
    val helper = mockk<DownloadHelper>(relaxed = true)
    val thrown = IOException("prepare failed")

    var errored: IOException? = null
    val callback = MuxHlsDownloadCallback(
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

    val helper = mockk<DownloadHelper>(relaxed = true)
    val mediaSource = mockk<MuxOfflineCmafHlsMediaSource>(relaxed = true) {
      every { capturedMultivariantPlaylist } returns
          capturedMultivariant(widevineData = sessionKeyInitData, playlist = multivariantPlaylist())
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
    val callback = MuxHlsDownloadCallback(
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
          capturedMultivariant(widevineData = widevineInitData(), playlist = multivariantPlaylist())
      every { selectedMediaPlaylists } returns emptyList()
    }

    var ready: DownloadRequest? = null
    var errored: IOException? = null
    val callback = MuxHlsDownloadCallback(
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
  fun `stream keys select the top-bitrate variant with its matching audio and subtitle renditions`() {
    val playbackId = "id"
    // per-tier bundled audio (like Mux): each variant binds its own audio group; subs shared
    val variants = listOf(
      variant(bitrate = 900_000, audioGroup = "audio-lo", subtitleGroup = "subs"),
      variant(bitrate = 5_000_000, audioGroup = "audio-hi", subtitleGroup = "subs"), // top
      variant(bitrate = 1_800_000, audioGroup = "audio-med", subtitleGroup = "subs"),
    )
    val audios = listOf(
      rendition("audio-lo"),  // 0
      rendition("audio-hi"),  // 1  <- belongs to the top variant
      rendition("audio-med"), // 2
    )
    val subtitles = listOf(rendition("subs")) // 0
    val mediaSource = mockk<MuxOfflineCmafHlsMediaSource>(relaxed = true) {
      every { capturedMultivariantPlaylist } returns
          capturedMultivariant(
            widevineData = null,
            playlist = multivariantPlaylist(variants = variants, audios = audios, subtitles = subtitles),
          )
      every { selectedMediaPlaylists } returns emptyList()
    }

    var ready: DownloadRequest? = null
    val callback = MuxHlsDownloadCallback(
      mediaSource = mediaSource,
      drmProvider = mockk(relaxed = true),
      playbackId = playbackId,
      drmToken = null,
      ioExecutor = directExecutor(),
      onReady = { ready = it },
      onError = { },
    )

    callback.onPrepared(mockk(relaxed = true), /* tracksInfoAvailable = */ true)

    assertEquals(
      "top variant (idx 1) + its audio group (idx 1) + subtitle (idx 0), and nothing else",
      setOf(
        StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, 1),
        StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, 1),
        StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_SUBTITLE, 0),
      ),
      ready?.streamKeys?.toSet(),
    )
  }

  private fun widevineInitData(): DrmInitData =
    DrmInitData(DrmInitData.SchemeData(C.WIDEVINE_UUID, "video/mp4", byteArrayOf(1, 2, 3)))

  private fun capturedMultivariant(
    widevineData: DrmInitData?,
    playlist: HlsMultivariantPlaylist,
  ): MuxOfflineCmafHlsMediaSource.CapturedMultivariantPlaylist =
    MuxOfflineCmafHlsMediaSource.CapturedMultivariantPlaylist(widevineData, playlist)

  private fun variant(
    bitrate: Int,
    audioGroup: String? = "audio",
    subtitleGroup: String? = null,
  ): HlsMultivariantPlaylist.Variant =
    HlsMultivariantPlaylist.Variant(
      /* url = */ Uri.parse("https://stream.mux.com/v_$bitrate.m3u8"),
      /* format = */ Format.Builder().setPeakBitrate(bitrate).build(),
      /* videoGroupId = */ null,
      /* audioGroupId = */ audioGroup,
      /* subtitleGroupId = */ subtitleGroup,
      /* captionGroupId = */ null,
      /* pathwayId = */ null,
      /* stableVariantId = */ null,
    )

  private fun rendition(group: String, name: String = "Default"): HlsMultivariantPlaylist.Rendition =
    HlsMultivariantPlaylist.Rendition(
      /* url = */ Uri.parse("https://stream.mux.com/$group.m3u8"),
      /* format = */ Format.Builder().build(),
      /* groupId = */ group,
      /* name = */ name,
      /* stableRenditionId = */ null,
    )

  private fun multivariantPlaylist(
    variants: List<HlsMultivariantPlaylist.Variant> = listOf(variant(bitrate = 1_000_000)),
    audios: List<HlsMultivariantPlaylist.Rendition> = emptyList(),
    subtitles: List<HlsMultivariantPlaylist.Rendition> = emptyList(),
  ): HlsMultivariantPlaylist =
    HlsMultivariantPlaylist(
      /* baseUri = */ MANIFEST_URI.toString(),
      /* tags = */ emptyList(),
      /* variants = */ variants,
      /* videos = */ emptyList(),
      /* audios = */ audios,
      /* subtitles = */ subtitles,
      /* closedCaptions = */ emptyList(),
      /* muxedAudioFormat = */ null,
      /* muxedCaptionFormats = */ emptyList(),
      /* hasIndependentSegments = */ false,
      /* variableDefinitions = */ emptyMap(),
      /* sessionKeyDrmInitData = */ emptyList(),
    )

  private fun directExecutor() = Executor { it.run() }

  private companion object {
    private val MANIFEST_URI: Uri = Uri.parse("https://stream.mux.com/fake.m3u8")
  }
}
