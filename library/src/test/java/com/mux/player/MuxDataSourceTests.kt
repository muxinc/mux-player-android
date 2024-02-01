package com.mux.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.mux.player.cacheing.CacheConstants
import com.mux.player.media.MuxDataSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert
import org.junit.Test

class MuxDataSourceTests : AbsRobolectricTest() {

  @Test
  fun `open() should change URLs to go to the Proxy`() {
    fun tryCase(uriIn: Uri): Uri {
      val dataSpec = DataSpec.Builder()
        .setUri(uriIn)
        .build()
      val slot = slot<DataSpec>()
      val mockUpstreamDataSrcFac = mockk<HttpDataSource.Factory> {
        every { createDataSource() } returns
                mockk<HttpDataSource>() {
                  every { open(capture(slot)) } returns 0
                }
      }

      // object under test
      val muxDataSource = MuxDataSource.Factory(mockUpstreamDataSrcFac).createDataSource()
      muxDataSource.open(dataSpec)
      return slot.captured.uri
    }

    val httpsUriIn = Uri.parse("https://something.mux.com/a/path/that/ends?q1=dog&q2=cat")
    val httpUriIn = Uri.parse("http://something.mux.com/a/path/that/ends?q1=dog&q2=cat")

    val httpsUriOut = tryCase(httpsUriIn)
    val expectedHttpsOut =
      Uri.parse("http://localhost:${CacheConstants.PROXY_PORT}/1~something.mux.com/a/path/that/ends?q1=dog&q2=cat")
    Assert.assertEquals(
      "Input URI $httpsUriIn transforms to $expectedHttpsOut",
      expectedHttpsOut, httpsUriOut
    )

    val httpUriOut = tryCase(httpUriIn)
    val expectedHttpOut =
      Uri.parse("http://localhost:${CacheConstants.PROXY_PORT}/0~something.mux.com/a/path/that/ends?q1=dog&q2=cat")
    Assert.assertEquals(
      "Input URI $httpUriIn transforms to $expectedHttpOut",
      expectedHttpOut, httpUriOut
    )
  }
}