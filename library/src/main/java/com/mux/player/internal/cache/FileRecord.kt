package com.mux.player.internal.cache

import android.content.ContentValues
import android.database.Cursor

data class FileRecord(
  val url: String,
  val etag: String,
  val relativePath: String,
  val sizeOnDisk: Long,
  val lookupKey: String,
  val lastAccessUtcSecs: Long,
  val downloadedAtUtcSecs: Long,
  val cacheMaxAge: Long,
  val resourceAge: Long,
  val cacheControl: String,
) {
  fun isStale(nowUtc: Long): Boolean {
    return (nowUtc - downloadedAtUtcSecs) + resourceAge >= cacheMaxAge
  }
}

@JvmSynthetic
internal fun FileRecord.toContentValues(): ContentValues {
  val values = ContentValues()

  values.apply {
    put(IndexSql.Files.Columns.lookupKey, lookupKey)
    put(IndexSql.Files.Columns.etag, etag)
    put(IndexSql.Files.Columns.filePath, relativePath)
    put(IndexSql.Files.Columns.remoteUrl, url)
    put(IndexSql.Files.Columns.downloadedAtUnixTime, downloadedAtUtcSecs)
    put(IndexSql.Files.Columns.maxAgeUnixTime, cacheMaxAge)
    put(IndexSql.Files.Columns.resourceAgeUnixTime, resourceAge)
    put(IndexSql.Files.Columns.cacheControl, cacheControl)
    put(IndexSql.Files.Columns.lastAccessUnixTime, lastAccessUtcSecs)
    put(IndexSql.Files.Columns.diskSize, sizeOnDisk)
  }

  return values
}

@JvmSynthetic
internal fun Cursor.toFileRecord(): FileRecord {
  return FileRecord(
    url = getStringOrThrow(IndexSql.Files.Columns.remoteUrl),
    lookupKey = getStringOrThrow(IndexSql.Files.Columns.lookupKey),
    lastAccessUtcSecs = getLongOrThrow(IndexSql.Files.Columns.lastAccessUnixTime),
    etag = getStringOrThrow(IndexSql.Files.Columns.etag),
    relativePath = getStringOrThrow(IndexSql.Files.Columns.filePath),
    downloadedAtUtcSecs = getLongOrThrow(IndexSql.Files.Columns.downloadedAtUnixTime),
    cacheMaxAge = getLongOrThrow(IndexSql.Files.Columns.maxAgeUnixTime),
    resourceAge = getLongOrThrow(IndexSql.Files.Columns.resourceAgeUnixTime),
    cacheControl = getStringOrThrow(IndexSql.Files.Columns.cacheControl),
    sizeOnDisk = getLongOrThrow(IndexSql.Files.Columns.diskSize),
  )
}
