package com.mux.player

import android.content.Context
import com.mux.player.internal.cache.CacheController
import com.mux.player.internal.cache.CacheDatastore
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert
import org.junit.Before
import org.junit.Test

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
}
