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