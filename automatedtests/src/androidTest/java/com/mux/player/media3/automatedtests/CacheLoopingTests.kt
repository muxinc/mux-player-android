package com.mux.player.media3.automatedtests

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.core.internal.deps.guava.base.Objects
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.mux.player.CachePerfTestActivity
import com.mux.player.LoopingTestCase
import com.mux.player.media.PlaybackResolution
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.locks.ReentrantLock

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
    val testCase = LoopingTestCase(
      playbackId = TestCases.VIDEO_1_ID,
//      playbackId = TestCases.TEARS,
      assetName = "Video 1",
      resolution = PlaybackResolution.FHD_1080,
      loops = 2,
      cacheEnabled = true,
    )

    val scenario = ActivityScenario.launch(CachePerfTestActivity::class.java)
//    scenario.moveToState(Lifecycle.State.CREATED)
//    scenario.moveToState(Lifecycle.State.STARTED)
    scenario.moveToState(Lifecycle.State.RESUMED)

    val lock = ReentrantLock()
    val testOver = lock.newCondition()
    val testThread = Thread.currentThread()

    scenario.onActivity { activity ->
      Log.d(TAG, "scenario.onActivity")

      activity.setPlayerListener(object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
          when (playbackState) {
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
              // todo - something other than this
              testThread.interrupt()
            }
          }
        }
      })

      activity.playTestCase(testCase)
    }

    lock.lock()
    testOver.await()
  }


  object TestCases {
    val VIDEO_1_ID = "maVbJv2GSYNRgS02kPXOOGdJMWGU1mkA019ZUjYE7VU7k"
    val VIDEO_2_ID = "23s11nz72DsoN657h4314PjKKjsF2JG33eBQQt6B95I"
    val VIDEO_3_ID = "gZh02tKCI015W6k2XdYSh4srGnksYvsoT1uHsYOlv4Blo"
    val VIDEO_4_ID = "VcmKA6aqzIzlg3MayLJDnbF55kX00mds028Z65QxvBYaA"
    val TEARS = "rojBpoQ8QkSRwvKMsS8FUuCbaANJDN02HRWqFXNBtjH00"
  }

  companion object {
    const val TAG = "CacheLoopingTests"
  }
}
