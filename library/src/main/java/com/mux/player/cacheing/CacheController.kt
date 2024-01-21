package com.mux.player.cacheing

import android.icu.util.Output
import com.mux.player.cacheing.CacheUtil.getCacheControl
import com.mux.player.internal.cache.CacheControlRecord
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Controls access to Mux Player's cache
 */
internal object CacheController {

  val RX_NO_STORE_NO_CACHE = Regex("""no-store|no-cache""")

  /**
   * group(1) will be the value
   */
  val RX_MAX_AGE = Regex("""max-age=([0-9].*)""")

  fun downloadStarted(
    requestUrl: String,
    responseHeaders: HashMap<String, List<String>>,
    remoteInputStream: InputStream,
    playerOutputStream: OutputStream,
  ): WriteHandle {
    return if (shouldCache(requestUrl, responseHeaders)) {
      WriteHandle(
        controller = this,
        fileOutputStream = null, // todo - real value
        playerOutputStream = playerOutputStream
      )
    } else {
      WriteHandle(
        controller = this,
        fileOutputStream = null, // todo - real value
        playerOutputStream = playerOutputStream
      )
    }
  }

  private fun shouldCache(
    requestUrl: String,
    responseHeaders: HashMap<String, List<String>>
  ): Boolean {
    // todo - additional logic here, like checking disk space against Content-Length etc

    return responseHeaders.getCacheControl()?.matches(RX_NO_STORE_NO_CACHE)?.not() ?: false
  }

  class WriteHandle(
    private val controller: CacheController,
    private val fileOutputStream: OutputStream?,
    private val playerOutputStream: OutputStream
    // todo - info about the Repsonse
  ) {

    fun write(data: ByteArray) {
      // todo - write to player OutputStream
      // todo - write to file output stream if it exists
    }

    fun write(data: String) {
      // todo : write to the OutputStream
      // todo - write to file OutputStream if it exists
    }

    fun finishedWriting() {
      // todo - write to index db
    }
  }
}

internal object CacheUtil {
  fun HashMap<String, List<String>>.getCacheControl(): String? = get("Cache-Control")?.last()
}