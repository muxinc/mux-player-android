package com.mux.player

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaDrm
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.DefaultDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class OfflineDownloadInstrumentationTests {

  private lateinit var mediaDownloadManager: DownloadManager
  private lateinit var testDatabaseProvider: DatabaseProvider
  private lateinit var testCache: SimpleCache
  private lateinit var downloadIndex: DownloadIndex

  // TODO: Doesn't need to be a field
  private lateinit var mediaDrm: MediaDrm

  private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Before
  fun setUp() {
    ensureEmptyCacheDir()

    val openHelper = TestDbHelper(appContext)
    testDatabaseProvider = DefaultDatabaseProvider(openHelper)
    testCache = SimpleCache(
      /*cacheDir=*/ File(appContext.filesDir, CACHE_SUBDIR),
      /*evictor=*/ NoOpCacheEvictor(),
      /*databaseProvider=*/ testDatabaseProvider
    )
    downloadIndex = DefaultDownloadIndex(testDatabaseProvider)
    mediaDownloadManager = DownloadManager(
      /*context=*/ appContext,
      /*databaseProvider=*/ testDatabaseProvider,
      /*cache=*/ testCache,
      /*upstreamFactory=*/ DefaultHttpDataSource.Factory(),
      /*executor=*/ Executors.newCachedThreadPool()
    )
  }

  @After
  fun cleanUp() {
    ensureEmptyCacheDir()
  }

  @Test
  fun testCleartextDownload() {
  }

  @Test
  fun testDrmDownload() {

  }

  private fun deleteCachedWidevineLicenses() {
    TODO("drm tests not yet implemented")
  }

  private fun ensureEmptyCacheDir() {
    fun deleteRecursively(file: File): Boolean {
      if (file.isDirectory) {
        // !! is either an error or isDirectory returning true for a non-file. Both are abort cases
        for (file in file.listFiles()!!) {
          val deleted = file.deleteRecursively()
          if (!deleted) {
            return false
          }
        }
        return true
      } else {
        return file.delete()
      }
    }

    // delete stale test downloads and prep the dir again
    val cacheDirFile = File(appContext.filesDir, CACHE_SUBDIR)
    if (cacheDirFile.exists()) {
      deleteRecursively(file = cacheDirFile)
    }
    cacheDirFile.mkdirs()
  }

  companion object {
    // TODO: Could run this in CI for real if we supply tokens to the CI machine
    private val PLAY_TOKEN = ""
    private val DRM_TOKEN = ""
    private val DRM_PLAYBACK_ID = ""

    private val CLEARTEXT_PLAYBACK_ID = "zyII9g3ndjv9jOQi7JQh37oAUfLok2kvtdHmlGBPuVc"

    private val CACHE_SUBDIR = "test_downloads"
    private val DB_NAME = "test_download.db" // TODO: Maybe I won't need
  }

  private class TestDbHelper(
    context: Context
  ): SQLiteOpenHelper(
    context,
    null, // In-mem index (media files still saved on-disk)
    null,
    0
  ) {
    override fun onCreate(p0: SQLiteDatabase?) {
      // SimpleCache creates tables
    }

    override fun onUpgrade(
      p0: SQLiteDatabase?,
      p1: Int,
      p2: Int
    ) {
      // SimpleCache maintains tables
    }

  }
}
