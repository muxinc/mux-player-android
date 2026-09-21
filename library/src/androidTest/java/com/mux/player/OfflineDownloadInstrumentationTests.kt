package com.mux.player

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.DefaultDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
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
import com.mux.player.internal.getDrmToken
import com.mux.player.internal.getLicenseUrlHost
import com.mux.player.internal.getPlaybackId
import com.mux.player.media.MediaItems
import com.mux.player.media.MuxDrmSessionManagerProvider
import com.mux.player.offline.MuxHlsDownloadCallback
import com.mux.player.offline.MuxOfflineCmafHlsMediaSource
import com.mux.player.offline.createMuxHlsDownloadHelper
import com.mux.player.offline.isOfflineLicenseExpired
import com.mux.player.offline.localOfflineLicenseHelper
import com.mux.player.offline.ExtraData
import com.mux.player.offline.acquireOfflineLicense
import com.mux.player.offline.widevineDrmInitData
import com.mux.player.util.LoggingHttpDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
    const val TAG = "OfflineDownloadInstrumentationTests"

    // Add tokens and test locally (for now, maybe for a long time)
    private const val PLAY_TOKEN = ""
    private const val DRM_TOKEN = ""
    private const val DRM_PLAYBACK_ID = "kZcYm801rGBccH7xY8ToJl75hriNkly5KzBigPKiId2E"

    private const val CLEAR_CMAF_PLAYBACK_ID = "5ICwECLW8900gMTi5eaOkWdYvOkGhtKyBY02uRCT6FOyE"
    private const val CLEAR_PLAYBACK_ID = "KyU4B3aJB01jjk00EmZBkp9nRkeaZyTblN3EwmjhIqkcw"
    private const val CLEAR_MULTI_LANG_PLAYBACK_ID = "3x5wDUHxkd8NkEfspLUK3OpSQEJe3pom"

    private const val LOG_MEDIA_REQUESTS = false // warning: excessively verbose
    private const val LOG_DRM_REQUESTS = true
    private const val CACHE_SUBDIR = "test_downloads"
  }

  private lateinit var testDownloadManager: DownloadManager
  private lateinit var dbHelper: TestDbHelper
  private lateinit var testDatabaseProvider: DatabaseProvider
  private lateinit var testCache: SimpleCache
  private lateinit var ioExecutor: Executor

  private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Before
  fun setUp() {
    ensureEmptyCacheDir()

    dbHelper = TestDbHelper(appContext)
    ioExecutor = Executors.newFixedThreadPool(4)
    testDatabaseProvider = DefaultDatabaseProvider(dbHelper)
    testCache = SimpleCache(
      /*cacheDir=*/ File(appContext.filesDir, CACHE_SUBDIR),
      /*evictor=*/ NoOpCacheEvictor(),
      /*databaseProvider=*/ testDatabaseProvider
    )
    testDownloadManager = DownloadManager(
      /*context=*/ appContext,
      /*databaseProvider=*/ testDatabaseProvider,
      /*cache=*/ testCache,
      /*upstreamFactory=*/ LoggingHttpDataSource.Factory(
        DefaultHttpDataSource.Factory(),
        tag = "DownloadHttp",
        logging = LOG_MEDIA_REQUESTS
      ),
      /*executor=*/ ioExecutor
    )
    testDownloadManager.requirements = Requirements(0) // no requirements for starting
  }

  @After
  fun cleanUp() {
    testDownloadManager.release()
    testCache.release()
    dbHelper.close()

    ensureEmptyCacheDir()
  }

  @Test
  fun testCleartextSimple() = runTest {
    val mediaItem = MediaItems.fromMuxPlaybackId(
      playbackId = CLEAR_PLAYBACK_ID
    )
    testMediaItemCase(
      mediaItem,
      expectedAudioTrackIds = listOf("main:audio")
    )
  }

  @Test
  fun testCleartextCmaf() = runTest {
    // we have to test a CMAF asset because DownloadHelper misbehaves while selecting CMAF tracks
    val mediaItem = MediaItems.fromMuxPlaybackId(
      playbackId = CLEAR_CMAF_PLAYBACK_ID,
      assetStartTime = 0.0,
      assetEndTime = 60.0
    )
    testMediaItemCase(
      mediaItem,
      expectedAudioTrackIds = listOf("audio:Default")
    )
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

  @Test
  fun testDrmDownload() = runTest {
    // If there's no DRM/playback tokens this test
    assumeTrue(
      "DRM Token should be provided to do this test",
      DRM_TOKEN.isNotEmpty()
    )
    assumeTrue(
      "Playback Token should be provided to do this test",
      PLAY_TOKEN.isNotEmpty()
    )

    testMediaItemCase(
      mediaItem = MediaItems.fromMuxPlaybackId(
        playbackId = DRM_PLAYBACK_ID,
        playbackToken = PLAY_TOKEN,
        drmToken = DRM_TOKEN,
      ),
      expectedAudioTrackIds = listOf("audio:Default")
    )
  }

  @Test
  fun testDrmLicenseRenewal() = runTest {
    assumeTrue(
      "DRM Token should be provided to do this test",
      DRM_TOKEN.isNotEmpty()
    )
    assumeTrue(
      "Playback Token should be provided to do this test",
      PLAY_TOKEN.isNotEmpty()
    )

    val mediaItem = MediaItems.fromMuxPlaybackId(
      playbackId = DRM_PLAYBACK_ID,
      playbackToken = PLAY_TOKEN,
      drmToken = DRM_TOKEN,
    )
    val drmProvider = createDrmSessionManagerProvider()

    // Acquiring the license is what starting a download does. Renewal starts over from the PSSH the
    // download saved, with no media and no manifests to hand.
    val downloadRequest = prepareDownloadRequest(mediaItem, drmProvider)
    assertNotNull(
      "Downloading a DRM asset should acquire an offline license",
      downloadRequest.keySetId
    )
    val savedPssh = ExtraData.fromUtf8Bytes(downloadRequest.data).widevinePssh
    assertNotNull(
      "Downloading a DRM asset should save the pssh a renewal needs",
      savedPssh
    )

    Log.d(TAG, "testDrmLicenseRenewal(): Acquiring a replacement offline license")
    val renewedKeySetId = withContext(Dispatchers.IO) {
      drmProvider.acquireOfflineLicense(
        playbackId = DRM_PLAYBACK_ID,
        drmToken = DRM_TOKEN,
        licenseEndpointHost = mediaItem.getLicenseUrlHost(),
        drmInitData = widevineDrmInitData(savedPssh!!),
      )
    }

    assertTrue(
      "Renewal should hand back a keySetId the download can play with",
      renewedKeySetId.isNotEmpty()
    )

    // The whole point of renewing: this device's CDM has to consider the license good again
    val licenseHelper = localOfflineLicenseHelper()
    try {
      assertFalse(
        "The renewed offline license should have time left on it",
        licenseHelper.isOfflineLicenseExpired(renewedKeySetId)
      )
    } finally {
      licenseHelper.release()
    }
  }

  private fun createDrmSessionManagerProvider(): MuxDrmSessionManagerProvider =
    MuxDrmSessionManagerProvider(
      drmHttpDataSourceFactory = LoggingHttpDataSource.Factory(
        delegateFactory = DefaultHttpDataSource.Factory(),
        tag = TAG,
        logging = LOG_DRM_REQUESTS,
      ),
      logger = createLogcatLogger()
    )

  /**
   * Prepares [mediaItem] for download the way `MuxDownloadManager.startDownload` does, and returns
   * the resulting request — including, for DRM content, the `keySetId` of a freshly acquired offline
   * license.
   *
   * @param drmProvider the provider that acquires the license. Pass one in when you need the same
   *   provider afterward, eg to renew the license it acquired.
   */
  private suspend fun prepareDownloadRequest(
    mediaItem: MediaItem,
    drmProvider: MuxDrmSessionManagerProvider = createDrmSessionManagerProvider(),
  ): DownloadRequest {
    val fileMediaSource = MuxOfflineCmafHlsMediaSource.create(
      dataSourceFactory = LoggingHttpDataSource.Factory(
        DefaultHttpDataSource.Factory(),
        tag = "MediaSrcHttp",
        logging = LOG_MEDIA_REQUESTS
      ),
      mediaItem = mediaItem,
      drmSessionManagerProvider = drmProvider
    )

    Log.d(TAG, "prepareDownloadRequest(): Preparing Download")
    val preparationComplete = CompletableDeferred<DownloadRequest>()
    val downloadHelper = createMuxHlsDownloadHelper(appContext, fileMediaSource)
    downloadHelper.prepare(
      MuxHlsDownloadCallback(
        fileMediaSource,
        playbackId = mediaItem.getPlaybackId()!!, // MediaItems.fromMuxPlaybackId tested elsewhere
        // DrmSessionManagerProvider/drmToken not touched unless the stream has a pssh (unit-tested)
        drmProvider = drmProvider,
        drmToken = DRM_TOKEN,
        ioExecutor = ioExecutor,
        onReady = { preparationComplete.complete(it) },
        onError = { preparationComplete.completeExceptionally(Exception("Download prep failed", it))
        }
      )
    )

    // Throws here if there was an error selecting tracks
    return preparationComplete.await()
  }

  // Download something using the DownloadHelper and OfflineLicenseHelpers and assert expected tracks
  suspend fun testMediaItemCase(
    mediaItem: MediaItem,
    expectedAudioTrackIds: List<String> = listOf(),
    expectedSubtitleIds: List<String> = listOf()
  ) {
    val downloadRequest = prepareDownloadRequest(mediaItem)

    if (!mediaItem.getDrmToken().isNullOrEmpty()) {
      // assert that we discovered and attached a keyset id before continuinh
      assertNotNull(
        "Should have keySetId associated with DRM token ${mediaItem.getDrmToken()}",
        downloadRequest.keySetId
      )
    }

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
            Log.d(TAG, "downloading:" +
                " ${download.percentDownloaded}% (${download.bytesDownloaded} bytes)")
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

    // prepare an ExoPlayer from the downloaded asset so we can assert against its loaded tracks
    val completedPrepareFromDisk = CompletableDeferred<Tracks>()
    coroutineScope {
      // use ExoPlayer from the main thread (simpler than configuring a new applicationLooper)
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
              releaseOnNextLoop()
            }
            override fun onPlayerError(error: PlaybackException) {
              completedPrepareFromDisk.completeExceptionally(error)
              releaseOnNextLoop()
            }
            private fun releaseOnNextLoop() {
              // gotta release after track selection in the listener but not during the call itself
              launch(Dispatchers.Main) { player.release() }
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
        // !! is either an error or isDirectory returning true for a non-directory. Both are aborts
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
  ) : SQLiteOpenHelper(
    context,
    null, // In-mem index goes away on close (media files still saved on-disk)
    null,
    1
  ) {
    override fun onCreate(p0: SQLiteDatabase?) {
      // SimpleCache creates tables
    }

    override fun onUpgrade(p0: SQLiteDatabase?, p1: Int, p2: Int) {
      // SimpleCache maintains tables
    }

  }
}
