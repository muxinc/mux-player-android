package com.mux.player.internal

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec

// todo - certainly not a final design
object Instrumentation {

  //todo - report transfer stats when reading cache entries so we can use TransferListener

  private val cacheBytesByUrl: MutableMap<String, Long> = mutableMapOf()
  private val upstreamBytesByUrl: MutableMap<String, Long> = mutableMapOf()

  var segmentMisses: Int = 0
    private set
  var segmentHits: Int = 0
    private set

  fun totalCacheBytes() = cacheBytesByUrl.values.sum()

  fun totalUpstreamBytes() = upstreamBytesByUrl.values.sum()

  @OptIn(UnstableApi::class)
  @Synchronized
  fun recordBytesFromUpstream(dataSpec: DataSpec, sentBytes: Long) {
    val soFar = upstreamBytesByUrl[dataSpec.uri.toString()] ?: 0
    upstreamBytesByUrl[dataSpec.uri.toString()] = soFar + sentBytes
  }

  @OptIn(UnstableApi::class)
  @Synchronized
  fun recordBytesFromCache(dataSpec: DataSpec, sentBytes: Long) {
    val soFar = cacheBytesByUrl[dataSpec.uri.toString()] ?: 0
    cacheBytesByUrl[dataSpec.uri.toString()] = soFar + sentBytes
  }

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
