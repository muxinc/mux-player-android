package com.mux.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.mux.player.media.MuxDataSource
import com.mux.player.media.MuxMediaSourceFactory
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class MuxDataSourceTests : AbsRobolectricTest() {
  @Test
  fun `open() should change URLs to go to the Proxy`() {
    // todo - object under test (MuxDataSource) returned by `MuxDataSource.Factory` injected to ctor
    val mockContext = mockk<Context>()
    val muxMediaSourceFactory = MuxMediaSourceFactory(
      ctx = mockContext,
      innerFactory = DefaultMediaSourceFactory(mockContext),
    )
  }
}