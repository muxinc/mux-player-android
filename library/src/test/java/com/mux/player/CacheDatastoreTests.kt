package com.mux.player

import android.content.Context
import com.mux.player.internal.cache.CacheDatastore
import com.mux.player.internal.cache.IndexSchema
import com.mux.player.internal.cache.FileRecord
import com.mux.player.internal.cache.toContentValues
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.net.URL

class CacheDatastoreTests: AbsRobolectricTest() {

  private lateinit var cacheDatastore: CacheDatastore

  @Before
  fun setUp() {
    val mockContext = mockk<Context> {
      every { applicationContext } returns mockk<Context> {

      }
    }

    cacheDatastore = CacheDatastore(mockContext)
  }

  @Test
  fun `generateCacheKey generates special keys for segments`() {
    val notASegmentUrl = "https://manifest-gcp-us-east4-vop1.cfcdn.mux.com/efg456hjk/rendition.m3u8"
    val hlsSegmentUrl = "https://chunk-gcp-us-east4-vop1.cfcdn.mux.com/v1/chunk/hls123abc/0.ts"
    val cmafSegmentUrl = "https://chunk-gcp-us-east4-vop1.cfcdn.mux.com/v1/chunk/cmaf456def/146.m4s"

    val notASegmentKey =
      cacheDatastore.generateCacheKey(URL(notASegmentUrl))
    val hlsKey = cacheDatastore.generateCacheKey(URL(hlsSegmentUrl))
    val cmafKey = cacheDatastore.generateCacheKey(URL(cmafSegmentUrl))

    Assert.assertEquals(
      "Non-segment URLs key on the entire URL",
      notASegmentUrl, notASegmentKey
    )
    Assert.assertNotEquals(
      "HLS segment URLs have a special key",
      hlsKey, hlsSegmentUrl
    )
    Assert.assertNotEquals(
      "CMAF segment URLs have a special key",
      cmafKey, cmafSegmentUrl
    )
  }

  @Test
  fun `generateCacheKey generates cache keys for segments correctly`() {
    val segmentUrl = "https://chunk-gcp-us-east4-vop1.cfcdn.mux.com/v1/chunk/abc12345xyz/0.ts"
    val expectedKey = "/v1/chunk/abc12345xyz/0.ts"

    val key = cacheDatastore.generateCacheKey(URL(segmentUrl))
    Assert.assertEquals(
      "cache key should be constructed properly",
      expectedKey, key
    )
  }

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
      lastAccessUtcSecs = 4L
    )

    val contentValues = record.toContentValues()
    Assert.assertEquals(
      "last-access should be saved",
      4L, contentValues.getAsLong(IndexSchema.FilesTable.Columns.lastAccessUnixTime)
    )
    Assert.assertEquals(
      "url should be saved",
      "url", contentValues.getAsString(IndexSchema.FilesTable.Columns.remoteUrl)
    )
    Assert.assertEquals(
      "etag should be saved",
      "etag", contentValues.getAsString(IndexSchema.FilesTable.Columns.etag)
    )
    Assert.assertEquals(
      "file should be saved",
      "cacheFile", contentValues.getAsString(IndexSchema.FilesTable.Columns.filePath)
    )
    Assert.assertEquals(
      "lookup key should be saved",
      "lookupKey", contentValues.getAsString(IndexSchema.FilesTable.Columns.lookupKey)
    )
    Assert.assertEquals(
      "downloadedAt should be saved",
      1L, contentValues.getAsLong(IndexSchema.FilesTable.Columns.downloadedAtUnixTime)
    )
    Assert.assertEquals(
      "cacheMaxAge should be saved",
      2L, contentValues.getAsLong(IndexSchema.FilesTable.Columns.maxAgeUnixTime)
    )
    Assert.assertEquals(
      "age should be saved",
      3L, contentValues.getAsLong(IndexSchema.FilesTable.Columns.resourceAgeUnixTime)
    )
    Assert.assertEquals(
      "cache-control should be saved for future reference",
      "cacheControl", contentValues.getAsString(IndexSchema.FilesTable.Columns.cacheControl)
    )
  }
}
