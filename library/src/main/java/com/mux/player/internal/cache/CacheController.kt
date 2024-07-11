package com.mux.player.internal.cache

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.util.Log
import com.mux.player.internal.cache.CacheController.startWriting
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
    val fileRecord = datastore.readRecordByUrl(requestUrl)
    return if (fileRecord == null) {
      null
    } else {
      ReadHandle.create(
        url = requestUrl,
        fileRecord = fileRecord,
        datastore = datastore,
        directory = datastore.fileCacheDir(),
      )
    }
  }

  /**
   * Call when you are about to download the body of a response. This method returns an object you
   * can use to write your data. When you are done writing, call [WriteHandle.finishedWriting].
   *
   * @see [WriteHandle]
   */
  fun startWriting(
    requestUrl: String,
    responseHeaders: Map<String, List<String>>,
  ): WriteHandle {
    return if (shouldCacheResponse(requestUrl, responseHeaders)) {
      val tempFile = datastore.createTempDownloadFile(URL(requestUrl))

      WriteHandle.create(
        controller = this,
        tempFile = tempFile,
        responseHeaders = responseHeaders,
        datastore = datastore,
        url = requestUrl,
      )
    } else {
      // not supposed to cache, so the WriteHandle just writes to the player
      WriteHandle.create(
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
    val totalPlayersBefore = playersWithCache.getAndIncrement()
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
    if (totalPlayersNow == 0) {
      ioScope.launch { datastore.close() }
    }
  }

  private fun closeDatastoreLegacy() {
    val totalPlayersNow = playersWithCache.decrementAndGet()
    if (totalPlayersNow == 0) {
      ioScope.launch { datastore.close() }
    }
  }

  /**
   * Returns true if the response must be revalidated before use.
   *
   * Requiring revalidation doesn't necessarily mean that the entry needs to be deleted
   */
  @JvmSynthetic
  internal fun revalidateRequired(nowUtc: Long, fileRecord: FileRecord): Boolean {
    // assume 'immutable' if we didn't get 'no-cache' because it saves a round-trip.
    return fileRecord.isStale(nowUtc) || RX_NO_CACHE.containsMatchIn(fileRecord.cacheControl)
  }

  /**
   * Returns true if the response should be cached, based on its URL and the headers of the response
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

    // todo - additional logic here:
    //  * check disk space against Content-Length?

    return true
  }
}

/**
 * Object for reading from the Cache. The methods on this object will read bytes from a cache copy
 * of the remote resource.
 *
 * Obtain an instance via [CacheController.tryRead]
 */
internal class ReadHandle private constructor(
  val url: String,
  val fileRecord: FileRecord,
  datastore: CacheDatastore,
  directory: File,
) : Closeable {

  companion object {
    const val READ_SIZE = 32 * 1024
    private const val TAG = "ReadHandle"

    @JvmSynthetic internal fun create(
      url: String,
      fileRecord: FileRecord,
      datastore: CacheDatastore,
      directory: File,
    ): ReadHandle = ReadHandle(url, fileRecord, datastore, directory)
  }

  private val cacheFile: File
  private val fileInput: InputStream

  init {
    // todo - Are we really doing relative paths here? We want to be
    cacheFile = File(datastore.fileCacheDir(), fileRecord.relativePath)
    fileInput = BufferedInputStream(FileInputStream(cacheFile))
  }

  val fileSize: Long get() = fileRecord.sizeOnDisk

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
 * Object for writing to both the player and the cache. Writes to this handle will go to the player
 * and also to the cache if required
 *
 * Obtain an instance with [CacheController.startWriting]
 */
internal class WriteHandle private constructor(
  val url: String,
  val responseHeaders: Map<String, List<String>>,
  private val controller: CacheController,
  private val datastore: CacheDatastore,
  private val tempFile: File?,
) : Closeable {

  companion object {
    private const val TAG = "WriteHandle"

    @JvmSynthetic internal fun create(
      url: String,
      responseHeaders: Map<String, List<String>>,
      controller: CacheController,
      datastore: CacheDatastore,
      tempFile: File?,
    ): WriteHandle = WriteHandle(url, responseHeaders, controller, datastore, tempFile)
  }

  private val fileOutputStream = tempFile?.let { BufferedOutputStream(FileOutputStream(it)) }
  private var writtenBytes = 0

  /**
   * Writes the given bytes to both the player socket and the file
   */
  fun write(data: ByteArray, offset: Int, len: Int) {
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
    fileOutputStream?.flush()
    fileOutputStream?.close()

    if (tempFile != null) {
      val cacheControl = responseHeaders.getCacheControl()
      val eTag = responseHeaders.getETag()
      if (cacheControl != null && eTag != null) {
        val cacheFile = datastore.moveFromTempFile(tempFile, URL(url))

        val nowUtc = nowUtc()
        val recordAge = responseHeaders.getAge()?.toLongOrNull()
        val maxAge = parseMaxAge(cacheControl) ?: parseSMaxAge(cacheControl)
        val relativePath = cacheFile.toRelativeString(datastore.fileCacheDir())

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

        val result = datastore.writeFileRecord(record)

        // Just evict synchronously on-write. If this is a burden we can do it async (or less often)
        if (result.isSuccess) {
          datastore.evictByLru()
        }
      }
    }
  }

  override fun close() {
    fileOutputStream?.close()
  }
}
