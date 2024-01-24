package com.mux.player.cacheing

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mux.player.internal.cache.FileRecord

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
       * some files (segments) are keyed using a strategy other than hashing the entire URL
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
       * The path of the cached copy of the file. This path is absolute
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
