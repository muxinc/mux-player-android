package com.mux.player.offline

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.DefaultDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.WritableDownloadIndex
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Provides objects for Mux's offline download functionality. Holds a DownloadIndex, DatabaseProvider,
 * DownloadManager, Cache, etc
 *
 * This store deliberately does not expose a CacheDataSource.Factory. Downloading and playback want
 * different cache-access configurations, so those factories are created in higher-level components
 * as needed, backed by [downloadCache].
 *
 * You don't need to interact with this class directly unless you are implementing your own
 * DownloadService, but want Mux to manage download storage for you.
 *
 * It's safe to call [get] from any thread, including the main thread.
 */
@OptIn(UnstableApi::class)
abstract class MuxPlayerDownloadStore {

  abstract val downloadManager: DownloadManager
  abstract val downloadIndex: WritableDownloadIndex
  abstract val downloadCache: Cache
  abstract val ioExecutor: Executor

  companion object {
    private val lock = Any()
    private var impl: MuxPlayerDownloadStore? = null

    fun get(context: Context): MuxPlayerDownloadStore = synchronized(lock) {
      var impl = this.impl
      if (impl == null) {
        impl = DownloadStoreImpl(context.applicationContext)
        this.impl = impl
        return impl
      } else {
        return impl
      }
    }
  }
}

@OptIn(UnstableApi::class)
private class DownloadStoreImpl(
  private val applicationContext: Context
) : MuxPlayerDownloadStore() {

  // Nothing below touches disk, opens a database, or starts a thread until it is first accessed.
  // Constructing the store (and everything in this constructor) is side-effect free.

  private val downloadDirectory: File
    get() = File(applicationContext.noBackupFilesDir, DOWNLOAD_DIR)

  private val databaseProvider: DatabaseProvider by lazy {
    DefaultDatabaseProvider(
      CacheDatabaseHelper(
        context = applicationContext,
        // Co-locate the index database with the downloaded media so all offline data lives under
        // a single directory. The helper creates the directory when the database is first opened.
        databaseFile = File(downloadDirectory, DATABASE_NAME)
      )
    )
  }

  override val ioExecutor: Executor by lazy {
    Executors.newFixedThreadPool(4)
  }

  override val downloadCache: Cache by lazy {
    SimpleCache(
      /*cacheDir=*/ File(downloadDirectory, CACHE_SUBDIR),
      // Downloaded content must never be evicted out from under the user
      /*evictor=*/ NoOpCacheEvictor(),
      /*databaseProvider=*/ databaseProvider,
    )
  }

  override val downloadManager: DownloadManager by lazy {
    DownloadManager(
      /*context=*/ applicationContext,
      /*databaseProvider=*/ databaseProvider,
      /*cache=*/ downloadCache,
      /*upstreamFactory=*/ DefaultHttpDataSource.Factory(),
      /*executor=*/ ioExecutor,
    ).apply {
      // Media3's own default is network-only; establish Mux's default (network + charging). Callers
      // override it with MuxDownloadManager.setRequirements.
      setRequirements(MuxDownloadManager.DEFAULT_REQUIREMENTS)
    }
  }

  // Derived from the DownloadManager so the store and the manager share a single, consistent index.
  // DownloadManager's constructor above always builds a DefaultDownloadIndex, which is writable.
  override val downloadIndex: WritableDownloadIndex by lazy {
    downloadManager.downloadIndex as WritableDownloadIndex
  }

  companion object {
    const val DATABASE_NAME = "mux-offline.db"
    const val DOWNLOAD_DIR = "com.mux.player.offline"
    const val CACHE_SUBDIR = "cache"
  }
}

private class CacheDatabaseHelper(
  context: Context,
  databaseFile: File,
) : SQLiteOpenHelper(
  /*context=*/ context,
  /*name=*/ databaseFile.absolutePath,
  /*factory=*/ null,
  /*version=*/ 1
) {

  init {
    databaseFile.parentFile?.mkdirs()
  }

  override fun onCreate(db: SQLiteDatabase?) {
    // media3 (DownloadManager / SimpleCache) creates its tables as needed
  }

  override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
    // media3 (DownloadManager / SimpleCache) maintains its tables
  }
}
