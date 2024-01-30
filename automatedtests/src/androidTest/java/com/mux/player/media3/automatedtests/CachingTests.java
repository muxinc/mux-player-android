package com.mux.player.media3.automatedtests;

import static org.junit.Assert.fail;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import com.mux.player.cacheing.ProxyServer;
import java.net.MalformedURLException;
import java.net.URL;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class CachingTests extends TestBase{

  protected ProxyServer proxyServer;

  @Before
  public void init() {
    // start proxy server on port 6000, run in seprate thread by default
    proxyServer = new ProxyServer(6000);
    try {
      urlToPlay = proxyServer.encodeUrl(
          new URL(
              "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8")
      ).toString();
    } catch (MalformedURLException ex) {
      fail(ex.getMessage());
    }
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
