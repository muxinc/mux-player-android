package com.mux.player.media3.automatedtests

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.mux.player.CachePerfTestActivity
import com.mux.player.LoopingTestCase
import com.mux.player.internal.Instrumentation
import com.mux.player.media.PlaybackResolution
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.locks.ReentrantLock

@LargeTest
@RunWith(AndroidJUnit4::class)
class CacheLoopingTests {

  @Test
  fun testLoop60s10Times() {
    val testCase = LoopingTestCase(
      playbackIds = listOf(
        TestCases.VIDEO_4_ID,
      ),
      name = "Video4, 60s",
      resolution = PlaybackResolution.FHD_1080,
      loopsOverall = 10,
      cacheEnabled = true,
    )
    runTestCase(testCase)
  }

  @Test
  fun testLoop30s10Times() {
    val testCase = LoopingTestCase(
      playbackIds = listOf(
        TestCases.VIDEO_1_ID,
      ),
      name = "Video1, 30s",
      resolution = PlaybackResolution.FHD_1080,
      loopsOverall = 10,
      cacheEnabled = true,
    )
    runTestCase(testCase)
  }

  @Test
  fun testLoopAllThrice() {
    val testCase = LoopingTestCase(
      playbackIds = listOf(
        TestCases.VIDEO_1_ID,
        TestCases.VIDEO_2_ID,
        TestCases.VIDEO_3_ID,
        TestCases.VIDEO_4_ID,
      ),
      name = "All Test Assets",
      resolution = PlaybackResolution.FHD_1080,
      loopsOverall = 3,
      cacheEnabled = true,
    )
    runTestCase(testCase)
  }

  private fun runTestCase(testCase: LoopingTestCase) {
    val scenario = ActivityScenario.launch(CachePerfTestActivity::class.java)
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
              testThread.interrupt()
            }
          }
        }
      })

      activity.playTestCase(testCase)
    }

    runCatching {
      lock.lock()
      testOver.await()
    }

    Log.i(TAG, "Test Complete.")
    Log.i(TAG, "Test Complete. Cache Misses: ${Instrumentation.segmentMisses}")
    Log.i(TAG, "Test Complete. Cache Hits: ${Instrumentation.segmentHits}")
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
