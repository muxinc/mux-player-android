package com.mux.player

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mux.player.cacheing.CacheConstants
import com.mux.player.cacheing.CacheDatastore
import com.mux.player.cacheing.filesDirNoBackupCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CacheDatastoreInstrumentationTests {

  private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext

  // todo -test cases
  //  basic init: Dirs created, temp dir clear, db file in place
  //  open-close: (if possible) Like the caller handled an exception we weren't a part of, making sure db not allocated
  //  open-close-open: Same conditions as basic init should be satisfied
  //  open-open: Same conditions as basic init
  //
  // todo - more test cases
  //  writing:
  //  createTempDownloadFile: creates file, file has basename in it
  //  moveFromTempFile: File written when moveFromTempFile, has content of temp file
  //    .. even if there was another file first
  //  writeRecord: Returns successful if a new row
  //    still returns successful if not a new row
  //    does eviction pass after writing
  //  readRecord: Works for segments (written data = read data)
  //    Works for not-segments (written data = read data)
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
      "index db should be created",
      dbFile.exists() &&! dbFile.isDirectory && dbFile.length() > 0
    )
  }

  private fun expectedFileTempDir(context: Context): File =
    File(context.cacheDir, CacheConstants.TEMP_FILE_DIR)
  private fun expectedFileCacheDir(context: Context): File =
    File(context.cacheDir, CacheConstants.CACHE_FILES_DIR)
  private fun expectedIndexDbDir(context: Context): File =
    File(context.filesDirNoBackupCompat, CacheConstants.CACHE_BASE_DIR)
}
