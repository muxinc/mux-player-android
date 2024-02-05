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
 * The file is a contiguous part of an overall resource (segment, mp4 file, etc). It may be only
 * part of a full resource. To get all the spans cached
 *
 * We may only have part of the file, and the `CacheDatastore` may choose to store this data on the
 * fs in smaller pieces, all in one file, or something else.
 *
 * The size of the entire resource can be found on the [CachedResourceRecord]
 */
data class RangeFileRecord(
  val lookupKey: String,
  val relativePath: String,
  val fileSize: Long,
  val lastAccessedAtUtcSecs: Long,
  val startOffsetInResource: Long,
  val endOffsetInResource: Long,
)

@JvmSynthetic
internal fun RangeFileRecord.toContentValues(): ContentValues {
  return ContentValues().apply {
    put(IndexSchema.FilesTable.Columns.lookupKey, lookupKey)
    put(IndexSchema.FilesTable.Columns.filePath, relativePath)
    put(IndexSchema.FilesTable.Columns.fileSize, fileSize)
    put(IndexSchema.FilesTable.Columns.lastAccessedUtc, lastAccessedAtUtcSecs)
    put(IndexSchema.FilesTable.Columns.startOffset, startOffsetInResource)
    put(IndexSchema.FilesTable.Columns.endOffset, endOffsetInResource)
  }
}

@JvmSynthetic
internal fun Cursor.toRangeRecord(): RangeFileRecord {
 return RangeFileRecord(
   lookupKey = getStringOrThrow(IndexSchema.FilesTable.Columns.lookupKey),
   relativePath = getStringOrThrow(IndexSchema.FilesTable.Columns.filePath),
   fileSize = getLongOrThrow(IndexSchema.FilesTable.Columns.fileSize),
   lastAccessedAtUtcSecs = getLongOrThrow(IndexSchema.FilesTable.Columns.lastAccessedUtc),
   startOffsetInResource = getLongOrThrow(IndexSchema.FilesTable.Columns.startOffset),
   endOffsetInResource = getLongOrThrow(IndexSchema.FilesTable.Columns.endOffset),
 )
}
