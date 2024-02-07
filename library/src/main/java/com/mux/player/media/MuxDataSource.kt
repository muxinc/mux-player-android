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
import androidx.media3.datasource.TransferListener
import com.mux.player.cacheing.CacheConstants
import com.mux.player.cacheing.CacheController
import java.io.File

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
  private var cacheReader: CacheController.ReadHandle? = null
  private var cacheWriter: CacheController.WriteHandle? = null

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    return if (respondingFromCache) {
      // !! safe by contract
      val readBytes = cacheReader!!.read(buffer, offset, length)
      Log.d(TAG, "Read $readBytes from the cache")

      readBytes
    } else {
      val writer = if (cacheWriter != null) {
        cacheWriter!!
      } else {
        CacheController.downloadStarted(
          uri.toString(),
          upstream!!.responseHeaders, // !! safe by contract
        )
      }
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
      upstream.open(dataSpec)
    } else {
      respondingFromCache = true
      this.cacheReader = readHandle
      Log.d(TAG, "open(): Opening from cache. Advertising ${readHandle.fileSize} bytes")
      readHandle.fileSize
    }

    // OLD IMPL BELOW
    // todo - hey, do we need the app to enable plaintext http for the proxy to work?
    //  maybe some companies already would for ads or something, but some people won't want to
//    val proxyUri = dataSpec.uri.run {
//      val replaceScheme = if (scheme.equals("https")) "1~" else "0~"
//      val replacePath = "$replaceScheme${host}${path}"
//
//      buildUpon()
//        .encodedAuthority("localhost:${CacheConstants.PROXY_PORT}")
//        .scheme("http")
//        .path(replacePath)
//        .build()
//    }
//
//    this.originalUri = dataSpec.uri
//    Log.d(TAG, "Modified URL\n\tFrom: ${dataSpec.uri}\n\tTo: $proxyUri")
//
//    return upstreamSrc.open(dataSpec.withUri(proxyUri))
  }

  override fun getUri(): Uri? = dataSpec?.uri

  override fun close() {
    cacheReader?.close()
    cacheWriter?.close()
    upstream?.close()
  }
}
