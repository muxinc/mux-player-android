package com.mux.player.offline

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSession.DrmSessionException
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.WritableDownloadIndex
import com.mux.player.AbsRobolectricTest
import com.mux.player.media.MuxDrmSessionManagerProvider
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Tests for [MuxDownloadManager.renewOfflineLicenseBlocking], the offline-license renewal behind
 * [MuxDownloadManager.renewOfflineLicense].
 *
 * There's no CDM under Robolectric, so everything that talks to one is stubbed at the `OfflineUtilsKt`
 * seam: the license request ([MuxDrmSessionManagerProvider.renewOfflineLicense]), the expiry check
 * ([isOfflineLicenseExpired]), and the stale-license purge ([dropOfflineLicense]). What's under test
 * is everything around them — which downloads are eligible, what gets persisted, and which license
 * gets purged.
 */
@OptIn(UnstableApi::class)
class OfflineLicenseRenewalTests : AbsRobolectricTest() {

  private lateinit var index: WritableDownloadIndex
  private lateinit var store: MuxPlayerDownloadStore

  @Before
  fun setUp() {
    index = mockk(relaxed = true)
    store = mockk(relaxed = true) {
      every { downloadIndex } returns index
      every { downloadManager } returns mockk<DownloadManager> {
        every { applicationLooper } returns Looper.getMainLooper()
      }
    }

    mockkStatic("com.mux.player.offline.OfflineUtilsKt")
    every {
      any<MuxDrmSessionManagerProvider>().renewOfflineLicense(any(), any(), any(), any())
    } returns RENEWED_KEY_SET_ID
    // The purge is what a couple of these tests observe, so it has to be stubbed either way
    every { dropOfflineLicense(any()) } just Runs
    // Snapshotting a completed download asks the CDM whether its license is still good
    every { localOfflineLicenseHelper() } returns mockk(relaxed = true)
    every { any<OfflineLicenseHelper>().isOfflineLicenseExpired(any()) } returns false
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `renewing a completed DRM download persists the renewed keySetId`() {
    every { index.getDownload(PLAYBACK_ID) } returns completedDrmDownload()
    val persisted = capturePutDownload()

    renew()

    assertArrayEquals(
      "the renewed keySetId should be the one the download plays with from now on",
      RENEWED_KEY_SET_ID,
      persisted.captured.request.keySetId,
    )
  }

  @Test
  fun `renewing keeps the download's state, progress and content length`() {
    every { index.getDownload(PLAYBACK_ID) } returns completedDrmDownload()
    val persisted = capturePutDownload()

    renew()

    val renewed = persisted.captured
    assertEquals(
      "a renewed license doesn't change how much of the asset is on disk",
      Download.STATE_COMPLETED, renewed.state,
    )
    assertEquals(CONTENT_LENGTH, renewed.contentLength)
    assertEquals(BYTES_DOWNLOADED, renewed.bytesDownloaded)
    assertEquals(100f, renewed.percentDownloaded, 0f)
    assertEquals(START_TIME_MS, renewed.startTimeMs)
    assertEquals(
      "the URI the asset was downloaded from must survive renewal",
      completedDrmDownload().request.uri, renewed.request.uri,
    )
  }

  @Test
  fun `renewing returns a snapshot of a download that's playable again`() {
    every { index.getDownload(PLAYBACK_ID) } returns completedDrmDownload()

    val snapshot = renew()

    assertEquals(PLAYBACK_ID, snapshot.playbackId)
    assertEquals(MuxDownload.State.COMPLETED, snapshot.state)
    assertEquals(BYTES_DOWNLOADED, snapshot.bytesDownloaded)
  }

  @Test
  fun `a renewed license that's already spent is reported as expired`() {
    every { index.getDownload(PLAYBACK_ID) } returns completedDrmDownload()
    // The license server can hand back a license whose windows have already run out
    every { any<OfflineLicenseHelper>().isOfflineLicenseExpired(RENEWED_KEY_SET_ID) } returns true

    val snapshot = renew()

    assertEquals(
      "the caller shouldn't have to guess whether renewal actually helped",
      MuxDownload.State.EXPIRED, snapshot.state,
    )
  }

  @Test
  fun `a keySetId the CDM replaced has its stale license purged`() {
    every { index.getDownload(PLAYBACK_ID) } returns completedDrmDownload()

    renew()

    verify(exactly = 1) { dropOfflineLicense(ORIGINAL_KEY_SET_ID) }
  }

  @Test
  fun `a license renewed in place is not purged`() {
    every { index.getDownload(PLAYBACK_ID) } returns completedDrmDownload()
    // Widevine commonly renews in place and hands back the same keySetId. Purging that would delete
    // the license we just renewed.
    every {
      any<MuxDrmSessionManagerProvider>().renewOfflineLicense(any(), any(), any(), any())
    } returns ORIGINAL_KEY_SET_ID.copyOf()

    renew()

    verify(exactly = 0) { dropOfflineLicense(any()) }
  }

  @Test
  fun `renewing goes to the license host for a custom domain`() {
    every { index.getDownload(PLAYBACK_ID) } returns completedDrmDownload()

    val host = slot<String>()
    every {
      any<MuxDrmSessionManagerProvider>().renewOfflineLicense(any(), any(), capture(host), any())
    } returns RENEWED_KEY_SET_ID

    renew(domain = "custom.abc1234.com")

    assertEquals("license.custom.abc1234.com", host.captured)
  }

  @Test
  fun `renewing goes to Mux's license host by default`() {
    every { index.getDownload(PLAYBACK_ID) } returns completedDrmDownload()

    val host = slot<String>()
    every {
      any<MuxDrmSessionManagerProvider>().renewOfflineLicense(any(), any(), capture(host), any())
    } returns RENEWED_KEY_SET_ID

    renew()

    assertEquals("license.mux.com", host.captured)
  }

  @Test
  fun `renewing renews the license the download already has`() {
    every { index.getDownload(PLAYBACK_ID) } returns completedDrmDownload()

    val playbackId = slot<String>()
    val drmToken = slot<String>()
    val keySetId = slot<ByteArray>()
    every {
      any<MuxDrmSessionManagerProvider>()
        .renewOfflineLicense(capture(playbackId), capture(drmToken), any(), capture(keySetId))
    } returns RENEWED_KEY_SET_ID

    renew()

    assertEquals(PLAYBACK_ID, playbackId.captured)
    assertEquals(DRM_TOKEN, drmToken.captured)
    assertArrayEquals(
      "renewal has to start from the keySetId stored on the download",
      ORIGINAL_KEY_SET_ID, keySetId.captured,
    )
  }

  @Test
  fun `renewing an unknown playback ID fails`() {
    every { index.getDownload(PLAYBACK_ID) } returns null

    assertThrows(IllegalArgumentException::class.java) { renew() }
    verify(exactly = 0) { index.putDownload(any()) }
  }

  @Test
  fun `renewing a download that isn't finished fails`() {
    every { index.getDownload(PLAYBACK_ID) } returns
        completedDrmDownload(state = Download.STATE_DOWNLOADING)

    assertThrows(IllegalStateException::class.java) { renew() }
    verify(exactly = 0) { index.putDownload(any()) }
  }

  @Test
  fun `renewing a clear download fails`() {
    every { index.getDownload(PLAYBACK_ID) } returns completedDrmDownload(keySetId = null)

    assertThrows(IllegalStateException::class.java) { renew() }
    verify(exactly = 0) { index.putDownload(any()) }
  }

  @Test
  fun `a failed license request leaves the download alone`() {
    every { index.getDownload(PLAYBACK_ID) } returns completedDrmDownload()
    every {
      any<MuxDrmSessionManagerProvider>().renewOfflineLicense(any(), any(), any(), any())
    } throws DrmSessionException(
      IOException("no license for you"),
      PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
    )

    assertThrows(IOException::class.java) { renew() }
    verify(exactly = 0) { index.putDownload(any()) }
    verify(exactly = 0) { dropOfflineLicense(any()) }
  }

  @Test
  fun `a license failure that isn't an IOException is reported as one`() {
    every { index.getDownload(PLAYBACK_ID) } returns completedDrmDownload()
    every {
      any<MuxDrmSessionManagerProvider>().renewOfflineLicense(any(), any(), any(), any())
    } throws IllegalArgumentException("the CDM didn't like that")

    // Callers shouldn't have to catch whatever the platform decided to throw
    assertThrows(IOException::class.java) { renew() }
  }

  @Test
  fun `a second renewal of the same asset is rejected while the first is in flight`() {
    every { index.getDownload(PLAYBACK_ID) } returns completedDrmDownload()

    var reentrantFailure: Throwable? = null
    every {
      any<MuxDrmSessionManagerProvider>().renewOfflineLicense(any(), any(), any(), any())
    } answers {
      // Renewal is a network round-trip, so a UI can easily ask for a second one mid-flight
      reentrantFailure = runCatching { renew() }.exceptionOrNull()
      RENEWED_KEY_SET_ID
    }

    renew()

    assertTrue(
      "a renewal that's already in flight should be refused, not run twice: $reentrantFailure",
      reentrantFailure is IllegalStateException,
    )
  }

  @Test
  fun `a rejected renewal doesn't block later ones`() {
    every { index.getDownload(PLAYBACK_ID) } returns null
    runCatching { renew() }

    every { index.getDownload(PLAYBACK_ID) } returns completedDrmDownload()
    val persisted = capturePutDownload()

    renew()

    assertArrayEquals(RENEWED_KEY_SET_ID, persisted.captured.request.keySetId)
  }

  private fun renew(domain: String? = null): MuxDownload =
    MuxDownloadManager.renewOfflineLicenseBlocking(store, PLAYBACK_ID, DRM_TOKEN, domain)

  private fun capturePutDownload(): io.mockk.CapturingSlot<Download> =
    slot<Download>().also { every { index.putDownload(capture(it)) } just Runs }

  private fun completedDrmDownload(
    state: Int = Download.STATE_COMPLETED,
    keySetId: ByteArray? = ORIGINAL_KEY_SET_ID,
  ): Download = Download(
    DownloadRequest.Builder(PLAYBACK_ID, Uri.parse("https://stream.mux.com/$PLAYBACK_ID.m3u8"))
      .setMimeType(MimeTypes.APPLICATION_M3U8)
      .setKeySetId(keySetId)
      .build(),
    state,
    START_TIME_MS,
    /* updateTimeMs = */ START_TIME_MS + 30_000L,
    CONTENT_LENGTH,
    Download.STOP_REASON_NONE,
    Download.FAILURE_REASON_NONE,
    DownloadProgress().apply {
      bytesDownloaded = BYTES_DOWNLOADED
      percentDownloaded = 100f
    },
  )

  private companion object {
    const val PLAYBACK_ID = "abc123playbackId"
    const val DRM_TOKEN = "a-fresh-drm-token"
    const val START_TIME_MS = 1_700_000_000_000L
    const val CONTENT_LENGTH = 42_000_000L
    const val BYTES_DOWNLOADED = 42_000_000L

    val ORIGINAL_KEY_SET_ID = byteArrayOf(1, 2, 3, 4)
    val RENEWED_KEY_SET_ID = byteArrayOf(5, 6, 7, 8)
  }
}
