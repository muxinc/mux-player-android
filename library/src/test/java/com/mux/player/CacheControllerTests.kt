package com.mux.player

import android.content.Context
import com.mux.player.cacheing.CacheConstants
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
      Assert.assertFalse("Response with Cache-Control header [$cacheControl] should not " +
              "be cached", shouldCache)
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
    fun testTheCase(requestUrl: URL, contentType: String) {

    }
  }

  @Test fun `segmentCacheKey generates cache keys for segments correctly`() {
    val segmentUrl =  "https://chunk-gcp-us-east4-vop1.cfcdn.mux.com/v1/chunk/abc12345xyz/0.ts"
    val responseHeaders = mapOf( "Content-Type" to listOf(CacheConstants.MIME_TS) )

    val key = CacheController.segmentCacheKey(URL(segmentUrl), responseHeaders)
    Assert.assertEquals(
      "cache key should be constructed properly",
      "abc12345xyz-0.ts",
      key
      )
  }
}
