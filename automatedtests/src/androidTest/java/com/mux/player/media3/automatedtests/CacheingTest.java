package com.mux.player.media3.automatedtests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import android.util.Log;

import com.mux.player.media3.automatedtests.mockup.http.SegmentStatistics;
import com.mux.stats.sdk.core.events.playback.PauseEvent;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

public class CacheingTest extends TestBase {

    protected int waitForSegmentToLoad = 4000;

    public Condition seekDone = lock.newCondition();

    @Before
    public void init() {
        urlToPlay = "http://localhost:5000/hls/google_glass/playlist.m3u8";
        // These video have larger bitrate, make sure we do not cause any
        // rebuffering due to low bandwith
        bandwidthLimitInBitsPerSecond = 10000000;
        enableSmartCache = true;
        super.init();
    }

    @Test
    public void testCachedPlayback() {
        try {
            if (!testActivity.waitForPlaybackToStart(waitForPlaybackToStartInMS)) {
                fail("Playback did not start in " + waitForPlaybackToStartInMS + " milliseconds !!!");
            }
            // wait for server to serve first 5 segment.
            waitForNSegments(8);
            // Seek to beginning
            boolean serverServingData = httpServer.isServingData();
            if (!serverServingData) {
                fail("Serving data API not showing correct state, expected is true");
            }
            testActivity.runOnUiThread(new Runnable() {
                public void run() {
                    pView.getPlayer().seekTo(0);
                    lock.lock();
                    seekDone.signalAll();
                    lock.unlock();
                }
            });
            waitForSeekToFinish();
            Thread.sleep(1000);
            HashMap<String, SegmentStatistics> segmentsAfterSeek = httpServer.getServedSegments();
            for (int i = 0; i < 4; i ++) {
                Thread.sleep(1000);
                serverServingData = httpServer.isServingData();
                if (serverServingData) {
                    HashMap<String, SegmentStatistics> segments = httpServer.getServedSegments();
                    fail("Data is being served while playing from the cached media segment");
                }
            }
            Thread.sleep(waitForSegmentToLoad);
            HashMap<String, SegmentStatistics> segmentsAfterCachePlayback = httpServer.getServedSegments();
            if (segmentsAfterSeek.size() != segmentsAfterCachePlayback.size()) {
                fail("Server have been serving segments while player was playing from the cache.");
            }
        } catch (Exception e) {
            fail(getExceptionFullTraceAndMessage(e));
        }
    }

    private void waitForNSegments(int n) {
        for (int i = 0; i < n; i++) {
            if (!httpServer.waitForNextSegmentToLoad(waitForSegmentToLoad)) {
                fail("HLS playback segment did not start in " + waitForSegmentToLoad + " ms !!!");
            }
        }
    }

    private void waitForSeekToFinish() {
        try {
            lock.lock();
            seekDone.await(3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }
}
