package com.mux.player.media

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest
import androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest
import com.mux.player.AbsRobolectricTest
import com.mux.player.internal.createNoLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.math.log
import kotlin.math.min

class MuxDrmSessionManagerProviderTests : AbsRobolectricTest() {

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
      drmHttpDataSourceFactory = mockk(relaxed = true),
      logger = createNoLogger()
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
  fun `Only widevine should be supported`() {
    val fakeDrmToken = "fake-drm-token"
    val fakePlaybackId = "fake-playback-id"
    val fakeEndpointHost = "mux.com"
    val mockDataSourceFac = mockk<HttpDataSource.Factory>()

    // object under test
    val drmCallback = MuxDrmCallback(
      drmHttpDataSourceFactory = mockDataSourceFac,
      licenseEndpointHost = fakeEndpointHost,
      drmToken = fakeDrmToken,
      playbackId = fakePlaybackId
    )

    assertThrows(IOException::class.java) {
      drmCallback.executeProvisionRequest(
        uuid = C.PLAYREADY_UUID,
        request = mockk()
      )
    }
    assertThrows(IOException::class.java) {
      drmCallback.executeKeyRequest(
        uuid = C.PLAYREADY_UUID,
        request = mockk()
      )
    }
    assertThrows(IOException::class.java) {
      drmCallback.executeProvisionRequest(
        uuid = C.CLEARKEY_UUID,
        request = mockk()
      )
    }
    assertThrows(IOException::class.java) {
      drmCallback.executeKeyRequest(
        uuid = C.CLEARKEY_UUID,
        request = mockk()
      )
    }
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
      drmHttpDataSourceFactory = mockk(relaxed = true),
      logger = createNoLogger()
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

  @Test
  fun `executeProvisionRequest happy path`() {
    val fakeEndpointHost = "license.fake.endpoint"
    val fakeDrmToken = "fake-drm-token"
    val fakePlaybackId = "fake-playback-id"
    val fakeRequestData = "--init data".toByteArray()
    val fakeLicenseData = "++license data".toByteArray()

    val capturedLicenseReq = slot<DataSpec>()
    val mockDataSourceFac = mockk<HttpDataSource.Factory> {
      val bufferSlot = slot<ByteArray>()
      val offsetSlot = slot<Int>()
      val lengthSlot = slot<Int>()

      every { createDataSource() } returns mockk(relaxed = true) {
        every { open(capture(capturedLicenseReq)) } returns fakeLicenseData.size.toLong()

        var finished = false
        every { read(capture(bufferSlot), capture(offsetSlot), capture(lengthSlot)) } answers {
          val buffer = bufferSlot.captured
          val length = lengthSlot.captured
          val offset = offsetSlot.captured
          println("Asked for len $length")

          val realLength = min(length, fakeLicenseData.size)
          fakeLicenseData.copyInto(
            destination = buffer,
            destinationOffset = offset,
            startIndex = 0,
            endIndex = realLength
          )


          if (finished) {
            C.RESULT_END_OF_INPUT
          } else {
            finished = true
            realLength
          }
        }
      }
    }
    val mockProvisionRequest = mockk<ProvisionRequest> {
      every { data } returns fakeRequestData
      every { defaultUrl } returns "http://fake-url?fakeparam=fakevalue"
    }
    // object under test
    val drmCallback = MuxDrmCallback(
      drmHttpDataSourceFactory = mockDataSourceFac,
      licenseEndpointHost = fakeEndpointHost,
      drmToken = fakeDrmToken,
      playbackId = fakePlaybackId
    )

    val licenseData = drmCallback.executeProvisionRequest(
      uuid = C.WIDEVINE_UUID,
      request = mockProvisionRequest
    )

    // Data to CDM
    assertEquals(
      "license data should be returned to caller",
      fakeLicenseData.contentToString(), licenseData.contentToString()
    )

    // Request to provision endpoint
    val capturedProvisionRequestUrl = capturedLicenseReq.captured.uri
    val capturedSignedRequestParam = capturedProvisionRequestUrl.getQueryParameter("signedRequest")
    assertEquals(
      "signedRequestParam should be gathered from default URL",
          fakeRequestData.decodeToString(), capturedSignedRequestParam
    )
  }

  @Test
  fun `executeProvisionRequest license request fails`() {
    val fakeEndpointHost = "license.fake.endpoint"
    val fakeDrmToken = "fake-drm-token"
    val fakePlaybackId = "fake-playback-id"
    val fakeRequestData = "--init data".toByteArray() //as in, license request data
    val fakeLicenseData = "++license data".toByteArray()

    val mockDataSourceFac = mockk<HttpDataSource.Factory> {
      every { createDataSource() } returns mockk(relaxed = true) {
        every { open(any()) } returns fakeLicenseData.size.toLong()

        every { read(any(), any(), any()) } answers {
          throw IOException("whoops")
        }
      }
    }
    val mockProvisionRequest = mockk<ProvisionRequest> {
      every { data } returns fakeRequestData
      every { defaultUrl } returns "fake-url"
    }
    // object under test
    val drmCallback = MuxDrmCallback(
      drmHttpDataSourceFactory = mockDataSourceFac,
      licenseEndpointHost = fakeEndpointHost,
      drmToken = fakeDrmToken,
      playbackId = fakePlaybackId
    )

    // errors are all handled by rethrowing
    assertThrows(
      IOException::class.java
    ) {
      drmCallback.executeProvisionRequest(
        uuid = C.WIDEVINE_UUID,
        request = mockProvisionRequest
      )
    }
  }

  @Test
  fun `executeKeyRequest happy path`() {
    val fakeEndpointHost = "license.fake.endpoint"
    val fakeDrmToken = "fake-drm-token"
    val fakePlaybackId = "fake-playback-id"
    val fakeRequestData = "--init data".toByteArray() //as in, license request data
    val fakeKeyData = "++key data".toByteArray()

    val capturedKeyRequest = slot<DataSpec>()
    val mockDataSourceFac = mockk<HttpDataSource.Factory> {
      val bufferSlot = slot<ByteArray>()
      val offsetSlot = slot<Int>()
      val lengthSlot = slot<Int>()

      every { createDataSource() } returns mockk(relaxed = true) {
        every { open(capture(capturedKeyRequest)) } returns fakeKeyData.size.toLong()

        var finished = false
        every { read(capture(bufferSlot), capture(offsetSlot), capture(lengthSlot)) } answers {
          val buffer = bufferSlot.captured
          val length = lengthSlot.captured
          val offset = offsetSlot.captured
          println("Asked for len $length")

          val realLength = min(length, fakeKeyData.size)
          fakeKeyData.copyInto(
            destination = buffer,
            destinationOffset = offset,
            startIndex = 0,
            endIndex = realLength
          )


          if (finished) {
            C.RESULT_END_OF_INPUT
          } else {
            finished = true
            realLength
          }
        }
      }
    }
    val mockProvisionRequest = mockk<KeyRequest> {
      every { data } returns fakeRequestData
    }
    // object under test
    val drmCallback = MuxDrmCallback(
      drmHttpDataSourceFactory = mockDataSourceFac,
      licenseEndpointHost = fakeEndpointHost,
      drmToken = fakeDrmToken,
      playbackId = fakePlaybackId
    )

    val keyData = drmCallback.executeKeyRequest(
      uuid = C.WIDEVINE_UUID,
      request = mockProvisionRequest
    )

    // Data to CDM
    assertEquals(
      "license data should be returned to caller",
      fakeKeyData.contentToString(), keyData.contentToString()
    )

    // Request to license proxy
    val capturedCertRequestBody = capturedKeyRequest.captured.httpBody
    val capturedCertRequestHeaders = capturedKeyRequest.captured.httpRequestHeaders
    assertEquals(
      "Request body from license request should come from provision request",
      fakeRequestData, capturedCertRequestBody
    )
    val capturedContentLen =
      capturedCertRequestHeaders.mapKeys { it.key.lowercase() }["content-type"]
    assertEquals(
      "Request should be application/octet-stream",
      "application/octet-stream", capturedContentLen
    )
  }

  @Test
  fun `executeKeyRequest request to license proxy fails`() {
    val fakeEndpointHost = "license.fake.endpoint"
    val fakeDrmToken = "fake-drm-token"
    val fakePlaybackId = "fake-playback-id"
    val fakeRequestData = "--init data".toByteArray() //as in, license request data
    val fakeKeyData = "++key data".toByteArray()

    val mockDataSourceFac = mockk<HttpDataSource.Factory> {
      every { createDataSource() } returns mockk(relaxed = true) {
        every { open(any()) } returns fakeKeyData.size.toLong()

        every { read(any(), any(), any()) } answers {
          throw IOException("failed")
        }
      }
    }
    val mockKeyRequest = mockk<KeyRequest> {
      every { data } returns fakeRequestData
    }
    // object under test
    val drmCallback = MuxDrmCallback(
      drmHttpDataSourceFactory = mockDataSourceFac,
      licenseEndpointHost = fakeEndpointHost,
      drmToken = fakeDrmToken,
      playbackId = fakePlaybackId
    )

    assertThrows(
      IOException::class.java
    ) {
      drmCallback.executeKeyRequest(
        uuid = C.WIDEVINE_UUID,
        request = mockKeyRequest
      )
    }
  }
}