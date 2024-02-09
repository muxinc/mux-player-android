package com.mux.player.internal.cache

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.util.Log
import com.mux.player.internal.cache.CacheController.downloadStarted
import com.mux.player.internal.cache.CacheController.setup
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

  private const val TAG = "CacheController"
  private lateinit var appContext: Context
  private lateinit var datastore: CacheDatastore

  private val playersWithCache = AtomicInteger(0)
  private val ioScope = CoroutineScope(Dispatchers.IO)

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
      appContext = context.applicationContext
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
    requestUrl: String,
  ): ReadHandle? {
    // todo - check for initialization and throw Something

    val fileRecord = datastore.readRecordByUrl(requestUrl)
    Log.d(TAG, "Read file record: $fileRecord")
    // todo readRecord checks for the file?
    return if (fileRecord == null) {
      null
    } else {
      ReadHandle(
        url = requestUrl,
        file = fileRecord,
        datastore = datastore,
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
  ): WriteHandle {
    return if (shouldCacheResponse(requestUrl, responseHeaders)) {
      val tempFile = datastore.createTempDownloadFile(URL(requestUrl))

      WriteHandle(
        controller = this,
        tempFile = tempFile,
        responseHeaders = responseHeaders,
        datastore = datastore,
        url = requestUrl,
      )
    } else {
      // not supposed to cache, so the WriteHandle just writes to the player
      WriteHandle(
        controller = this,
        tempFile = null,
        url = requestUrl,
        datastore = datastore,
        responseHeaders = responseHeaders,
      )
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
}

/**
 * Object for reading from the Cache. The methods on this object will read bytes from a cache copy
 * of the remote resource.
 *
 * Use [readAllInto] to read the entire file into an OutputStream.
 */
internal class ReadHandle internal constructor(
  val url: String,
  val file: FileRecord,
  datastore: CacheDatastore,
  directory: File,
) : Closeable {

  companion object {
    const val READ_SIZE = 32 * 1024
    private const val TAG = "ReadHandle"
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

/**
 * Object for writing to both the player and the cache. Call [downloadStarted] to get one of these
 * for any given web response. Writes to this handle will go to the player and also to the cache
 * if required
 */
internal class WriteHandle internal constructor(
  val url: String,
  val responseHeaders: Map<String, List<String>>,
  private val controller: CacheController,
  private val datastore: CacheDatastore,
  private val tempFile: File?,
): Closeable {

  companion object {
    private const val TAG = "WriteHandle"
  }

  private val fileOutputStream = tempFile?.let { BufferedOutputStream(FileOutputStream(it)) }
  private var writtenBytes = 0

  /**
   * Writes the given bytes to both the player socket and the file
   */
  fun write(data: ByteArray, offset: Int, len: Int) {
    Log.i(TAG, "Writing $len bytes unless $fileOutputStream is null")
    fileOutputStream?.write(data, offset, len)
    fileOutputStream?.flush()
    writtenBytes += len
  }

  /**
   * Call when you've reached the end of the body input. This closes the streams to the player
   * socket and file (if any)
   */
  fun finishedWriting() {
    // If there's a temp file, we are caching it so move it from the temp file and write to index
    Log.i(TAG, "flushing $fileOutputStream")
    fileOutputStream?.flush()
    Log.i(TAG, "closing $fileOutputStream")
    fileOutputStream?.close()
    Log.i(TAG, "temp file is $tempFile")
    Log.i(TAG, "temp file has ${tempFile?.length()}")
    if (tempFile != null) {
      val cacheControl = responseHeaders.getCacheControl()
      val eTag = responseHeaders.getETag()
      if (cacheControl != null && eTag != null) {
        val cacheFile = datastore.moveFromTempFile(tempFile, URL(url))
        Log.d(TAG, "move to cache file with path ${cacheFile.path}")

        val nowUtc = nowUtc()
        val recordAge = responseHeaders.getAge()?.toLongOrNull()
        val maxAge = parseMaxAge(cacheControl) ?: parseSMaxAge(cacheControl)
        val relativePath = cacheFile.toRelativeString(datastore.fileCacheDir())

        Log.i(TAG, "Saving ${cacheFile.length()} to cache as: $relativePath")

        val record = FileRecord(
          url = url,
          etag = eTag,
          relativePath = relativePath,
          lastAccessUtcSecs = nowUtc,
          lookupKey = datastore.safeCacheKey(URL(url)),
          downloadedAtUtcSecs = nowUtc,
          cacheMaxAge = maxAge ?: TimeUnit.SECONDS.convert(7, TimeUnit.DAYS),
          resourceAge = recordAge ?: 0L,
          cacheControl = cacheControl,
          sizeOnDisk = writtenBytes.toLong(),
        )

        val result = datastore.writeRecord(record)

        // todo - return a fail or throw somerthing
      } else {
        // todo: need a logger
      }
    }
  }

  override fun close() {
    fileOutputStream?.close()
  }
}
