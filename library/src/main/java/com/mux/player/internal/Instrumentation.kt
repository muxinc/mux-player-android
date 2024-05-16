package com.mux.player.internal

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec

// todo - certainly not a final design
object Instrumentation {

  // todo - for more detailed data we can make a TransferListener & add to data src

  var segmentReqsToUpstream: Int = 0
    private set
  var segmentReqsToCache: Int = 0
    private set

  @OptIn(UnstableApi::class)
  fun recordSegmentRequestToUpstream(dataSpec: DataSpec) {

    segmentReqsToUpstream ++
  }

  @OptIn(UnstableApi::class)
  fun recordSegmentRequestToCache(dataSpec: DataSpec) {
    // only video segments
    segmentReqsToCache ++
  }
}