package com.mux.player.media3.automatedtests

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.mux.player.CachePerfTestActivity
import com.mux.player.CacheTestCase
import com.mux.player.media.PlaybackResolution
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class CacheLoopingTests {

//  @Rule
//  val activityRule = ActivityScenarioRule<CachePerfTestActivity>(
//    Intent(
//      ApplicationProvider.getApplicationContext(),
//      CachePerfTestActivity::class.java
//    )
//  )

  @Test
  fun testJustOneCase() {
    val testCase = CacheTestCase(
      playbackId = TestCases.VIDEO_1_ID,
      assetName = "Video 1",
      resolution = PlaybackResolution.FHD_1080,
      loops = 5,
      cacheEnabled = true,
    )

    val scenario = ActivityScenario.launch(CachePerfTestActivity::class.java)
    scenario.moveToState(Lifecycle.State.CREATED)
    scenario.moveToState(Lifecycle.State.STARTED)
    scenario.moveToState(Lifecycle.State.RESUMED)

    scenario.onActivity { activity ->
      Log.d(TAG, "scenario.onActivity")
      activity.setPlayerListener(object: Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
          when(playbackState) {
            Player.STATE_READY -> {
              Log.i(TAG, "player entered READY")
            }
            Player.STATE_BUFFERING -> {
              Log.i(TAG, "player entered BUFFERING")
            }
            Player.STATE_IDLE -> {
              Log.i(TAG, "player entered IDLE")
            }
            Player.STATE_ENDED -> {
              Log.i(TAG, "player entered ENDED")
            }
          }
        }
      })

      activity.playTestCase(testCase)

      Thread.sleep(240 * 1000)
    }


//    activityRule.scenario.onActivity { activity ->
//      activity.playTestCase(testCase)
//
//      Thread.sleep(1000)
//    }
  }


  object TestCases {
    val VIDEO_1_ID = "maVbJv2GSYNRgS02kPXOOGdJMWGU1mkA019ZUjYE7VU7k"
    val VIDEO_2_ID = "23s11nz72DsoN657h4314PjKKjsF2JG33eBQQt6B95I"
    val VIDEO_3_ID = "gZh02tKCI015W6k2XdYSh4srGnksYvsoT1uHsYOlv4Blo"
    val VIDEO_4_ID = "VcmKA6aqzIzlg3MayLJDnbF55kX00mds028Z65QxvBYaA"
  }

  companion object {
    const val TAG = "CacheLoopingTests"
  }
}
