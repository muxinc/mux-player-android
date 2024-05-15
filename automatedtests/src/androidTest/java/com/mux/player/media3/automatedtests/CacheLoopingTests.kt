package com.mux.player.media3.automatedtests

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.mux.player.CachePerfTestCase
import com.mux.player.media.PlaybackResolution
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class CacheLoopingTests {

  @Test
  fun testJustOneCase() {
    val testCase = CachePerfTestCase(
      playbackId = TestCases.VIDEO_1_ID,
      assetName = "Video 1",
      resolution = PlaybackResolution.FHD_1080,
      loops = 5,
    )

  }


  object TestCases {
    val VIDEO_1_ID = "maVbJv2GSYNRgS02kPXOOGdJMWGU1mkA019ZUjYE7VU7k"
    val VIDEO_2_ID = "23s11nz72DsoN657h4314PjKKjsF2JG33eBQQt6B95I"
    val VIDEO_3_ID = "gZh02tKCI015W6k2XdYSh4srGnksYvsoT1uHsYOlv4Blo"
    val VIDEO_4_ID = "VcmKA6aqzIzlg3MayLJDnbF55kX00mds028Z65QxvBYaA"
  }
}
