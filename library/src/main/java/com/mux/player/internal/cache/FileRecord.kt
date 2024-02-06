package com.mux.player.internal.cache

import android.content.ContentValues
import android.database.Cursor
import com.mux.player.cacheing.CacheController
import com.mux.player.cacheing.IndexSchema
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class FileRecord(
  val url: String,
  val etag: String,
  val relativePath: String,
  val lookupKey: String,
  val lastAccessUtcSecs: Long,
  val downloadedAtUtcSecs: Long,
  val cacheMaxAge: Long,
  val resourceAge: Long,
  val cacheControl: String,
)

@JvmSynthetic
internal fun FileRecord.toContentValues(): ContentValues {
  val values = ContentValues()

  values.apply {
    put(IndexSchema.FilesTable.Columns.lookupKey, lookupKey)
    put(IndexSchema.FilesTable.Columns.etag, etag)
    put(IndexSchema.FilesTable.Columns.filePath, relativePath)
    put(IndexSchema.FilesTable.Columns.remoteUrl, url)
    put(IndexSchema.FilesTable.Columns.downloadedAtUnixTime, downloadedAtUtcSecs)
    put(IndexSchema.FilesTable.Columns.maxAgeUnixTime, cacheMaxAge)
    put(IndexSchema.FilesTable.Columns.resourceAgeUnixTime, resourceAge)
    put(IndexSchema.FilesTable.Columns.cacheControl, cacheControl)
  }

  return values
}

@JvmSynthetic
internal fun Cursor.toFileRecord(): FileRecord {
  return FileRecord(
    url = getStringOrThrow(IndexSchema.FilesTable.Columns.remoteUrl),
    lookupKey = getStringOrThrow(IndexSchema.FilesTable.Columns.lookupKey),
    lastAccessUtcSecs = getLongOrThrow(IndexSchema.FilesTable.Columns.lastAccessUnixTime),
    etag = getStringOrThrow(IndexSchema.FilesTable.Columns.etag),
    relativePath = getStringOrThrow(IndexSchema.FilesTable.Columns.filePath),
    downloadedAtUtcSecs = getLongOrThrow(IndexSchema.FilesTable.Columns.downloadedAtUnixTime),
    cacheMaxAge = getLongOrThrow(IndexSchema.FilesTable.Columns.maxAgeUnixTime),
    resourceAge = getLongOrThrow(IndexSchema.FilesTable.Columns.resourceAgeUnixTime),
    cacheControl = getStringOrThrow(IndexSchema.FilesTable.Columns.cacheControl)
  )
}

@Throws(IOException::class)
fun Cursor.getStringOrThrow(name: String): String {
  val idx = getColumnIndex(name)
  return if (idx >= 0) {
    getString(idx)
  } else {
    throw IOException("Could not find expected column: $name")
  }
}

@Throws(IOException::class)
fun Cursor.getLongOrThrow(name: String): Long {
  val idx = getColumnIndex(name)
  return if (idx >= 0) {
    getLong(idx)
  } else {
    throw IOException("Could not find expected column: $name")
  }
}

@Throws(IOException::class)
fun InputStream.consumeInto(outputStream: OutputStream, readSize: Int = 32 * 1024) {
  val buf = ByteArray(CacheController.ReadHandle.READ_SIZE)
  while (true) {
    val readBytes = read(buf)
    if (readBytes == -1) {
      // done
      break
    } else {
      outputStream.write(buf, 0, readBytes)
    }
  }
}
