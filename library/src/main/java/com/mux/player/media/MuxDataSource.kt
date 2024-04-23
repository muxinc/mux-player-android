package com.mux.player.media

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import com.google.common.net.HttpHeaders
import com.mux.player.internal.cache.CacheController
import com.mux.player.internal.cache.ReadHandle
import com.mux.player.internal.cache.WriteHandle
import com.mux.player.internal.cache.nowUtc
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

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

  private var dataSpec: DataSpec? = null

  private var respondingFromCache: Boolean = false
  private var upstream: HttpDataSource? = null // only present if we need to request something
  private var cacheReader: ReadHandle? = null
  private var cacheWriter: WriteHandle? = null

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    return if (respondingFromCache) {
      // !! safe by contract
      val readBytes = cacheReader!!.read(buffer, offset, length)

      readBytes
    } else {
      val writer = cacheWriter!!
      val upstreamSrc = this.upstream!!
      val bytesFromUpstream = upstreamSrc.read(buffer, offset, length)

      if (bytesFromUpstream > 0) {
        writer.write(buffer, offset, bytesFromUpstream)
      } else if (bytesFromUpstream == C.RESULT_END_OF_INPUT) {
        writer.finishedWriting()
      }

      bytesFromUpstream
    }
  }

  override fun open(dataSpec: DataSpec): Long {
    this.dataSpec = dataSpec;

    Log.i(TAG, "open(): Opening URI ${dataSpec.uri}")
    val readHandle = CacheController.tryRead(dataSpec.uri.toString())
    val nowUtc = nowUtc()

    return if (readHandle == null) {
      // cache miss
      Log.d(TAG, "cache MISS on url ${dataSpec.uri}")
      openAndInitFromRemote(dataSpec, upstreamSrcFac)
    } else if (CacheController.revalidateRequired(nowUtc, readHandle.fileRecord)) {
      // need to revalidate
      Log.d(TAG, "cache VALIDATING url ${dataSpec.uri}")
      openAndInitRevalidating(dataSpec, readHandle)
    } else {
      // cache hit
      Log.d(TAG, "cache HIT on url ${dataSpec.uri}")
      openAndInitFromCache(readHandle)
    }
  }

  override fun getUri(): Uri? = dataSpec?.uri

  override fun close() {
    cacheReader?.close()
    cacheWriter?.close()
    upstream?.close()
  }

  private fun openAndInitRevalidating(dataSpec: DataSpec, readHandle: ReadHandle): Long {
    val revalidateRequestHeaders = mutableMapOf<String, String>()
    revalidateRequestHeaders.putAll(dataSpec.httpRequestHeaders)
    revalidateRequestHeaders["If-None-Match"] = readHandle.fileRecord.etag

    val revalidateSpec = dataSpec.buildUpon()
      .setHttpMethod(DataSpec.HTTP_METHOD_GET) // deviate from spec to save the round-trip w/cdn
      .setHttpRequestHeaders(revalidateRequestHeaders)
      .build()

    val upstreamBytes = openAndInitFromRemote(revalidateSpec, RevalidatingDataSource.Factory())
    val upstream = this.upstream!! // set by initAndOpenUpstream

    Log.d(TAG, "revalidation: HTTP ${upstream.responseCode}. $upstreamBytes available")
    return if (upstream.responseCode != 304) {
      // Entry wasn't valid anymore, but we did a GET so the body's ready to read and we're done
      Log.d(TAG, "revalidation: Entry was NOT valid, getting from network")

      // todo - we *could* delete the row here, but consider that stale items can be used if
      //  state-while-revalidate or stale-while-error or if we're disconnected (unless must-revalidate)..
      //  For now, we saved time by assuming state-while-error and *not* must-revalidate & keep it

      upstreamBytes
    } else {
      Log.d(TAG, "revalidation: Entry WAS still valid, getting from cache")
      // Entry was still valid, so read from cache instead
      upstream.close()
      this.upstream = null

      openAndInitFromCache(readHandle)
    }
  }

  private fun openAndInitFromRemote(dataSpec: DataSpec, fac: HttpDataSource.Factory): Long {
    respondingFromCache = false
    val upstream = fac.createDataSource()

    this.upstream = upstream
    val available = upstream.open(dataSpec)
    cacheWriter = CacheController.downloadStarted(
      dataSpec.uri.toString(),
      upstream.responseHeaders,
    )
    return available
  }

  private fun openAndInitFromCache(readHandle: ReadHandle): Long {
    respondingFromCache = true
    this.cacheReader = readHandle
    return readHandle.fileSize
  }
}

/**
 * HttpDataSource used for revalidating stale segments/chunks.
 *
 * This is required because the DefaultHttpDataSource doesn't support 304 responses
 * It's ok for this class to be simple because the main special cases covered by the
 * DefaultHttpDataSource are not relevant for this version of the cache. Chunks aren't gzipped,
 * never requested in parts, and mux video doesn't redirect segment requests so we can just
 * straightforwardly read the bytes
 */
@OptIn(UnstableApi::class)
private class RevalidatingDataSource : BaseDataSource(true), HttpDataSource {

  class Factory: HttpDataSource.Factory {
    override fun createDataSource(): HttpDataSource {
      return RevalidatingDataSource()
    }
    override fun setDefaultRequestProperties(defaultRequestProperties: MutableMap<String, String>): HttpDataSource.Factory {
      // not used
      return this
    }
  }

  private var httpConnection: HttpURLConnection? = null
  private var bodyInputSteam: InputStream? = null
  private var responseCode: Int? = null
  private var responseMessage: String? = null

  private var open: Boolean = false
  private var dataSpec: DataSpec? = null
  private val requestHeaders = mutableMapOf<String, String>()

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    val readBytes = bodyInputSteam?.read(buffer, offset, length) ?: 0

    return if (readBytes == -1) {
      C.RESULT_END_OF_INPUT
    } else {
      bytesTransferred(readBytes)
      readBytes
    }
  }

  override fun open(dataSpec: DataSpec): Long {
    val conn = try {
      val hurlConn = createConnection(dataSpec, requestHeaders)
      this.responseCode = hurlConn.responseCode
      this.responseMessage = hurlConn.responseMessage
      hurlConn
    } catch (e: IOException) {
      closeConnection()
      throw HttpDataSourceException.createForIOException(
        e, dataSpec,
        HttpDataSourceException.TYPE_OPEN
      )
    }

    val code = conn.responseCode
    val msg = conn.responseMessage
    if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
      // not-modified, not an error, we don't have to download the body again
      runCatching { conn.disconnect() }
      return 0
    } else if (code < 200 || code > 299) {
      // some kind of error
      val errorBody = conn.errorStream.use { errorBody -> errorBody.readBytes() }
      throw InvalidResponseCodeException(
        code,
        responseMessage,
        null,
        conn.headerFields,
        dataSpec,
        errorBody
      )
    } else {
      // have data to read
      val bodyStream = try {
        val stream = conn.inputStream
        transferStarted(dataSpec)
        stream
      } catch (e: IOException) {
        closeConnection()
        throw HttpDataSourceException(
          e,
          dataSpec,
          PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
          HttpDataSourceException.TYPE_OPEN
        )
      }

      this.bodyInputSteam = bodyStream
      this.open = true
      //return 0 // todo = bodyInputStream.available()? returning 0 all the time is technically ok tho
      return bodyStream.available().toLong()
    }
  }

  override fun getUri(): Uri? {
    return dataSpec?.uri
  }

  override fun getResponseHeaders(): MutableMap<String, MutableList<String>> {
    return httpConnection?.headerFields ?: mutableMapOf()
  }

  override fun close() {
    runCatching { bodyInputSteam?.close() }
    closeConnection()
    if (open) {
      transferEnded()
      open = false
    }
  }

  override fun setRequestProperty(name: String, value: String) {
    requestHeaders[name] = value
  }

  override fun clearRequestProperty(name: String) {
    requestHeaders -= name
  }

  override fun clearAllRequestProperties() {
    requestHeaders.clear()
  }

  override fun getResponseCode(): Int {
    return responseCode ?: -1
  }

  private fun closeConnection() {
    httpConnection?.let { runCatching { it.disconnect() } }
  }

  private fun createConnection(dataSpec: DataSpec, requestHeaders: Map<String, String>): HttpURLConnection {
    val url = URL(dataSpec.uri.toString())
    val conn = url.openConnection() as HttpURLConnection
    conn.connectTimeout = DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS
    conn.readTimeout = DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS

    requestHeaders.forEach { conn.setRequestProperty(it.key, it.value) }
    // todo - user agent str
    dataSpec.httpRequestHeaders.entries.forEach { conn.setRequestProperty(it.key, it.value) }
    conn.setRequestProperty(HttpHeaders.ACCEPT_ENCODING, "identity")

    conn.requestMethod = DataSpec.getStringForHttpMethod(DataSpec.HTTP_METHOD_GET)
    conn.doOutput = false

    conn.connect()
    return conn
  }
}
