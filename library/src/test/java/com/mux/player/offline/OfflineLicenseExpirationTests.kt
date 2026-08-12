package com.mux.player.offline

import android.util.Pair
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSession.DrmSessionException
import androidx.media3.exoplayer.drm.DrmUtil
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import com.mux.player.AbsRobolectricTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tests for the offline-license expiration check behind [MuxDownload.State.EXPIRED].
 */
@OptIn(UnstableApi::class)
class OfflineLicenseExpirationTests : AbsRobolectricTest() {

  @After
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `a license with both windows remaining is not expired`() {
    assertFalse(
      "a license with time left on both windows should be usable",
      helperReporting(licenseSec = 3_600L, playbackSec = 600L).isOfflineLicenseExpired(KEY_SET_ID),
    )
  }

  @Test
  fun `a license whose license window ran out is expired`() {
    assertTrue(
      "the rental window running out should expire the license",
      helperReporting(licenseSec = 0L, playbackSec = 600L).isOfflineLicenseExpired(KEY_SET_ID),
    )
  }

  @Test
  fun `a license whose play window ran out is expired`() {
    assertTrue(
      "the play window running out should expire the license, even with rental time left",
      helperReporting(licenseSec = 3_600L, playbackSec = 0L).isOfflineLicenseExpired(KEY_SET_ID),
    )
  }

  @Test
  fun `keys the CDM reports as expired are expired`() {
    // What media3 hands back when the CDM raises KeysExpiredException for the keySetId
    assertTrue(
      "0s remaining on both windows is media3 reporting expired keys",
      helperReporting(licenseSec = 0L, playbackSec = 0L).isOfflineLicenseExpired(KEY_SET_ID),
    )
  }

  @Test
  fun `an unreported window doesn't expire a license that has time left on the other`() {
    assertFalse(
      "a play window the CDM didn't report shouldn't be read as a spent one",
      helperReporting(licenseSec = 3_600L, playbackSec = C.TIME_UNSET)
        .isOfflineLicenseExpired(KEY_SET_ID),
    )
  }

  @Test
  fun `a license the CDM reports nothing about is not expired`() {
    assertFalse(
      "with no windows reported there's nothing to conclude",
      helperReporting(licenseSec = C.TIME_UNSET, playbackSec = C.TIME_UNSET)
        .isOfflineLicenseExpired(KEY_SET_ID),
    )
  }

  @Test
  fun `a failed query is not expired`() {
    val helper = mockk<OfflineLicenseHelper> {
      every { getLicenseDurationRemainingSec(any()) } throws
          DrmSessionException(IOException("no CDM for you"), DrmUtil.ERROR_SOURCE_EXO_MEDIA_DRM)
    }

    assertFalse(
      "a query that failed isn't the CDM saying the license is spent",
      helper.isOfflineLicenseExpired(KEY_SET_ID),
    )
  }

  private fun helperReporting(licenseSec: Long, playbackSec: Long): OfflineLicenseHelper =
    mockk {
      every { getLicenseDurationRemainingSec(KEY_SET_ID) } returns Pair(licenseSec, playbackSec)
    }

  private companion object {
    val KEY_SET_ID = byteArrayOf(1, 2, 3, 4)
  }
}
