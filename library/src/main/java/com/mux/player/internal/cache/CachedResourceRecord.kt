package com.mux.player.internal.cache

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
//  val file: File,
  val lookupKey: String,
  val downloadedAtUtcSecs: Long,
  val cacheMaxAge: Long,
  val resourceAge: Long,
  val cacheControl: String,
  /**
   * The size of the resource in bytes. For 'normal' HTTP responses, this is set by the Content-Length
   * header. For Partial-Content responses, this is set based on the total size in the Content-Range
   * header
   */
  val resourceSizeBytes: Long,

  // Have thse byte ranges in the cache
  //  todo - this needs to go on ReadHandle instead
  // tryRead() can do this
//  val haveByteRanges: List<Pair<Long, Long>>,
)

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
