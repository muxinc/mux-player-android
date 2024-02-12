package com.mux.player

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mux.player.internal.cache.CacheConstants
import com.mux.player.internal.cache.CacheDatastore
import com.mux.player.internal.cache.FileRecord
import com.mux.player.internal.cache.filesDirNoBackupCompat
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
import kotlin.math.min

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

  companion object {
    const val TAG = "CacheDatastoreInstrumentationTests"
  }

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
        sizeOnDisk = 1L
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
        lastAccessUtcSecs = 4L,
        sizeOnDisk = 1L
      )

      val writeResult1 = datastore.writeFileRecord(originalRecord)
      Assert.assertTrue(
        "First write of record with key ${originalRecord.lookupKey} should succeed",
        writeResult1.isSuccess
      )
      val writeResult2 = datastore.writeFileRecord(secondRecord)
      Assert.assertTrue(
        "next write of record with key ${secondRecord.lookupKey} should succeed",
        writeResult2.isSuccess
      )

      val readRecord = datastore.readRecordByLookupKey("lookupKey")
      Assert.assertEquals(
        "The record read-out should match the last record written with that lookup key",
        secondRecord, readRecord
      )
    }
  }

  @Test
  fun testReadRecord() {
    fun testTheCase(url: String) {
      CacheDatastore(appContext).use { datastore ->
        val originalRecord = FileRecord(
          url = url,
          etag = "etag1",
          relativePath = "cacheFile",
          lookupKey = datastore.safeCacheKey(URL(url)),
          downloadedAtUtcSecs = 1L,
          cacheMaxAge = 2L,
          resourceAge = 3L,
          cacheControl = "cacheControl",
          lastAccessUtcSecs = 4L,
          sizeOnDisk = 1L
        )
        val result = datastore.writeFileRecord(originalRecord)
        result.getOrThrow() // not part of test, writing is covered elsewhere

        val readRecord = datastore.readRecordByUrl(url)
        Assert.assertEquals(
          "The record should be the same after writing and reading",
          originalRecord, readRecord
        )
      }
    }

    testTheCase("https://www.mux.com/any/path")
    testTheCase("https://chunk-gcp-us-east4-vop1.cfcdn.mux.com/v1/chunk/vSIm02ye02gC7NasaSqE5zGP4lN3UJ01Iw01gOd01PapjUbeWza9NSOI02cpcAa02f5Kfh78vqhiWwVCk01bpcumXT4jQbfDJGBzQ02ygzY02QIMiTQuw/10.ts?skid=default&signature=NjVjZDQ1ZjBfNDYyNWYwODAxZmIzZTQ4YzU2YmQyYTZmZDhkNmYyYWQ2YjkxYmVkZmJkNThkOTBkOWRkYmU3NmRhNDVhYWY5OQ==&zone=0&vsid=z3MOq02sdo99wTURNGGxQJgKEk4qHbLSY4C8HfvZTbRPNGT0029u56MOSv8xlmJSior66tll9YK98")
  }

  @Test
  fun testReadLeastRecentFiles() {
    val maxCacheSize = 5L
    CacheDatastore(appContext, maxDiskSize = maxCacheSize).use { datastore ->
      datastore.open()
      // For this test, size "units" are like one digit.
      //  time "units" start in the 3-digit range and tick at ~10 units per call to fakeNow()

      var fakeLastAccess = 200L // increment by some amount when you need to
      fun fakeNow(since: Long = 10) = (fakeLastAccess + since).also { fakeLastAccess = it }

      val recordsWritten = mutableListOf<FileRecord>()
      for (x in 0..10) {
        val url = "https://fake.mux.com/test/url/of/index/$x.ts"
        val now = fakeNow()
        datastore.writeFileRecord(
          FileRecord(
            url = url,
            lookupKey = datastore.safeCacheKey(URL(url)),
            relativePath = "dummy/path/$x",
            etag = "etag-unique-$x",
            lastAccessUtcSecs = now,
            downloadedAtUtcSecs = 0L,
            cacheMaxAge = 400,
            resourceAge = 0,
            cacheControl = "dummy-directive",
            sizeOnDisk = 1
          ).also { recordsWritten += it }
        )
      } // for(x in ...

      val candidates = datastore.readLeastRecentFiles()
      Log.w(
        TAG, "Just checking in here's the eviction candidates:" +
                " ${candidates.joinToString("\n")}"
      )
      val candidateFiles = candidates.map { it.relativePath }
      val recordsToBeKept = mutableListOf<FileRecord>().also { copiedList ->
        copiedList.addAll(recordsWritten)
        copiedList.removeAll { candidateFiles.contains(it.relativePath) }
      }

      // We can equate 'disk size' and 'array size' here because all the elements in the test have
      //  a fake disk size of 1
      Assert.assertEquals(
        "Cache size would be $maxCacheSize after deleting",
        maxCacheSize, recordsToBeKept.size.toLong()
      )

      // every lastAccess in the rtbk should be greater than all the ones (eg, greatest one of) in the candidates
      val mostRecentCandidate = candidates.maxBy { it.lastAccessUtcSecs }
      val onlyMostRecentKept =
        recordsToBeKept.all { it.lastAccessUtcSecs >= mostRecentCandidate.lastAccessUtcSecs }
      Assert.assertTrue(
        "Only the most-recent records should be kept",
        onlyMostRecentKept
      )

    } // CacheDatastore().use
  }

  @Test
  fun testEvictByLru() {
    val maxCacheSize = 5500L
    val dummyFileSize = 1000L

    CacheDatastore(appContext, maxDiskSize = maxCacheSize).use { datastore ->
      datastore.open()
      //  time "units" start in the 3-digit range and tick at ~10 units per call to fakeNow()
      var fakeLastAccess = 200L // increment by some amount when you need to
      fun fakeNow(since: Long = 10) = (fakeLastAccess + since).also { fakeLastAccess = it }
      fun createCacheFile(url: String) = createDummyTempFile(datastore, url)
        .also { writeDummyTempFile(it, dummyFileSize) }
        .let{ datastore.moveFromTempFile(it, URL(url)) }

      val recordsWritten = mutableListOf<FileRecord>()
      for (x in 0..10) {
        val url = "https://fake.mux.com/test/url/of/index/$x.ts"
        val now = fakeNow()
        val cacheFile = createCacheFile(url)

        @Suppress("SameParameterValue")
        datastore.writeFileRecord(
          FileRecord(
            url = url,
            lookupKey = datastore.safeCacheKey(URL(url)),
            relativePath = cacheFile.toRelativeString(datastore.fileCacheDir()),
            etag = "etag-unique-$x",
            lastAccessUtcSecs = now,
            downloadedAtUtcSecs = 0L,
            cacheMaxAge = 400,
            resourceAge = 0,
            cacheControl = "dummy-directive",
            sizeOnDisk = dummyFileSize,
          ).also { recordsWritten += it }
        )
      } // for(x in ...

      val filesBeforeEviction = datastore.fileCacheDir().listFiles()!!.filter { !it.isDirectory }
      // this condition shouldn't fail
      datastore.evictByLru().getOrThrow()
      val filesAfterEviction = datastore.fileCacheDir().listFiles()!!.filter { !it.isDirectory }
      val totalDiskUsageAfterEvict = filesAfterEviction.sumOf { it.length() }
      Assert.assertTrue(
        "After eviction, cache is under max size",
        totalDiskUsageAfterEvict <= maxCacheSize
      )
    } // CacheDatastore().use
  }

  private fun writeDummyTempFile(
    file: File,
    size: Long,
  ) {
    val zeroes = ByteArray(4 * 1024)

    BufferedOutputStream(FileOutputStream(file)).use { outStream ->
      var written = 0
      do {
        val writeSize = min(zeroes.size, size.toInt() - written)
        outStream.write(zeroes, 0, writeSize)
        written += writeSize
      } while (written < size)
    }
  }

  private fun createDummyTempFile(
    datastore: CacheDatastore,
    url: String,
  ): File {
    return datastore.createTempDownloadFile(URL(url))
  }

  private fun expectedFileTempDir(context: Context): File =
    File(context.cacheDir, CacheConstants.TEMP_FILE_DIR)

  private fun expectedFileCacheDir(context: Context): File =
    File(context.cacheDir, CacheConstants.CACHE_FILES_DIR)

  private fun expectedIndexDbDir(context: Context): File =
    File(context.filesDirNoBackupCompat, CacheConstants.CACHE_BASE_DIR)
}
