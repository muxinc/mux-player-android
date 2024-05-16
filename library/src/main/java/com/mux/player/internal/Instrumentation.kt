package com.mux.player.internal

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec

// todo - certainly not a final design
object Instrumentation {

  // todo - for more detailed data we can make a TransferListener & add to data src

  var segmentMisses: Int = 0
    private set
  var segmentHits: Int = 0
    private set

  @OptIn(UnstableApi::class)
  fun recordSegmentCacheMiss(dataSpec: DataSpec) {
    Log.d(TAG, "cache miss: $dataSpec")
    segmentMisses ++
  }

  @OptIn(UnstableApi::class)
  fun recordSegmentCacheHit(dataSpec: DataSpec) {
    Log.d(TAG, "cache hit: $dataSpec")
    // only video segments
    segmentHits ++
  }

  const val TAG = "Instrumentation"
}