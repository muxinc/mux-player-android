package com.mux.player.util

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource

/**
 * An [HttpDataSource] that logs I/O — [open], [read], and [close] — and delegates everything else
 * straight through to [delegate]. The delegate's target URL is logged so it's easy to follow what's
 * being fetched. Create instances via [Factory].
 */
@OptIn(UnstableApi::class)
class LoggingHttpDataSource(
  private val delegate: HttpDataSource,
  private val tag: String = TAG,
  /** When false, this source is a transparent pass-through and logs nothing. */
  var logging: Boolean = true,
) : HttpDataSource by delegate {

  private fun log(message: String) {
    if (logging) Log.v(tag, message)
  }

  override fun open(dataSpec: DataSpec): Long {
    log("open(): url=${dataSpec.uri} position=${dataSpec.position} length=${dataSpec.length}")
    return delegate.open(dataSpec).also { log("open(): url=${dataSpec.uri} -> bytesToRead=$it") }
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
    delegate.read(buffer, offset, length).also { read ->
      log("read(): url=${delegate.uri} requested=$length -> read=$read")
    }

  override fun close() {
    log("close(): url=${delegate.uri}")
    delegate.close()
  }

  /** Wraps the [HttpDataSource]s produced by [delegateFactory] in a [LoggingHttpDataSource]. */
  @OptIn(UnstableApi::class)
  class Factory(
    private val delegateFactory: HttpDataSource.Factory,
    private val tag: String = TAG,
    /** When false, created sources are transparent pass-throughs that log nothing. */
    var logging: Boolean = true,
  ) : HttpDataSource.Factory by delegateFactory {

    override fun createDataSource(): HttpDataSource =
      LoggingHttpDataSource(delegateFactory.createDataSource(), tag, logging)
  }

  companion object {
    const val TAG = "LoggingHttpDataSource"
  }
}