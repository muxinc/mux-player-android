package com.mux.player

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mux.player.cacheing.CacheConstants
import com.mux.player.cacheing.CacheDatastore
import com.mux.player.cacheing.filesDirNoBackupCompat
import com.mux.player.internal.cache.FileRecord
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL

/**
 * Instrumentation tests for just CacheDatastore. Tests in here are for things that require a real
 * android environment, but not necessarily a physical phone. Examples might include functions that
 * involve database queries, functions that manage files (as opposed to generate paths), etc
 *
 * There are also unit tests for functions that are all business logic, in the `test` sourceSet,
 * where everything is nicely mocked and repeatable by default.
 */
@RunWith(AndroidJUnit4::class)
class CacheDatastoreInstrumentationTests {

  private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext

  // todo - more test cases once more of the Datastore is done
  //  writeRecord: Returns successful if a new row
  //    does eviction pass after writing
  //  readRecord: Works for segments (written data == read data)
  //    Works for not-segments (written data == read data)
  //    Misses gracefully if the file underneath is deleted
  //    (after eviction) Misses & evicts if entry is eviction candidate
  //  evictionPass: Evicts according to max-age (including Age)
  //    Evicts according to cache max size
  //    Evicts according to cache quota if we are constrained (ie, if cache quota is under max)

  @Before
  fun setUp() {
    // clear the cache files
    val definitelyDelete: File.() -> Unit = {
      if (isDirectory) {
        deleteRecursively()
      } else {
        delete()
      }
    }

    // this should be fine, but we could also wipe the app cache & files dir entirely?
    expectedFileCacheDir(appContext).definitelyDelete()
    expectedFileTempDir(appContext).definitelyDelete()
    expectedIndexDbDir(appContext).definitelyDelete()
  }

  @Test
  fun testBasicInitialization() {
    val datastore = CacheDatastore(appContext)
    datastore.use { it.open() }

    Assert.assertTrue(
      "cache files dir should exist after open()",
      expectedFileCacheDir(appContext).let { it.exists() && it.isDirectory }
    )
    Assert.assertTrue(
      "cache temp files dir should exist after open()",
      expectedFileTempDir(appContext).let { it.exists() && it.isDirectory }
    )
    val dbFile = File(expectedIndexDbDir(appContext), "mux-player-cache.db")
    Assert.assertTrue(
      "cache temp files dir should exist after open()",
      expectedIndexDbDir(appContext).let { it.exists() && it.isDirectory }
    )
    Assert.assertTrue(
      "index db should be created",
      dbFile.exists() && !dbFile.isDirectory && dbFile.length() > 0
    )
  }

  @Test
  fun testCreateTempDownloadFile() {
    val datastore = CacheDatastore(appContext)
    datastore.use {
      it.open()

      val url = URL("https://some.host.com/path1/path2/basename.ts")
      val tempFile1 = datastore.createTempDownloadFile(url)
      val tempFile2 = datastore.createTempDownloadFile(url)

      Assert.assertNotEquals(
        "Temp files are always unique",
        tempFile1.absoluteFile, tempFile2.absoluteFile
      )
    }
  }

  @Test
  fun testMoveFromTempFile() {
    val basename = "basename.ts"
    val url = URL("https://some.host.com/path1/path2/$basename")
    val oldFileData = "old data".toByteArray(Charsets.UTF_8)
    val newFileData = "new data".toByteArray(Charsets.UTF_8)

    val datastore = CacheDatastore(appContext)
    datastore.open()
    datastore.use {
      // Write one file...
      val oldTempFile = datastore.createTempDownloadFile(url)
      BufferedOutputStream(FileOutputStream(oldTempFile)).use { it.write(oldFileData) }
      val permanentFile1 = datastore.moveFromTempFile(oldTempFile, url)
      Assert.assertEquals(
        "The 'permanent' file should have the content: [${oldFileData.decodeToString()}]",
        oldFileData.decodeToString(),
        BufferedInputStream(FileInputStream(permanentFile1)).use { it.readBytes() }.decodeToString()
      )

      val newTempFile = datastore.createTempDownloadFile(url)
      BufferedOutputStream(FileOutputStream(newTempFile)).use { it.write(newFileData) }
      val permanentFile2 = datastore.moveFromTempFile(newTempFile, url)
      Assert.assertEquals(
        "The second 'permanent' file should replace the first one",
        permanentFile1.absoluteFile, permanentFile2.absoluteFile
      )
      Assert.assertEquals(
        "The new 'permanent' file should have the content: [${newFileData.decodeToString()}]",
        newFileData.decodeToString(),
        BufferedInputStream(FileInputStream(permanentFile2)).use { it.readBytes() }.decodeToString()
      )
    }
  }

  @Test
  fun testWriteRecordReplacesOnKey() {
    val datastore = CacheDatastore(appContext)
    datastore.use {
      val originalRecord = FileRecord(
        url = "url",
        etag = "etag1",
        relativePath = "cacheFile",
        lookupKey = "lookupKey",
        downloadedAtUtcSecs = 1L,
        cacheMaxAge = 2L,
        resourceAge = 3L,
        cacheControl = "cacheControl",
        lastAccessUtcSecs = 4L,
      )
      val secondRecord = FileRecord(
        url = "url2",
        etag = "etag2",
        relativePath = "cacheFile",
        lookupKey = "lookupKey",
        downloadedAtUtcSecs = 1L,
        cacheMaxAge = 2L,
        resourceAge = 3L,
        cacheControl = "cacheControl",
        lastAccessUtcSecs = 4L
      )

      val writeResult1 = datastore.writeRecord(originalRecord)
      Assert.assertTrue(
        "First write of record with key ${originalRecord.lookupKey} should succeed",
        writeResult1.isSuccess
      )
      val writeResult2 = datastore.writeRecord(secondRecord)
      Assert.assertTrue(
        "next write of record with key ${secondRecord.lookupKey} should succeed",
        writeResult2.isSuccess
      )

      // todo - read-out the record and ensure it is equal to the second record
    }
  }

  private fun expectedFileTempDir(context: Context): File =
    File(context.cacheDir, CacheConstants.TEMP_FILE_DIR)

  private fun expectedFileCacheDir(context: Context): File =
    File(context.cacheDir, CacheConstants.CACHE_FILES_DIR)

  private fun expectedIndexDbDir(context: Context): File =
    File(context.filesDirNoBackupCompat, CacheConstants.CACHE_BASE_DIR)
}
