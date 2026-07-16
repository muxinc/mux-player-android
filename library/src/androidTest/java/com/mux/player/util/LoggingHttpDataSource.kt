package com.mux.player.util

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * An [HttpDataSource] that logs every method call to logcat and delegates to [delegate].
 *
 * The delegate's target URL is logged on each call so it's easy to follow what's being fetched.
 * Create instances via [Factory] so the surrounding media3 machinery gets logging sources.
 */
@OptIn(UnstableApi::class)
class LoggingHttpDataSource(
  private val delegate: HttpDataSource,
  private val tag: String = TAG,
  /** When false, this source is a transparent pass-through and logs nothing. */
  var logging: Boolean = true,
) : HttpDataSource {

  // the delegate's current target, updated on open(); handy for logging calls that have no uri arg
  private val targetUrl: Uri? get() = delegate.uri

  private fun log(message: String) {
    if (logging) Log.v(tag, message)
  }

  override fun open(dataSpec: DataSpec): Long {
    log("open(): url=${dataSpec.uri} position=${dataSpec.position} length=${dataSpec.length}")
    return delegate.open(dataSpec).also {
      log("open(): url=${dataSpec.uri} -> bytesToRead=$it")
    }
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    return delegate.read(buffer, offset, length).also { read ->
      log("read(): url=$targetUrl requested=$length -> read=$read")
    }
  }

  override fun close() {
    log("close(): url=$targetUrl")
    delegate.close()
  }

  override fun addTransferListener(transferListener: TransferListener) {
    log("addTransferListener(): $transferListener")
    delegate.addTransferListener(transferListener)
  }

  override fun getUri(): Uri? {
    return delegate.uri.also { log("getUri(): $it") }
  }

  override fun getResponseCode(): Int {
    return delegate.responseCode.also { log("getResponseCode(): url=$targetUrl -> $it") }
  }

  override fun getResponseHeaders(): Map<String, List<String>> {
    return delegate.responseHeaders.also {
      log("getResponseHeaders(): url=$targetUrl -> ${it.size} header(s)")
    }
  }

  override fun setRequestProperty(name: String, value: String) {
    log("setRequestProperty(): $name=$value")
    delegate.setRequestProperty(name, value)
  }

  override fun clearRequestProperty(name: String) {
    log("clearRequestProperty(): $name")
    delegate.clearRequestProperty(name)
  }

  override fun clearAllRequestProperties() {
    log("clearAllRequestProperties()")
    delegate.clearAllRequestProperties()
  }

  /**
   * Wraps the [HttpDataSource]s produced by [delegateFactory] in a [LoggingHttpDataSource].
   */
  @OptIn(UnstableApi::class)
  class Factory(
    private val delegateFactory: HttpDataSource.Factory,
    private val tag: String = TAG,
    /** When false, created sources are transparent pass-throughs that log nothing. */
    var logging: Boolean = true,
  ) : HttpDataSource.Factory {

    override fun createDataSource(): HttpDataSource {
      if (logging) Log.v(tag, "Factory.createDataSource()")
      return LoggingHttpDataSource(delegateFactory.createDataSource(), tag, logging)
    }

    override fun setDefaultRequestProperties(
      defaultRequestProperties: Map<String, String>
    ): HttpDataSource.Factory {
      delegateFactory.setDefaultRequestProperties(defaultRequestProperties)
      return this
    }
  }

  companion object {
    const val TAG = "LoggingHttpDataSource"
  }
}