package com.mux.player.cacheing

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.util.Base64
import com.mux.player.internal.cache.FileRecord
import com.mux.player.oneOf
import java.io.File
import java.net.URL

internal class CacheDatastore(val context: Context) {

  private val RX_CHUNK_URL =
    Regex("""https://.*\.mux.com/v1/chunk/([^/]*)/([^/]*)\.(m4s|ts)""")
  private val openHelper: SQLiteOpenHelper by lazy { DbHelper(context) }

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
        requestUrl.path
      } else {
        urlStr
      }
    }

    // todo - wait we need this to be base64 no matter what
    return key
  }

  /**
   * Generates a URL-safe cache key for a given URL.
   */
  @JvmSynthetic
  internal fun safeCacheKey(url: URL): String = Base64.encodeToString(
    generateCacheKey(url).toByteArray(Charsets.UTF_8),
    Base64.URL_SAFE
  )

  private fun ensureDirs()  {
    fileTempDir().mkdirs()
    fileCacheDir().mkdirs()
    indexDbDir().mkdirs()
  }

  private fun fileTempDir(): File = File(context.cacheDir, CacheConstants.TEMP_FILE_DIR)
  private fun fileCacheDir(): File = File(context.cacheDir, CacheConstants.CACHE_FILES_DIR)
  private fun indexDbDir(): File = File(context.filesDirCompat, CacheConstants.CACHE_BASE_DIR)

  /**
   * Creates a new temp file for downloading-into
   */
  private fun createTempMediaFile(fileBasename: String): File {
    return File.createTempFile("filedownload", ".part", fileTempDir())
  }

  private fun filenameForKey(cacheKey: String): String {
    val base64 =  Base64.encode(cacheKey.toByteArray(Charsets.UTF_8), Base64.URL_SAFE)
    return base64.toString(Charsets.UTF_8)
  }

  private val Context.filesDirCompat: File
  @SuppressLint("ObsoleteSdkInt") get() {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      noBackupFilesDir
    } else {
      filesDir
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
  /* name */DB_FILE, // todo - put file in `filesDir/mux/player/` or something
  null,
  DB_VERSION
) {

  companion object {
    private const val DB_VERSION = 1
    private const val DB_FILE = "mux-player-cache.db"
  }

  override fun onCreate(db: SQLiteDatabase?) {
    TODO("Not yet implemented")
  }

  override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
    TODO("Not yet implemented")
  }
}

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
       * Use [CacheController.generateCacheKey] to calculate a cache key for a given URL
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
      const val resourceAge = "resource_age"
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
