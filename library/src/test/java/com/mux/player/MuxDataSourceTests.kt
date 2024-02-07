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

  }
}