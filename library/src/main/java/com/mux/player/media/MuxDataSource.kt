package com.mux.player.media

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import com.mux.player.internal.cache.CacheController
import com.mux.player.internal.cache.ReadHandle
import com.mux.player.internal.cache.WriteHandle

@OptIn(UnstableApi::class)
class MuxDataSource private constructor(
 val upstreamSrcFac: HttpDataSource.Factory,
) : BaseDataSource(false) {

  /**
   * Creates a new instance of [MuxDataSource]. The upstream data source will be invoked for any
   * data that Mux's cache cannot provide
   */
  class Factory(
    private val upstream: HttpDataSource.Factory = DefaultHttpDataSource.Factory()
  ) : DataSource.Factory {
    override fun createDataSource(): DataSource {
      return MuxDataSource(upstream)
    }
  }

  companion object {
    const val TAG = "MuxDataSource"
  }

//  private var originalUri: Uri? = null
  private var dataSpec: DataSpec? = null

  private var respondingFromCache: Boolean = false
  private var upstream: HttpDataSource? = null // only present if we need to request something
  private var cacheReader: ReadHandle? = null
  private var cacheWriter: WriteHandle? = null

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    return if (respondingFromCache) {
      // !! safe by contract
      val readBytes = cacheReader!!.read(buffer, offset, length)
      Log.d(TAG, "Read $readBytes from the cache")

      readBytes
    } else {
      val writer = cacheWriter!!
      val upstreamSrc = this.upstream!!
      val bytesFromUpstream = upstreamSrc.read(buffer, offset, length)
      Log.d(TAG, "Got $bytesFromUpstream from upstream")

      if (bytesFromUpstream > 0) {
        writer.write(buffer, offset, bytesFromUpstream)
      } else if (bytesFromUpstream == C.RESULT_END_OF_INPUT) {
        writer.finishedWriting()
      }

      return bytesFromUpstream
    }
  }

  override fun open(dataSpec: DataSpec): Long {
    Log.i(TAG, "open(): Opening URI ${dataSpec.uri}")
    Log.i(TAG, "open(): with Request Headers ${dataSpec.httpRequestHeaders}")
    this.dataSpec = dataSpec
    val readHandle = CacheController.tryRead(dataSpec.uri.toString())

    return if (readHandle == null) {
      // cache miss
      respondingFromCache = false
      val upstream = upstreamSrcFac.createDataSource()
      this.upstream = upstream
      val available = upstream.open(dataSpec)
      cacheWriter = CacheController.downloadStarted(
        dataSpec.uri.toString(),
        upstream.responseHeaders,
      )
      available
    } else {
      // cache hit
      respondingFromCache = true
      this.cacheReader = readHandle
      Log.d(TAG, "open(): Opening from cache. Advertising ${readHandle.fileSize} bytes")
      readHandle.fileSize
    }
  }

  override fun getUri(): Uri? = dataSpec?.uri

  override fun close() {
    cacheReader?.close()
    cacheWriter?.close()
    upstream?.close()
  }
}
