package com.mux.player

import android.content.Context
import android.util.Base64
import com.mux.player.cacheing.CacheController
import com.mux.player.cacheing.CacheDatastore
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.net.URL

class CacheControllerTests : AbsRobolectricTest() {

  @Before
  fun setUpCacheController() {
    val mockDatastore = mockk<CacheDatastore> {

    }
    val mockContext = mockk<Context> {
      every { applicationContext } returns mockk {

      }
    }

    CacheController.setup(mockContext, mockDatastore)
  }

  @Test
  fun `shouldCacheResponse returns false for no-store`() {
    fun testTheCase(cacheControl: String) {
      val responseHeaders = mapOf("Cache-Control" to listOf(cacheControl))

      val shouldCache = CacheController.shouldCacheResponse("https://mux.com/xyz", responseHeaders)
      Assert.assertFalse(
        "Response with Cache-Control header [$cacheControl] should not " +
                "be cached", shouldCache
      )
    }

    val cacheControlValueSimple = "no-store"
    val cacheControlValueComplex = "no-cache no-store must-revalidate"
    val cacheControlValueConflicting = "no-store max-age=12345"

    testTheCase(cacheControlValueComplex)
    testTheCase(cacheControlValueSimple)
    testTheCase(cacheControlValueConflicting)
  }

  @Test
  fun `segmentCacheKey generates different keys for segments`() {
    val notASegmentUrl = "https://manifest-gcp-us-east4-vop1.cfcdn.mux.com/efg456hjk/rendition.m3u8"
    val hlsSegmentUrl = "https://chunk-gcp-us-east4-vop1.cfcdn.mux.com/v1/chunk/hls123abc/0.ts"
    val cmafSegmentUrl = "https://chunk-gcp-us-east4-vop1.cfcdn.mux.com/v1/chunk/cmaf456def/146.m4s"

    val notASegmentKey =
      CacheController.generateCacheKey(URL(notASegmentUrl))
    val hlsKey = CacheController.generateCacheKey(URL(hlsSegmentUrl))
    val cmafKey = CacheController.generateCacheKey(URL(cmafSegmentUrl))

    Assert.assertEquals(
      "Non-segment URLs key on the entire URL",
      notASegmentUrl, notASegmentKey
    )
    Assert.assertNotEquals(
      "HLS segment URLs have a special key",
      hlsKey, hlsSegmentUrl
    )
    Assert.assertNotEquals(
      "CMAF segment URLs have a special key",
      cmafKey, cmafSegmentUrl
    )
  }

  @Test
  fun `segmentCacheKey generates cache keys for segments correctly`() {
    val segmentUrl = "https://chunk-gcp-us-east4-vop1.cfcdn.mux.com/v1/chunk/abc12345xyz/0.ts"
    val expectedKey = "/v1/chunk/abc12345xyz/0.ts"

    val key = CacheController.generateCacheKey(URL(segmentUrl))
    Assert.assertEquals(
      "cache key should be constructed properly",
      expectedKey, key
    )
  }
}