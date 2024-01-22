package com.mux.player.cacheing

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mux.player.internal.cache.CacheControlRecord
import com.mux.player.internal.cache.FileRecord

class CacheDatastore(val context: Context) {

  private val openHelper: SQLiteOpenHelper by lazy { DbHelper(context) }

  fun writeRecord(fileRecord: FileRecord, cacheControlRecord: CacheControlRecord): Result<Unit> {
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
  /* name */DB_FILE, // todo - put file in `cacheDir/mux/player/` or something
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