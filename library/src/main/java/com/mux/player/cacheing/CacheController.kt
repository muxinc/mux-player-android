package com.mux.player.cacheing

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.mux.player.internal.cache.FileRecord
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Controls access to Mux Player's cache
 *
 * To use this object, you must first call [setup]. If you aren't writing a test, you can pass
 * `null` for the second parameter
 */
@SuppressLint("StaticFieldLeak")
internal object CacheController {

  private lateinit var appContext: Context
  private lateinit var datastore: CacheDatastore

  val RX_NO_STORE = Regex("""no-store""")
  val RX_NO_CACHE = Regex("""no-cache""")
  val RX_MAX_AGE = Regex("""max-age=([0-9].*)""")
  val RX_S_MAX_AGE = Regex("""s-max-age=([0-9].*)""")

  /**
   * Call from the constructor of Mux Player. This must be called internally before any playing
   * starts, assuming that disk caching is enabled
   *
   * @param context A context. The Application context will be extracted from it for further use
   * @param cacheDatastore Optional. If not provided, the default `CacheDatastore` will be used
   */
  @JvmSynthetic
  internal fun setup(context: Context, cacheDatastore: CacheDatastore?) {
    if (!this::appContext.isInitialized) {
      this.appContext = context.applicationContext
    }
    if (!this::datastore.isInitialized) {
      datastore = cacheDatastore ?: CacheDatastore(appContext)
    }
  }

  /**
   * Tries to read from the cache. If there's a hit, this method will return a [ReadHandle] for
   * reading the file. The [ReadHandle] has methods for reading, and also info about the original
   * resource, like its original URL, response headers, and cache-control directives
   */
  fun tryRead(
    requestUrl: String
  ): ReadHandle? {
    // todo - check for initialization and throw Something

    val fileRecord = datastore.readRecord(requestUrl)
    return if (fileRecord == null || !fileRecord.file.exists()) {
      null
    } else {
      ReadHandle(
        url = requestUrl,
        file = fileRecord,
        fileInput = ByteArrayInputStream(ByteArray(5))
      )
    }
  }

  /**
   * Call when you are about to download the body of a response. This method returns an object you
   * can use to write your data. See [WriteHandle] for more information
   */
  fun downloadStarted(
    requestUrl: String,
    responseHeaders: Map<String, List<String>>,
    playerOutputStream: OutputStream,
  ): WriteHandle {
    // todo - check for initialization and throw or something

    return if (shouldCacheResponse(requestUrl, responseHeaders)) {
      val tempFile = datastore.createTempDownloadFile(URL(requestUrl))

      WriteHandle(
        controller = this,
        tempFile = tempFile,
        playerOutputStream = playerOutputStream,
        responseHeaders = responseHeaders,
        datastore = datastore,
        url = requestUrl,
      )
    } else {
      // not supposed to cache, so the WriteHandle just writes to the player
      WriteHandle(
        controller = this,
        tempFile = null,
        playerOutputStream = playerOutputStream,
        url = requestUrl,
        datastore = datastore,
        responseHeaders = responseHeaders,
      )
    }
  }

  /**
   * Returns true if the request should be cached, based on its URL and the headers of the response
   */
  @JvmSynthetic
  internal fun shouldCacheResponse(
    requestUrl: String,
    responseHeaders: Map<String, List<String>>
  ): Boolean {
    val cacheControlLine = responseHeaders.getCacheControl()

    if (cacheControlLine == null) {
      return false
    }
    if (cacheControlLine.contains(RX_NO_STORE)) {
      return false
    }
    // todo - additional logic here:
    //  * check disk space against Content-Length?
    //  * check for headers like Age?
    //  * make sure the entry is not already expired by like a second or whatever (edge case)

    return true
  }

  private fun Map<String, List<String>>.getCacheControl(): String? =
    mapKeys { it.key.lowercase() }["cache-control"]?.last()
  private fun Map<String, List<String>>.getETag(): String? =
    mapKeys { it.key.lowercase() }["etag"]?.last()
  private fun Map<String, List<String>>.getAge(): String? =
    mapKeys { it.key.lowercase() }["age"]?.last()

  private fun parseSMaxAge(cacheControl: String): Long? {
    val matchResult = RX_S_MAX_AGE.matchEntire(cacheControl)
    return if (matchResult == null) {
      null
    } else {
      val maxAgeSecs = matchResult.groupValues[1]
      maxAgeSecs.toLongOrNull()
    }
  }

  private fun parseMaxAge(cacheControl: String): Long? {
    val matchResult = RX_MAX_AGE.matchEntire(cacheControl)
    return if (matchResult == null) {
      null
    } else {
      val maxAgeSecs = matchResult.groupValues[1]
      maxAgeSecs.toLongOrNull()
    }
  }

  /**
   * Object for writing to both the player and the cache. Call [downloadStarted] to get one of these
   * for any given web response. Writes to this handle will go to the player and also to the cache
   * if required
   */
  class WriteHandle(
    val url: String,
    val responseHeaders: Map<String, List<String>>,
    private val controller: CacheController,
    private val datastore: CacheDatastore,
    private val tempFile: File?,
    private val playerOutputStream: OutputStream,
  ) {

    private val fileOutputStream = tempFile?.let { BufferedOutputStream(FileOutputStream(it)) }

    /**
     * Writes the given bytes to both the player socket and the file
     */
    fun write(data: ByteArray, offset: Int, len: Int) {
      playerOutputStream.write(data, offset, len)
      fileOutputStream?.write(data, offset, len)
    }

    /**
     * Writes the given String's bytes to both the player socket and the file
     */
    fun write(data: String) {
      playerOutputStream.write(data.toByteArray(Charsets.US_ASCII))
      fileOutputStream?.write(data.toByteArray(Charsets.US_ASCII))
    }

    // todo - method to call if the proxy encounters an error, closes & deletes temp file

    /**
     * Call when you've reached the end of the body input. This closes the streams to the player
     * socket and file (if any)
     */
    fun finishedWriting() {
      playerOutputStream.close()

      // If there's a temp file, we are caching it so move it from the temp file and write to index
      fileOutputStream?.close()
      if (tempFile != null) {
        val cacheControl = responseHeaders.getCacheControl()
        val etag = responseHeaders.getETag()
        if (cacheControl != null && etag != null) {
          val cacheFile = datastore.moveFromTempFile(tempFile, URL(url))
          val nowUtc = System.currentTimeMillis().let { timeMs ->
            val timezone = TimeZone.getDefault()
            (timeMs + timezone.getOffset(timeMs)) / 1000
          }
          val recordAge = responseHeaders.getAge()?.toLongOrNull()
          val maxAge = parseMaxAge(cacheControl) ?: parseSMaxAge(cacheControl)

          val record = FileRecord(
            url = url,
            etag = etag,
            file = cacheFile,
            lookupKey = datastore.safeCacheKey(URL(url)),
            downloadedAtUtcSecs = nowUtc,
            cacheMaxAge = maxAge ?: TimeUnit.SECONDS.convert(7, TimeUnit.DAYS),
            resourceAge = recordAge ?: 0L,
            cacheControl = cacheControl,
          )

          val result = datastore.writeRecord(record)

          // todo - return a fail or throw somerthing
        } else {
          // todo: need a logger
          Log.w("CacheController", "Had temp file but not enough info to cache. " +
                  "cache-control: [$cacheControl] etag $etag")
        }

        //datastore.writeRecord()
      }
    }
  }

  /**
   * Object for reading from the Cache. The methods on this object will read bytes from a cache copy
   * of the remote resource.
   *
   * Use [read] or [readAll] to read out of the cache
   */
  class ReadHandle(
    val url: String,
    val file: FileRecord,
    // todo - figure out real fields
//    val fileRecord: FileRecord,
//    val cacheControlRecord: CacheControlRecord,
    val fileInput: InputStream,
  )
}
