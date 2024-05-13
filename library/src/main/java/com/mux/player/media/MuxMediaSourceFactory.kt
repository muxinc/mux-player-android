package com.mux.player.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import com.mux.player.internal.Logger
import com.mux.player.internal.createNoLogger

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
    @JvmSynthetic internal fun create(
      ctx: Context,
      dataSourceFactory: DataSource.Factory,
      innerFactory: DefaultMediaSourceFactory = DefaultMediaSourceFactory(ctx),
      logger: Logger,
    ): MuxMediaSourceFactory = MuxMediaSourceFactory(ctx, dataSourceFactory, innerFactory, logger)
  }

  @JvmOverloads
  constructor(
    ctx: Context,
    dataSourceFactory: DataSource.Factory,
    innerFactory: DefaultMediaSourceFactory = DefaultMediaSourceFactory(ctx),
  ) : this (ctx, dataSourceFactory, innerFactory, createNoLogger())

  init {
    // basics
    innerFactory.setCmcdConfigurationFactory(CmcdConfiguration.Factory.DEFAULT)
    innerFactory.setDataSourceFactory(dataSourceFactory)

    // drm
    innerFactory.setDrmSessionManagerProvider(MuxDrmSessionManagerProvider(
      drmHttpDataSourceFactory = DefaultHttpDataSource.Factory()
    ))
  }
}


