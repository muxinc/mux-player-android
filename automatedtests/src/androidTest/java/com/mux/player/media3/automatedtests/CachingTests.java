package com.mux.player.media3.automatedtests;

import static org.junit.Assert.fail;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import com.mux.player.cacheing.ProxyServer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class CachingTests extends TestBase{

  protected ProxyServer proxyServer;

  @Before
  public void init() {
    urlToPlay = "http://localhost:6000/.m3u8";
    // start proxy server on port 6000, run in seprate thread by default
    proxyServer = new ProxyServer(6000);
    super.init();
  }

  @Test
  public void testProxyPlayback() {
    try {
      if (!testActivity.waitForPlaybackToStart(waitForPlaybackToStartInMS * 10)) {
        fail("Playback did not start in " + waitForPlaybackToStartInMS + " milliseconds !!!");
      }
    } catch (Exception e) {
      fail(getExceptionFullTraceAndMessage(e));
    }
    Log.e(TAG, "All done !!!");
  }
}
