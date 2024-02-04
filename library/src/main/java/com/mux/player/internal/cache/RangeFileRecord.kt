package com.mux.player.internal.cache

import android.content.ContentValues
import com.mux.player.cacheing.IndexSchema
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
  val file: File,
  val fileSize: Long,
  val lastAccessedAtUtcSecs: Long,
  val startOffsetInResource: Long,
  val endOffsetInResource: Long,
)

@JvmSynthetic
internal fun RangeFileRecord.toContentValues(): ContentValues {
  return ContentValues().apply {
    put(IndexSchema.FilesTable.Columns.lookupKey, lookupKey)
    put(IndexSchema.FilesTable.Columns.filePath, file.path)
    put(IndexSchema.FilesTable.Columns.fileSize, fileSize)
    put(IndexSchema.FilesTable.Columns.lastAccessedUtc, lastAccessedAtUtcSecs)
    put(IndexSchema.FilesTable.Columns.startOffset, startOffsetInResource)
    put(IndexSchema.FilesTable.Columns.endOffset, endOffsetInResource)
  }
}

// todo - Ok so how to refactor ReadHandle and WriteHandle?
//  Make them Closeable? Yep
//  WriteHandle: Needs to understand Content-Range
//    tryWrite(whatever): Need to write CachedResourceRecord first
//    finishedWriting() needs to write amount actually written, not assume entire amount
//    finishedWriting() -> close()
//  ReadHandle/tryRead:
//    tryRead() must take a content range
//    ReadHandle should tell the caller how much data it actually has available (could be less)
//      could tell what other spans it has too (but ReadHandle is for one request, so don't)
//      Yes, ReadHandle should be doing this and not the proxy. 'cause player won't request exactly
//      the same content ranges every time
//        ReadHandle has a list of ContentRange's but also its read() also needs to be able to assemble
//          an arbitrary ContentRange's worth of bytes
//          Whats more it has to be, since server won't ask for exact same ranges all the time
//    close() -> Does cleanup with db maybe, definitely closes InputStream(s)

// todo - note about eviction: LRU is based on cached span files, but age is based off whole segment
//  so remember that in the query [[IMplies that span table needs a last-access, CRR does not]]
