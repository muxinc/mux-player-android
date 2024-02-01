package com.mux.player.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration

/**
 * A [MediaSource.Factory] configured to work best with Mux Video. Prefer to use this over
 * [DefaultMediaSourceFactory] so we can provide our own defaults (turning on CMCD, caching, etc)
 *
 * If you wish to inject your own `DefaultMediaSourceFactory` then its `DataSource.Factory` will be
 * superseded by Mux's custom one. To override that, you can provide your own value for
 * [innerFactory]
 */
@OptIn(UnstableApi::class)
class MuxMediaSourceFactory(
  ctx: Context,
  dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(ctx, MuxDataSource.Factory()),
  private val innerFactory: DefaultMediaSourceFactory = DefaultMediaSourceFactory(ctx),
) : MediaSource.Factory by innerFactory {

  init {
    innerFactory.setCmcdConfigurationFactory(CmcdConfiguration.Factory.DEFAULT)
    innerFactory.setDataSourceFactory(dataSourceFactory)
  }
}
