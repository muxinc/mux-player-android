package com.mux.player.media3.automatedtests;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import com.mux.stats.sdk.core.util.MuxLogger;
import com.mux.stats.sdk.muxstats.MuxPlayerState;
import com.mux.stats.sdk.muxstats.MuxStateCollector;

public class TestEventListener implements AnalyticsListener {

  static final String TAG = "TestEventListener";

  SimplePlayerTestActivity parent;
  MuxStateCollector collector;

  TestEventListener(SimplePlayerTestActivity parent) {
    this.parent = parent;
  }

  public void setCollector(MuxStateCollector collector) {
    this.collector = collector;
  }

  @Override
  public void onPlaybackStateChanged(EventTime eventTime, @Player.State int playbackState) {
    // TODO how to get this info
    boolean playWhenReady = parent.player.getPlayWhenReady();
    MuxPlayerState muxPlayerState = collector.getMuxPlayerState();
    if (muxPlayerState == MuxPlayerState.PLAYING_ADS) {
      // Normal playback events are ignored during ad playback.
      return;
    }

    switch (playbackState) {
      case Player.STATE_BUFFERING: {
        parent.signalPlaybackBuffering();
      }

      case Player.STATE_READY: {
        // We're done seeking after we get back to STATE_READY
        if (muxPlayerState == MuxPlayerState.SEEKING) {
//          seeked()
        }

        // If playWhenReady && READY, we're playing or else we're paused
        if (playWhenReady) {
          parent.signalPlaybackStarted();
        } else if (muxPlayerState != MuxPlayerState.PAUSED) {
          parent.signalPlaybackStopped();
        }
      }

      case Player.STATE_ENDED: {
        parent.signalPlaybackEnded();
      }
      case Player.STATE_IDLE: {
        if (muxPlayerState == MuxPlayerState.PLAY || muxPlayerState == MuxPlayerState.PLAYING) {
          parent.signalPlaybackStopped();
        }
      }
    }
  }

  public void onPlayerError(EventTime eventTime, PlaybackException error) {
    MuxLogger.w(TAG, "Error" + error.getMessage());
  }

  public void onPlayWhenReadyChanged(
      EventTime eventTime, boolean playWhenReady, @Player.PlayWhenReadyChangeReason int reason) {
    int playbackState = parent.player.getPlaybackState();
    switch (playbackState) {
      case Player.STATE_BUFFERING:
        parent.signalPlaybackBuffering();
        break;
      case Player.STATE_ENDED:
        parent.signalPlaybackEnded();
        break;
      case Player.STATE_READY:
        // By the time we get here, it depends on playWhenReady to know if we're playing
        if (playWhenReady) {
          parent.signalPlaybackStarted();
        } else {
          parent.signalPlaybackStopped();
        }
      case Player.STATE_IDLE:
        parent.signalPlaybackStopped();
        break;
    }
  }

  @Override
  public void onRepeatModeChanged(EventTime eventTime, @Player.RepeatMode int repeatMode) {
    parent.activityLock.lock();
    parent.activityInitialized.signalAll();
    parent.activityLock.unlock();
  }
}
