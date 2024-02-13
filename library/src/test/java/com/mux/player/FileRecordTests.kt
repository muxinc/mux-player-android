package com.mux.player

import com.mux.player.internal.cache.FileRecord
import com.mux.player.internal.cache.IndexSql
import com.mux.player.internal.cache.toContentValues
import org.junit.Assert
import org.junit.Test

class FileRecordTests : AbsRobolectricTest() {

  @Test
  fun `FileRecord-toContentValues generates correct content values for its fields`() {
    val record = FileRecord(
      url = "url",
      etag = "etag",
      relativePath = "cacheFile",
      lookupKey = "lookupKey",
      downloadedAtUtcSecs = 1L,
      cacheMaxAge = 2L,
      resourceAge = 3L,
      cacheControl = "cacheControl",
      lastAccessUtcSecs = 4L,
      sizeOnDisk = 5L
    )

    val contentValues = record.toContentValues()
    Assert.assertEquals(
      "size on disk should be saved",
      5L, contentValues.getAsLong(IndexSql.Files.Columns.diskSize)
    )
    Assert.assertEquals(
      "last-access should be saved",
      4L, contentValues.getAsLong(IndexSql.Files.Columns.lastAccessUnixTime)
    )
    Assert.assertEquals(
      "url should be saved",
      "url", contentValues.getAsString(IndexSql.Files.Columns.remoteUrl)
    )
    Assert.assertEquals(
      "etag should be saved",
      "etag", contentValues.getAsString(IndexSql.Files.Columns.etag)
    )
    Assert.assertEquals(
      "file should be saved",
      "cacheFile", contentValues.getAsString(IndexSql.Files.Columns.filePath)
    )
    Assert.assertEquals(
      "lookup key should be saved",
      "lookupKey", contentValues.getAsString(IndexSql.Files.Columns.lookupKey)
    )
    Assert.assertEquals(
      "downloadedAt should be saved",
      1L, contentValues.getAsLong(IndexSql.Files.Columns.downloadedAtUnixTime)
    )
    Assert.assertEquals(
      "cacheMaxAge should be saved",
      2L, contentValues.getAsLong(IndexSql.Files.Columns.maxAgeUnixTime)
    )
    Assert.assertEquals(
      "age should be saved",
      3L, contentValues.getAsLong(IndexSql.Files.Columns.resourceAgeUnixTime)
    )
    Assert.assertEquals(
      "cache-control should be saved for future reference",
      "cacheControl", contentValues.getAsString(IndexSql.Files.Columns.cacheControl)
    )
  }
}