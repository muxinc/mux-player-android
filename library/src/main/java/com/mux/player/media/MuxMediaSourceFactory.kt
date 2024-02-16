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
class MuxMediaSourceFactory @JvmOverloads constructor(
  ctx: Context,
  dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(ctx, MuxDataSource.Factory()),
  private val innerFactory: DefaultMediaSourceFactory = DefaultMediaSourceFactory(ctx),
) : MediaSource.Factory by innerFactory {

  init {
    innerFactory.setCmcdConfigurationFactory(CmcdConfiguration.Factory.DEFAULT)
    innerFactory.setDataSourceFactory(dataSourceFactory)
  }
}
