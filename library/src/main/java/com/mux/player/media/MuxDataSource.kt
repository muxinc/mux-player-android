package com.mux.player.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.mux.player.cacheing.CacheConstants

@OptIn(UnstableApi::class)
class MuxDataSource private constructor(
 val upstreamSrc: HttpDataSource
) : DataSource {

  /**
   * Creates a new instance of [MuxDataSource]. The upstream data source will be invoked for any
   * data that Mux's cache cannot provide
   */
  class Factory(
    private val upstream: HttpDataSource.Factory = DefaultHttpDataSource.Factory()
  ) : DataSource.Factory {
    override fun createDataSource(): DataSource {
      return MuxDataSource(upstream.createDataSource())
    }
  }

  init {
    // todo - only once, must start the proxy.
    //  ideally, have CacheController do it when a MuxPlayer is made and then stop it when the last
    //  is released (same as with closing the SqliteOpenHelper)

  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
    upstreamSrc.read(buffer, offset, length)

  override fun addTransferListener(transferListener: TransferListener) =
    upstreamSrc.addTransferListener(transferListener)

  override fun open(dataSpec: DataSpec): Long {
    // todo - hey, do we need the app to enable plaintext http for the proxy to work?
    //  maybe some companies already would for ads or something, but some people won't want to
    val proxyUri = dataSpec.uri.run {
      val replaceScheme = if (scheme.equals("https")) "1~" else "0~"
      val replacePath = "$replaceScheme${host}${path}"

      buildUpon()
        .encodedAuthority("localhost:${CacheConstants.PROXY_PORT}")
        .scheme("http")
        .path(replacePath)
        .build()
    }
    return upstreamSrc.open(dataSpec.withUri(proxyUri))
  }

  override fun getUri(): Uri? =
    upstreamSrc.uri

  override fun close() =
    upstreamSrc.close()
}