package com.mux.player.cacheing

import android.icu.util.Output
import com.mux.player.internal.cache.CacheControlRecord
import com.mux.player.internal.cache.FileRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Controls access to Mux Player's cache
 */
internal object CacheController {

  val RX_NO_STORE_NO_CACHE = Regex("""no-store|no-cache""")

  /**
   * group(1) will have the max-age value
   */
  val RX_MAX_AGE = Regex("""max-age=([0-9].*)""")


  /**
   * group(1) will have the s-max-age value
   */
  val RX_S_MAX_AGE = Regex("""s-max-age=([0-9].*)""")

  /**
   * Tries to read from the cache. If there's a hit, this method will return a [ReadHandle] for
   * reading the file. The [ReadHandle] has methods for reading, and also info about the original
   * resource, like its original URL, response headers, and cache-control directives
   */
  fun tryRead(
    requestUrl: String
  ): ReadHandle? {
    return if (false/* todo - check cache */) {
      null
    } else {
      ReadHandle(
        url = requestUrl,
        file = File(""), // todo
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
    // todo - if for some reason we are currently downloading the exact-same same segment on another
    //  thread, there would be conflicts here.. But not sure if that is a real case or theoretical one

    // todo - create a file in the cache dir for the output (maybe name is key + downloaded-at timestamp)

    return if (shouldCache(requestUrl, responseHeaders)) {
      WriteHandle(
        controller = this,
        fileOutputStream = null, // todo - real value
        playerOutputStream = playerOutputStream,
        responseHeaders = responseHeaders,
        url = requestUrl,
      )
    } else {
      // not supposed to cache, so the WriteHandle just writes to the player
      WriteHandle(
        controller = this,
        fileOutputStream = null,
        playerOutputStream = playerOutputStream,
        url = requestUrl,
        responseHeaders = responseHeaders,
      )
    }
  }

  private fun shouldCache(
    requestUrl: String,
    responseHeaders: Map<String, List<String>>
  ): Boolean {
    // todo - additional logic here, like checking disk space against Content-Length etc

    return responseHeaders.getCacheControl()?.matches(RX_NO_STORE_NO_CACHE)?.not() ?: false
  }

  private fun Map<String, List<String>>.getCacheControl(): String? = get("Cache-Control")?.last()

  /**
   * Object for writing to both the player and the cache. Call [downloadStarted] to get one of these
   * for any given web response. Writes to this handle will go to the player and also to the cache
   * if required
   */
  class WriteHandle(
    val url: String,
    val responseHeaders: Map<String, List<String>>,
    private val controller: CacheController,
    private val fileOutputStream: OutputStream?,
    private val playerOutputStream: OutputStream,
    // todo - info about the Response, to be written to the index when appropriate
  ) {

    /**
     * Writes the given bytes to both the player socket and the file
     */
    fun write(data: ByteArray) {
      // todo - write to player OutputStream
      // todo - write to file output stream if it exists
    }

    /**
     * Writes the given String's bytes to both the player socket and the file
     */
    fun write(data: String) {
      // todo : write to the OutputStream
      // todo - write to file OutputStream if it exists
    }

    /**
     * Call when you've reached the end of the body input. This closes the streams to the player
     * socket and file (if any)
     */
    fun finishedWriting() {
      // todo - write to index db, close streams
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
    val file: File,
    // todo - figure out real fields
//    val fileRecord: FileRecord,
//    val cacheControlRecord: CacheControlRecord,
    val fileInput: InputStream,
  )
}
