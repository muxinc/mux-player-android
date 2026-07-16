package com.mux.player.offline

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
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
    val callback = MuxHlsDownloadCallback(
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
          MuxOfflineCmafHlsMediaSource.CapturedMultivariantPlaylist(widevineInitData(), mockk())
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

    val callback = MuxHlsDownloadCallback(
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

  private fun widevineInitData(): DrmInitData =
    DrmInitData(DrmInitData.SchemeData(C.WIDEVINE_UUID, "video/mp4", byteArrayOf(1, 2, 3)))

  private fun twoTrackGroups(): TrackGroupArray =
    TrackGroupArray(
      TrackGroup(Format.Builder().setSampleMimeType("audio/mp4a-latm").build()),
      TrackGroup(Format.Builder().setSampleMimeType("audio/mp4a-latm").build()),
    )

  private fun directExecutor() = Executor { it.run() }

  private companion object {
    private val MANIFEST_URI: Uri = Uri.parse("https://stream.mux.com/fake.m3u8")
  }
}