package com.mux.player.internal.cache

import android.content.ContentValues
import android.database.Cursor
import com.mux.player.cacheing.IndexSchema
import com.mux.player.cacheing.getLongOrThrow
import com.mux.player.cacheing.getStringOrThrow
import java.io.File

/**
 * Represents a cached resource from a stream. Could be a segment, static rendition, or whatever
 *
 * We may only have part of the file, and the `CacheDatastore` may choose to store this data on the
 * fs in smaller pieces, all in one file, or something else.
 */
data class CachedResourceRecord(
  val url: String,
  val etag: String,
  val lookupKey: String,
  val downloadedAtUtcSecs: Long,
  val cacheMaxAge: Long,
  val resourceAge: Long,
  val cacheControl: String,
  val resourceSizeBytes: Long,
)

@JvmSynthetic
internal fun Cursor.toResourceRecord(): CachedResourceRecord {
  return CachedResourceRecord(
    url = getStringOrThrow(IndexSchema.ResourcesTable.Columns.remoteUrl),
    etag = getStringOrThrow(IndexSchema.ResourcesTable.Columns.etag),
    lookupKey = getStringOrThrow(IndexSchema.ResourcesTable.Columns.lookupKey),
    downloadedAtUtcSecs = getLongOrThrow(IndexSchema.ResourcesTable.Columns.downloadedAtUnixTime),
    cacheMaxAge = getLongOrThrow(IndexSchema.ResourcesTable.Columns.maxAgeUnixTime),
    resourceAge = getLongOrThrow(IndexSchema.ResourcesTable.Columns.resourceAgeUnixTime),
    cacheControl = getStringOrThrow(IndexSchema.ResourcesTable.Columns.cacheControl),
    resourceSizeBytes = getLongOrThrow(IndexSchema.ResourcesTable.Columns.totalSize)
  )
}

@JvmSynthetic
internal fun CachedResourceRecord.toContentValues(): ContentValues {
  val values = ContentValues()

  values.apply {
    put(IndexSchema.ResourcesTable.Columns.lookupKey, lookupKey)
    put(IndexSchema.ResourcesTable.Columns.etag, etag)
    put(IndexSchema.ResourcesTable.Columns.remoteUrl, url)
    put(IndexSchema.ResourcesTable.Columns.downloadedAtUnixTime, downloadedAtUtcSecs)
    put(IndexSchema.ResourcesTable.Columns.maxAgeUnixTime, cacheMaxAge)
    put(IndexSchema.ResourcesTable.Columns.resourceAgeUnixTime, resourceAge)
    put(IndexSchema.ResourcesTable.Columns.cacheControl, cacheControl)
  }

  return values
}
