package com.mux.player.media3.automatedtests.mockup.http;

public interface ConnectionListener {

  void segmentServed(String requestUuid, SegmentStatistics segmentStat);

}
