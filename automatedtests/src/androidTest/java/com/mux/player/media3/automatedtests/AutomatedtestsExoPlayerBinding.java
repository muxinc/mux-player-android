package com.mux.player.media3.automatedtests;

import androidx.media3.exoplayer.ExoPlayer;
import com.mux.stats.sdk.muxstats.ExoPlayerBinding;
import com.mux.stats.sdk.muxstats.MuxStateCollector;

class AutomatedtestsExoPlayerBinding extends ExoPlayerBinding {

  TestEventListener testListener;

  public AutomatedtestsExoPlayerBinding(TestEventListener testListener) {
    this.testListener = testListener;
  }

  @Override
  public void bindPlayer(ExoPlayer player, MuxStateCollector collector) {
    super.bindPlayer(player, collector);
    testListener.setCollector(collector);
    player.addAnalyticsListener(testListener);
  }

  @Override
  public void unbindPlayer(ExoPlayer player, MuxStateCollector collector) {
    super.unbindPlayer(player, collector);
    player.removeAnalyticsListener(testListener);
  }
}