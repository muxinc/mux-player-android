package com.mux.player.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import com.mux.player.internal.Logger
import com.mux.player.internal.createLogcatLogger
import com.mux.player.internal.createNoLogger
import com.mux.player.internal.getPlaybackId
import com.mux.player.offline.MuxPlayerDownloadStore
import java.io.IOException
import java.util.UUID

/**
 * A [MediaSource.Factory] configured to work best with Mux Video.
 *
 * If possible, you should prefer to allow us to manage the MediaSourceFactory by using the defaults
 * on `MuxPlayer.Builder`. Also prefer to use this over [DefaultMediaSourceFactory] so we can
 * provide our own defaults (turning on CMCD, caching, etc)
 *
 * We also provide our own `DataSource.Factory`, which is a `DefaultMediaSourceFactory` that also
 * delegates to our disk caching layer. You can override it with the `dataSourceFactory` ctor param,
 * but caching is disabled by default so you don't need to worry about it if you don't want caching
 *
 * If you wish to inject your own `DefaultMediaSourceFactory` then its `DataSource.Factory` will be
 * superseded by Mux's custom one. To override that, you can provide your own value for
 * [innerFactory]
 */
@OptIn(UnstableApi::class)
class MuxMediaSourceFactory private constructor(
  ctx: Context,
  dataSourceFactory: DataSource.Factory,
  private val innerFactory: DefaultMediaSourceFactory = DefaultMediaSourceFactory(ctx),
  private val logger: Logger,
) : MediaSource.Factory by innerFactory {

  companion object {
    @JvmSynthetic
    internal fun create(
      ctx: Context,
      dataSourceFactory: DataSource.Factory,
      innerFactory: DefaultMediaSourceFactory = DefaultMediaSourceFactory(ctx),
      logger: Logger,
    ): MuxMediaSourceFactory = MuxMediaSourceFactory(ctx, dataSourceFactory, innerFactory, logger)
  }

  private val context = ctx

  @JvmOverloads
  constructor(
    ctx: Context,
    dataSourceFactory: DataSource.Factory,
    innerFactory: DefaultMediaSourceFactory = DefaultMediaSourceFactory(ctx),
  ) : this(ctx, dataSourceFactory, innerFactory, createLogcatLogger())

  override fun createMediaSource(item: MediaItem): MediaSource {
    val localConfig = item.localConfiguration
    val playbackID = item.getPlaybackId()
    return if (
      localConfig != null && playbackID != null
      && localConfig.uri.scheme == MediaItems.URI_SCHEME_MUX_OFFLINE
    ) {
      createOfflineMediaSource(playbackID)
    } else {
      innerFactory.createMediaSource(item)
    }
  }

  private fun createOfflineMediaSource(playbackId: String): MediaSource {
    val store = MuxPlayerDownloadStore.get(context)
    val download = store.downloadIndex.getDownload(playbackId)
    download ?: throw RuntimeException("asset with playbackId $playbackId not downloaded")
    val drm = download.request.keySetId
      ?.let { offlinePlaybackDrm(it) } ?: DrmSessionManager.DRM_UNSUPPORTED

    return DownloadHelper.createMediaSource(
      download.request,
      cacheOnlyDataSourceFactory(store.downloadCache),
      drm
    )
  }

  private fun offlinePlaybackDrm(keySetId: ByteArray): DrmSessionManager =
    DefaultDrmSessionManager.Builder()
      .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
      .build(FailingDrmCallback()).apply {
        setMode(
          DefaultDrmSessionManager.MODE_PLAYBACK,
          keySetId
        )
      }

  private fun cacheOnlyDataSourceFactory(cache: Cache): DataSource.Factory {
    return CacheDataSource.Factory().apply {
      setCache(cache)
      setUpstreamDataSourceFactory(null) // downloaded assets should never need to go online
    }
  }

  init {
    // basics
    innerFactory.setCmcdConfigurationFactory(CmcdConfiguration.Factory.DEFAULT)
    innerFactory.setDataSourceFactory(dataSourceFactory)

    // drm
    innerFactory.setDrmSessionManagerProvider(MuxDrmSessionManagerProvider(
      drmHttpDataSourceFactory = DefaultHttpDataSource.Factory(),
      logger = logger
    ))
  }
}

@OptIn(UnstableApi::class)
private class FailingDrmCallback : MediaDrmCallback {
  override fun executeProvisionRequest(
    uuid: UUID,
    request: ExoMediaDrm.ProvisionRequest
  ): MediaDrmCallback.Response {
    throw IOException("On-disk downloads should never need network")
  }

  override fun executeKeyRequest(
    uuid: UUID,
    request: ExoMediaDrm.KeyRequest
  ): MediaDrmCallback.Response {
    throw IOException("On-disk downloads should never need network")
  }
}
