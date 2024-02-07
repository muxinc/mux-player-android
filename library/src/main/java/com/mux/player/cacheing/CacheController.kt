package com.mux.player.cacheing

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.util.Log
import com.mux.player.cacheing.CacheController.datastore
import com.mux.player.cacheing.CacheController.setup
import com.mux.player.internal.cache.FileRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

  private val playersWithCache = AtomicInteger(0)
  private val ioScope = CoroutineScope(Dispatchers.IO)
  // access gated via playersWithCache.
  // Only start it if you incremented from 0, only stop if you decrement from 0
  private var proxyServer: ProxyServer? = null

  const val TAG = "CacheController"

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
   * Call internally when a new MuxPlayer is created, if caching was enabled.
   */
  @JvmSynthetic
  internal fun onPlayerCreated() {
    Log.d(TAG, "onPlayerCreated: called")
    val totalPlayersBefore = playersWithCache.getAndIncrement()
    Log.d(TAG, "onPlayerCreated: had $totalPlayersBefore players")
    if (totalPlayersBefore == 0) {
      ioScope.launch { datastore.open() }
//      proxyServer = ProxyServer()
    }
  }

  /**
   * Call internally when a MuxPlayer is released if caching was enabled.
   *
   * Try to call only once per player, even if caller calls release() multiple times
   */
  @JvmSynthetic
  internal fun onPlayerReleased() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      closeDatastoreApiN()
    } else {
      closeDatastoreLegacy()
    }
  }

  @TargetApi(Build.VERSION_CODES.N)
  private fun closeDatastoreApiN() {
    val totalPlayersNow = playersWithCache.updateAndGet { if (it > 0) it - 1 else it }
    Log.d(TAG, "closeDatastoreApiN: now have $totalPlayersNow players")
    if (totalPlayersNow == 0) {
      ioScope.launch { datastore.close() }
    }
  }

  private fun closeDatastoreLegacy() {
    val totalPlayersNow = playersWithCache.decrementAndGet()
    Log.d(TAG, "closeDatastoreLegacy: now have $totalPlayersNow players")
    if (totalPlayersNow == 0) {
      ioScope.launch { datastore.close() }
    }
  }

  /**
   * Tries to read from the cache. If there's a hit, this method will return a [ReadHandle] for
   * reading the file. The [ReadHandle] has methods for reading, and also info about the original
   * resource, like its original URL, response headers, and cache-control directives
   */
  fun tryRead(
    requestUrl: String,
  ): ReadHandle? {
    // todo - check for initialization and throw Something

    val fileRecord = datastore.readRecord(requestUrl)
    Log.d(TAG, "Read file record: $fileRecord")
    // todo readRecord checks for the file?
    return if (fileRecord == null) {
      null
    } else {
      ReadHandle(
        url = requestUrl,
        file = fileRecord,
        directory = datastore.fileCacheDir(),
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
//    playerOutputStream: OutputStream,
  ): WriteHandle {
    // todo - check for initialization and throw or something

    return if (shouldCacheResponse(requestUrl, responseHeaders)) {
      val tempFile = datastore.createTempDownloadFile(URL(requestUrl))

      WriteHandle(
        controller = this,
        tempFile = tempFile,
//        playerOutputStream = playerOutputStream,
        responseHeaders = responseHeaders,
        datastore = datastore,
        url = requestUrl,
      )
    } else {
      // not supposed to cache, so the WriteHandle just writes to the player
      WriteHandle(
        controller = this,
        tempFile = null,
//        playerOutputStream = playerOutputStream,
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
    // basic conditions
    val eTag = responseHeaders.getETag()
    if (eTag.isNullOrEmpty()) {
      return false
    }
    val cacheControlLine = responseHeaders.getCacheControl()
    if (cacheControlLine == null || cacheControlLine.contains(RX_NO_STORE)) {
      return false
    }

    val contentType = responseHeaders.getContentType()
    // for now, only segments
    if (!isContentTypeSegment(contentType)) {
      return false
    }

    // todo - Need to specifically only cache segments. Check content-type first then url

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

  private fun Map<String, List<String>>.getContentType(): String? =
    mapKeys { it.key.lowercase() }["content-type"]?.last()

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
//    private val playerOutputStream: OutputStream,
  ): Closeable {

    private val fileOutputStream = tempFile?.let { BufferedOutputStream(FileOutputStream(it)) }
//    private val fileOutputStream = tempFile?.let { FileOutputStream(it) }

    /**
     * Writes the given bytes to both the player socket and the file
     */
    fun write(data: ByteArray, offset: Int, len: Int) {
//      playerOutputStream.write(data, offset, len)
      Log.i(TAG , "Writing $len bytes unless $fileOutputStream is null")
      fileOutputStream?.write(data, offset, len)
      fileOutputStream?.flush()
    }

    /**
     * Writes the given String's bytes to both the player socket and the file
     */
//    fun write(data: String) {
//      playerOutputStream.write(data.toByteArray(Charsets.US_ASCII))
//      fileOutputStream?.write(data.toByteArray(Charsets.US_ASCII))
//    }

    /**
     * Call when you've reached the end of the body input. This closes the streams to the player
     * socket and file (if any)
     */
    fun finishedWriting() {
//      playerOutputStream.close()

      // If there's a temp file, we are caching it so move it from the temp file and write to index
      Log.i(TAG , "flushing $fileOutputStream")
      fileOutputStream?.flush()
      Log.i(TAG , "closing $fileOutputStream")
      fileOutputStream?.close()
      Log.i(TAG , "temp file is $tempFile")
      Log.i(TAG , "temp file has ${tempFile?.length()}")
      if (tempFile != null && false) {
        val cacheControl = responseHeaders.getCacheControl()
        val etag = responseHeaders.getETag()
        if (cacheControl != null && etag != null) {
          val cacheFile = datastore.moveFromTempFile(tempFile, URL(url))
          Log.d(TAG, "move to cache file with path ${cacheFile.path}")

          val nowUtc = System.currentTimeMillis().let { timeMs ->
            val timezone = TimeZone.getDefault()
            (timeMs + timezone.getOffset(timeMs)) / 1000
          }
          val recordAge = responseHeaders.getAge()?.toLongOrNull()
          val maxAge = parseMaxAge(cacheControl) ?: parseSMaxAge(cacheControl)
          val relativePath = cacheFile.toRelativeString(datastore.fileCacheDir())

          Log.i(TAG, "Saving to cache file $relativePath")
          Log.i(TAG, "We saved ${cacheFile.length()} bytes")
          Log.i(TAG, "but there's ${tempFile.length()} bytes in the temp file")

          val record = FileRecord(
            url = url,
            etag = etag,
            relativePath = relativePath,
            lastAccessUtcSecs = nowUtc,
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
          Log.w(
            "CacheController", "Had temp file but not enough info to cache. " +
                    "cache-control: [$cacheControl] etag $etag"
          )
        }
      }
    }

    override fun close() {
      fileOutputStream?.close()
    }
  }

  /**
   * Object for reading from the Cache. The methods on this object will read bytes from a cache copy
   * of the remote resource.
   *
   * Use [readAllInto] to read the entire file into an OutputStream.
   */
  class ReadHandle(
    val url: String,
    val file: FileRecord,
    directory: File,
  ) : Closeable {

    companion object {
      const val READ_SIZE = 32 * 1024
    }

    private val cacheFile: File
    private val fileInput: InputStream

    init {
      Log.d(TAG, "Reading from cache file at ${file.relativePath}")
      cacheFile = File(datastore.fileCacheDir(), file.relativePath)
      //fileInput = BufferedInputStream(FileInputStream(File(directory, file.relativePath)))
      // todo - oh no were saving absolute paths by mistake
      Log.d(TAG, "Actual file we're reading is $cacheFile")
      fileInput = BufferedInputStream(FileInputStream(cacheFile))
    }

    // todo - needs to be in schema for efficient eviction
    val fileSize: Long get() = cacheFile.length()

    @Throws(IOException::class)
    fun read(into: ByteArray, offset: Int, len: Int): Int {
      return fileInput.read(into, offset, len)
    }

    @Throws(IOException::class)
    fun readAllInto(outputStream: OutputStream) {
      val buf = ByteArray(READ_SIZE)
      while (true) {
        val readBytes = fileInput.read(buf)
        if (readBytes == -1) {
          // done
          break
        } else {
          outputStream.write(buf, 0, readBytes)
        }
      }
    }

    override fun close() {
      runCatching { fileInput.close() }
    }
  }
}
