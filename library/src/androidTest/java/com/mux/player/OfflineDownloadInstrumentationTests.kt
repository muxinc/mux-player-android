package com.mux.player

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaDrm
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.DefaultDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mux.player.internal.createLogcatLogger
import com.mux.player.internal.getPlaybackId
import com.mux.player.media.MediaItems
import com.mux.player.media.MuxDrmSessionManagerProvider
import com.mux.player.offline.MuxHlsDownloadCallback
import com.mux.player.offline.MuxOfflineCmafHlsMediaSource
import com.mux.player.offline.createMuxHlsDownloadHelper
import com.mux.player.util.LoggingHttpDataSource
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.Exception
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class OfflineDownloadInstrumentationTests {

  companion object {
    val TAG = "OfflineDownloadInstrumentationTests"

    // TODO: Could run this in CI for real if we supply tokens to the CI machine
    private val PLAY_TOKEN = ""
    private val DRM_TOKEN = ""
    private val DRM_PLAYBACK_ID = ""

    private const val CLEAR_CMAF_PLAYBACK_ID = "5ICwECLW8900gMTi5eaOkWdYvOkGhtKyBY02uRCT6FOyE"
    private const val CLEAR_PLAYBACK_ID = "KyU4B3aJB01jjk00EmZBkp9nRkeaZyTblN3EwmjhIqkcw"
    private const val CLEAR_MULTI_LANG_PLAYBACK_ID = "3x5wDUHxkd8NkEfspLUK3OpSQEJe3pom"

    // warning: excessively verbose
    private const val LOG_NETWORK_REQUESTS = false
    private const val CACHE_SUBDIR = "test_downloads"
  }

  private lateinit var testDownloadManager: DownloadManager
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
    testDownloadManager = DownloadManager(
      /*context=*/ appContext,
      /*databaseProvider=*/ testDatabaseProvider,
      /*cache=*/ testCache,
      /*upstreamFactory=*/ LoggingHttpDataSource.Factory(DefaultHttpDataSource.Factory(), tag = "DownloadHttp", logging = LOG_NETWORK_REQUESTS),
      /*executor=*/ ioExecutor
    )
    testDownloadManager.requirements = Requirements(0) // no requirements for starting
  }

  @After
  fun cleanUp() {
    testCache.release()
    testDownloadManager.release()

    ensureEmptyCacheDir()
  }

  @Test
  fun testCleartextSimple() = runTest {
    val mediaItem = MediaItems.fromMuxPlaybackId(
      playbackId = CLEAR_PLAYBACK_ID
    )
    testMediaItemCase(mediaItem)
  }

  @Test
  fun testCleartextCmaf() = runTest {
    val mediaItem = MediaItems.fromMuxPlaybackId(
      playbackId = CLEAR_CMAF_PLAYBACK_ID,
      assetStartTime = 0.0,
      assetEndTime = 60.0
    )
    testMediaItemCase(mediaItem)
  }

  @Test
  fun testCleartextMutliLang() = runTest {
    val mediaItem = MediaItems.fromMuxPlaybackId(
      playbackId = CLEAR_MULTI_LANG_PLAYBACK_ID
    )
    testMediaItemCase(
      mediaItem,
      expectedAudioTrackIds = listOf("main:audio", "audio:Français"),
      expectedSubtitleIds = listOf("subtitle:English", "subtitle:Français"),
    )
  }

  // Download something using the DownloadHelper and OfflineLicenseHelpers and assert expected tracks
  suspend fun testMediaItemCase(
    mediaItem: MediaItem,
    expectedAudioTrackIds: List<String> = listOf(),
    expectedSubtitleIds: List<String> = listOf()
  ) {
    val clearTextDrmSessionManagerProvider = { _: MediaItem -> DrmSessionManager.DRM_UNSUPPORTED }
    val fileMediaSource = MuxOfflineCmafHlsMediaSource.create(
      dataSourceFactory = LoggingHttpDataSource.Factory(
        DefaultHttpDataSource.Factory(),
        tag = "MediaSrcHttp",
        logging = LOG_NETWORK_REQUESTS
      ),
      mediaItem = mediaItem,
      drmSessionManagerProvider = clearTextDrmSessionManagerProvider
    )

    Log.d(TAG, "testCleartextDownload(): Preparing Download")
    val preparationComplete = CompletableDeferred<DownloadRequest>()
    val downloadHelper = createMuxHlsDownloadHelper(appContext, fileMediaSource)
    downloadHelper.prepare(
      MuxHlsDownloadCallback(
      fileMediaSource,
      // DrmSessionManagerProvider shouldn't be touched (tested in unit tests)
      drmProvider = MuxDrmSessionManagerProvider(
        LoggingHttpDataSource.Factory(
          DefaultHttpDataSource.Factory(),
          tag = "DrmHttp",
          logging = LOG_NETWORK_REQUESTS
        ),
        createLogcatLogger()
      ),
      playbackId = mediaItem.getPlaybackId()!!, // MediaItems.fromMuxPlaybackId tested elsewhere
      drmToken = null,
      ioExecutor = ioExecutor,
      onReady = { preparationComplete.complete(it) },
      onError = {
        preparationComplete.completeExceptionally(
          Exception(
            "Download prep failed",
            it
          )
        )
      }
    ))

    // Throws here if there was an error selecting tracks
    val downloadRequest = preparationComplete.await()

    val downloadComplete = CompletableDeferred<Download>()
    testDownloadManager.addListener(object : DownloadManager.Listener {
      override fun onDownloadChanged(
        downloadManager: DownloadManager,
        download: Download,
        finalException: Exception?
      ) {
        Log.d(
          TAG, "download state (id ${download.request.id}) changed:" +
              "\n\tstate:${downloadStateName(download.state)}"
        )
        when (download.state) {
          Download.STATE_COMPLETED -> {
            Log.d(TAG, "download complete")
            downloadComplete.complete(download)
          }

          Download.STATE_DOWNLOADING -> {
            Log.d(
              TAG,
              "downloading: ${download.percentDownloaded}% (${download.bytesDownloaded} bytes)"
            )
          }

          Download.STATE_FAILED -> {
            // contract guarantees finalException when state is failed
            downloadComplete.completeExceptionally(
              RuntimeException("Download failed", finalException)
            )
          }
        }
      }

      override fun onInitialized(downloadManager: DownloadManager) {
        Log.v(TAG, "DownloadManager onInitialized()1")
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

    Log.d(TAG, "testClearTextDownload(): Starting download")
    testDownloadManager.addDownload(downloadRequest)
    testDownloadManager.resumeDownloads()

    val completedDownload = downloadComplete.await()

    Log.d(TAG, "testClearTextDownload(): Preparing disk media src")
    val diskMediaItem = completedDownload.request.toMediaItem()
    val diskMediaSource = DefaultMediaSourceFactory(appContext)
      .setDataSourceFactory(
        CacheDataSource.Factory()
          .setCache(testCache)
          .setUpstreamDataSourceFactory(null) // Entire media should be downloaded
          .setCacheWriteDataSinkFactory(null) // don't write, since we read everything from cache
      )
      .createMediaSource(diskMediaItem)

    val completedPrepareFromDisk = CompletableDeferred<Tracks>()
    coroutineScope {
      launch(Dispatchers.Main) {
        val player = ExoPlayer.Builder(appContext).build()
        try {
          player.addMediaSource(diskMediaSource)
          player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
              Log.d(TAG, "onPlaybackStateChanged: $playbackState")
            }

            override fun onTracksChanged(tracks: Tracks) {
              completedPrepareFromDisk.complete(tracks)
              player.release()
            }
          })
          player.prepare()
        } catch (e: Throwable) {
          completedPrepareFromDisk.completeExceptionally(e)
        }
      }
    }

    val tracks = completedPrepareFromDisk.await()

    assertEquals(
      "Expected audio tracks available",
      expectedAudioTrackIds.sorted(),
      tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.map { it.mediaTrackGroup.id }.sorted()
    )

    assertEquals(
      "Expected subtitle tracks available",
      expectedSubtitleIds.sorted(),
      tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.map { it.mediaTrackGroup.id }.sorted()
    )
  }

  @Test
  fun testDrmDownload() {

  }

  private fun deleteCachedWidevineLicenses() {
    TODO("drm tests not yet implemented")
  }

  /** Human-readable name for a [Download.state] value, for logging/assertion messages. */
  private fun downloadStateName(state: Int): String = when (state) {
    Download.STATE_QUEUED -> "STATE_QUEUED"
    Download.STATE_STOPPED -> "STATE_STOPPED"
    Download.STATE_DOWNLOADING -> "STATE_DOWNLOADING"
    Download.STATE_COMPLETED -> "STATE_COMPLETED"
    Download.STATE_FAILED -> "STATE_FAILED"
    Download.STATE_REMOVING -> "STATE_REMOVING"
    Download.STATE_RESTARTING -> "STATE_RESTARTING"
    else -> "UNKNOWN($state)"
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

  private class TestDbHelper(
    context: Context
  ): SQLiteOpenHelper(
    context,
    null, // In-mem index (media files still saved on-disk)
    null,
    1
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
