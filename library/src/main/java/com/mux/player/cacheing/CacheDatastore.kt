package com.mux.player.cacheing

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.util.Base64
import android.util.Log
import com.mux.player.internal.cache.FileRecord
import com.mux.player.oneOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URL
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference

internal class CacheDatastore(val context: Context) {

  private val RX_CHUNK_URL =
    Regex("""^https://[^/]*/v1/chunk/([^/]*)/([^/]*)\.(m4s|ts)""")

  private val openTask: AtomicReference<FutureTask<DbHelper>> = AtomicReference(null)
  private val dbHelper: DbHelper get() = awaitDbHelper()

  /**
   * Opens the datastore, blocking until it is ready to use
   *
   * If you use the cache before opening then it will open itself. But opening after a crash or
   * something may take longer due to internal bookkeeping stuff, so this method is exposed for now
   *
   * Internally, this method will ensure that the cache directories and index database exist
   * on-disk, and the index db is updated. It will also clean-up orphaned files and do an eviction
   * pass and whatever other cleanup tasks are required.
   *
   * This method is safe to call multiple times. Subsequent calls will await the same task,
   * or do nothing if the db is already open.
   */
  fun open(): Result<Unit> {
    return try {
      awaitDbHelper()
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  fun createDownloadFile(): OutputStream {
    // todo - Create a temp file for downloading
    return ByteArrayOutputStream(1)
  }

  fun finalizeDownload() {

  }

  fun writeRecord(fileRecord: FileRecord): Result<Unit> {
    // todo - write record. If a record exists with same key then replace with this record
    return Result.success(Unit)
  }

  fun readRecord(url: String): FileRecord? {
    // todo - try to read a record for the given URL, returning it if there's a hit
    return null
  }

  /**
   * Mux Video segments have special cache keys because their URLs follow a known format even
   * across CDNs.
   *
   * For segments specifically from Mux Video, this key will be generated in such a way that
   * requests for the same segment from one CDN can hit cached entries for the same segment from a
   * different CDN
   *
   * Unless you are writing a test, use [safeCacheKey], which encodes the output of this
   */
  @JvmSynthetic
  internal fun generateCacheKey(
    requestUrl: URL,
  ): String {
    // todo - should be on the Datastore
    val urlStr = requestUrl.toString()
    val matchResult = RX_CHUNK_URL.find(urlStr)

    val key = if (matchResult == null) {
      urlStr
    } else {
      val extension = matchResult.groups[3]!!.value
      val isSegment = extension.oneOf(CacheConstants.EXT_TS, CacheConstants.EXT_M4S)

      if (isSegment) {
        // todo - we can pull out more-specific key parts using groups 2 and 1 if we want, but the
        //  paths in the urls are standardized in mux video so this implementation just relies on
        //  that for simplicity
        requestUrl.path
      } else {
        urlStr
      }
    }

    return key
  }

  /**
   * Generates a URL-safe cache key for a given URL. Delegates to [generateCacheKey] but encodes it
   * into something else
   */
  @JvmSynthetic
  internal fun safeCacheKey(url: URL): String = Base64.encodeToString(
    generateCacheKey(url).toByteArray(Charsets.UTF_8),
    Base64.URL_SAFE
  )

  private fun ensureDirs() {
    fileTempDir().mkdirs()
    fileCacheDir().mkdirs()
    indexDbDir().mkdirs()
  }

  private fun fileTempDir(): File = File(context.cacheDir, CacheConstants.TEMP_FILE_DIR)
  private fun fileCacheDir(): File = File(context.cacheDir, CacheConstants.CACHE_FILES_DIR)
  private fun indexDbDir(): File = File(context.filesDirCompat, CacheConstants.CACHE_BASE_DIR)

  /**
   * Creates a new temp file for downloading-into. Temp files go in a special dir that gets cleared
   * out when the datastore is opened
   */
  private fun createTempMediaFile(fileBasename: String): File {
    return File.createTempFile("filedownload", ".part", fileTempDir())
  }

  private val Context.filesDirCompat: File
    @SuppressLint("ObsoleteSdkInt") get() {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        noBackupFilesDir
      } else {
        filesDir
      }
    }

  // Starts opening the DB unless it's open or being opened. If it's open, you get the DbHelper.
  //  If it's still being opened on another thread, this method will block until the db has been
  //  opened.
  // If the db failed to open, this method will throw. Opening can be re-attempted after resolving
  @Throws(IOException::class)
  private fun awaitDbHelper(): DbHelper {
    // called only once, guaranteed by logic in this function
    fun doOpen(): DbHelper {
      ensureDirs()

      // todo- eviction pass
      // todo - clear temp dir

      val helper = DbHelper(context)
      helper.writableDatabase
      return helper
    }

    val needToStart = openTask.compareAndSet(null, FutureTask { doOpen() })
    try {
      val actualTask = openTask.get()!!
      if (needToStart) {
        actualTask.run()
      }
      return actualTask.get()
    } catch (e: Exception) {
      // todo - get a Logger down here
      Log.e("CacheDatastore", "failed to open cache", e)

      // subsequent calls can attempt again
      openTask.set(null)

      throw IOException(e)
    }
  }

  init {
    // todo - async setup
    //  * ensureDirs
    //  * clear temp dir
    //  * eviction pass
    //  ** These things must happen before any other cache operations are allowed. use coroutines
    //    and runBlocking() (for now) to guarantee this
    //TODO("set up the datastore")
  }
}

private class DbHelper(appContext: Context) : SQLiteOpenHelper(
  /*context = */ appContext,
  /* name = */ DB_FILE, // todo - put file in `filesDir/mux/player/` or something
  null,
  Schema.version
) {

  companion object {
    private const val DB_FILE = "mux-player-cache.db"
  }

  override fun onCreate(db: SQLiteDatabase?) {
    db?.execSQL("""
        create table if not exists ${Schema.FilesTable.name} (
            ${Schema.FilesTable.Columns.lookupKey} text primary key,
            ${Schema.FilesTable.Columns.remoteUrl} text not null,
            ${Schema.FilesTable.Columns.etag} text not null,
            ${Schema.FilesTable.Columns.filePath} text not null,
            ${Schema.FilesTable.Columns.downloadedAtUnixTime} integer not null,
            ${Schema.FilesTable.Columns.maxAgeUnixTime} text not null,
            ${Schema.FilesTable.Columns.resourceAgeUnixTime} text not null,
            ${Schema.FilesTable.Columns.cacheControl} text not null
        )
      """.trimIndent())
  }

  override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
    TODO("This is where we run scripts to update the schema")
  }
}

/**
 * SQL queries and parts of SQL queries that the Datastore needs in order to use the database
 * Queries with  args ('?'s) should explain what the args are for in their doc tags
 */
private object Queries {
}

/**
 * Schema for the cache index
 */
private object Schema {

  const val version = 1

  object FilesTable {
    const val name = "files"

    object Columns {
      /**
       * Key for matching URLs. Since we need to support multi-cdn without caching redundantly,
       * some files (segments) are keyed using a strategy other than hashing the entire URL.
       * Use [CacheDatastore.generateCacheKey] to calculate a cache key for a given URL
       */
      const val lookupKey = "lookup_key"

      /**
       * The URL of the remote resource
       */
      const val remoteUrl = "remote_url"

      /**
       * The etag of the response we cached for this resource
       */
      const val etag = "etag"

      /**
       * The time the resource was downloaded in unix time
       */
      const val downloadedAtUnixTime = "downloaded_at_unix_time"

      /**
       * The path of the cached copy of the file. This path is relative to the app's cache dir
       */
      const val filePath = "file_path"

      /**
       * Age of the resource as described by the `Age` header
       */
      const val resourceAgeUnixTime = "resource_age"

      /**
       * The `max-age` of the cache entry, as required by cache control.
       * `max-age` gets its own column because we must perform queries based on it
       */
      const val maxAgeUnixTime = "max_age_unix_time"

      /**
       * The 'Cache-Control' header that came down with the response we cached
       * Data found in this header may be duplicated in the table schema if it is required for
       * queries (like max-age)
       */
      const val cacheControl = "cache_control"
    }
  }
}
