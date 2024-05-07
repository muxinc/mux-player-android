package com.mux.player.media

import androidx.media3.exoplayer.drm.DrmSessionManager
import com.mux.player.AbsRobolectricTest
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import org.junit.Assert
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuxDrmSessionManagerProviderTests: AbsRobolectricTest() {

  @Test
  fun `get() returns new instances only when the mediaItem is changed`() {
    val mediaItem1 = MediaItems.fromMuxPlaybackId(
      playbackId = "playback id 1",
      playbackToken = "playback token 1",
      drmToken = "drm token 1"
      )
    val mediaItem2 = MediaItems.fromMuxPlaybackId(
      playbackId = "playback id 2",
      playbackToken = "playback token 2",
      drmToken = "drm token 2"
    )
    val provider = MuxDrmSessionManagerProvider(
      drmHttpDataSourceFactory = mockk(relaxed = true)
    )

    val manager1 = provider.get(mediaItem1)
    val manager2 = provider.get(mediaItem2)
    val manager2again = provider.get(mediaItem2)
    // remember, we're comparing instances
    val manager3 = provider.get(mediaItem1)

    assertNotEquals(
      "manager should change when media item changes",
      manager1, manager2
    )
    assertNotEquals(
      "manager should change when media item changes",
      manager1, manager3
    )
    assertNotEquals(
      "manager should change when media item changes",
      manager2, manager3
    )
    assertEquals(
      "manager should not change if the media item is not different",
      manager2, manager2again
    )
  }

  @Test
  fun `DRM only supported with DRM token and Playback Token`() {
    val mediaItemNoTokens = MediaItems.fromMuxPlaybackId("fake-playbackId")
    val mediaItemYesTokens = MediaItems.fromMuxPlaybackId(
      "fake-playbackId",
      drmToken = "drm-token",
      playbackToken = "playback-token",
    )
    val provider = MuxDrmSessionManagerProvider(
      drmHttpDataSourceFactory = mockk(relaxed = true)
    )

    val sessionManagerNoTokens = provider.get(mediaItemNoTokens)
    val sessionManagerYesTokens = provider.get(mediaItemYesTokens)

    assertTrue(
      "Without tokens, DRM is not supported",
      sessionManagerNoTokens == DrmSessionManager.DRM_UNSUPPORTED
    )
    assertFalse(
      "With tokens, DRM is supported",
      sessionManagerYesTokens == DrmSessionManager.DRM_UNSUPPORTED
    )
  }
}