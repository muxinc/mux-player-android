package com.mux.player

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaDrm
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.DefaultDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mux.player.internal.createLogcatLogger
import com.mux.player.internal.getDrmToken
import com.mux.player.internal.getPlaybackId
import com.mux.player.media.MediaItems
import com.mux.player.media.MuxDrmSessionManagerProvider
import com.mux.player.offline.MuxHlsDownloadCallback
import com.mux.player.offline.MuxOfflineCmafHlsMediaSource
import com.mux.player.offline.createMuxHlsDownloadHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.Exception
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class OfflineDownloadInstrumentationTests {

  private lateinit var mediaDownloadManager: DownloadManager
  private lateinit var testDatabaseProvider: DatabaseProvider
  private lateinit var testCache: SimpleCache
  private lateinit var downloadIndex: DownloadIndex
  private lateinit var ioExecutor: Executor

  // TODO: Doesn't need to be a field
  private lateinit var mediaDrm: MediaDrm

  private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Before
  fun setUp() {
    ensureEmptyCacheDir()

    val openHelper = TestDbHelper(appContext)

    ioExecutor = Executors.newFixedThreadPool(4)
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
      /*executor=*/ ioExecutor
    )
    mediaDownloadManager.requirements = Requirements(0) // no requirements for starting
  }

  @After
  fun cleanUp() {
    ensureEmptyCacheDir()
  }

  @Test
  fun testCleartextDownload() = runTest {
    val mediaItem = MediaItems.fromMuxPlaybackId(playbackId = CLEARTEXT_PLAYBACK_ID)
    val clearTextDrmSessionManagerProvider = { _: MediaItem -> DrmSessionManager.DRM_UNSUPPORTED }
    val fileMediaSource = MuxOfflineCmafHlsMediaSource.create(
      dataSourceFactory = DefaultHttpDataSource.Factory(),
      mediaItem = mediaItem,
      drmSessionManagerProvider = clearTextDrmSessionManagerProvider
    )

    val preparationComplete = CompletableDeferred<DownloadRequest>()

    val downloadHelper = createMuxHlsDownloadHelper(appContext, fileMediaSource)
    downloadHelper.prepare(MuxHlsDownloadCallback(
      fileMediaSource,
      // DrmSessionManagerProvider shouldn't be touched (tested in unit tests)
      drmProvider = MuxDrmSessionManagerProvider(
        DefaultHttpDataSource.Factory(),
        createLogcatLogger()
      ),
      playbackId = mediaItem.getPlaybackId()!!, // MediaItems.fromMuxPlaybackId tested elsewhere
      drmToken = null,
      ioExecutor = ioExecutor,
      onReady = { preparationComplete.complete(it) },
      onError = { preparationComplete.completeExceptionally(it) }
    ))

    // Throws here if there was an error selecting tracks
    val downloadRequest = preparationComplete.await()

    val downloadComplete = CompletableDeferred<Download>()
    mediaDownloadManager.addListener(object : DownloadManager.Listener {
      override fun onDownloadChanged(
        downloadManager: DownloadManager,
        download: Download,
        finalException: Exception?
      ) {
        Log.d(logTag(), "download state changed:\n\tstate:${download.state}")
        when (download.state) {
          Download.STATE_COMPLETED -> {
            Log.d(logTag(), "download complete")
            downloadComplete.complete(download)
          }
          Download.STATE_DOWNLOADING -> {
            Log.d(logTag(), "downloading: ${download.percentDownloaded}% (${download.bytesDownloaded} bytes)")
          }
          Download.STATE_FAILED -> {
            // contract guarantees finalException when state is failed
            downloadComplete.completeExceptionally(
              finalException ?: AssertionError("failed without exception")
            )
          }
        }
      }

      override fun onInitialized(downloadManager: DownloadManager) {
        Log.v(logTag(), "DownloadManager onInitialized()1")
      }

      override fun onRequirementsStateChanged(
        downloadManager: DownloadManager,
        requirements: Requirements,
        notMetRequirements: Int
      ) {
        if (notMetRequirements != 0) {
          // for our test this isn't expected to happen, but the test shouldn't hang if it does
          downloadComplete.completeExceptionally(
            AssertionError("DownloadManager didn't want to start. Requirements too strict?")
          )
        }
      }
    })

    mediaDownloadManager.addDownload(downloadRequest)

    val completedDownload = downloadComplete.await()

    // TODO: Assert on this completed download
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
