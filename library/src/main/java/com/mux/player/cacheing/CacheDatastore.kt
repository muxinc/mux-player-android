package com.mux.player.cacheing

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.util.Base64
import com.mux.player.internal.cache.FileRecord
import java.io.File

class CacheDatastore(val context: Context) {

  private val openHelper: SQLiteOpenHelper by lazy { DbHelper(context) }

  fun writeRecord(fileRecord: FileRecord): Result<Unit> {
    // todo - write record. If a record exists with same key then replace with this record
    return Result.success(Unit)
  }

  fun readRecord(url: String): FileRecord? {
    // todo - try to read a record for the given URL, returning it if there's a hit
    return null
  }

  private fun ensureDirs()  {
    fileTempDir().mkdirs()
    fileCacheDir().mkdirs()
    fileBaseDir().mkdirs()
  }

  private fun fileTempDir(): File = File(context.cacheDir, CacheConstants.TEMP_FILE_DIR)
  private fun fileCacheDir(): File = File(context.cacheDir, CacheConstants.CACHE_FILES_DIR)
  private fun fileBaseDir(): File = File(context.cacheDir, CacheConstants.CACHE_BASE_DIR)

  /**
   * Creates a new temp file for downloading-into
   */
  private fun createTempMediaFile(fileBasename: String): File {
    return File.createTempFile("filedownload", ".part", fileTempDir())
  }

  // not for security, just making everything path-safe
  private fun filenameForKey(cacheKey: String): String {
    val base64 =  Base64.encode(cacheKey.toByteArray(Charsets.UTF_8), Base64.DEFAULT)
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
    TODO("set up the datastore")
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
       * Use [CacheController.segmentCacheKey] to calculate a cache key for a given URL
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
