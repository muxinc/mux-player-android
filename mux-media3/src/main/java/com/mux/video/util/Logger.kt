package com.mux.video.util

import android.util.Log
import java.lang.Exception

/**
 * An interface for logging events
 */
internal interface Logger {
  fun e(tag: String, message: String, exception: Exception? = null)
  fun w(tag: String, message: String, exception: Exception? = null)
  fun d(tag: String, message: String, exception: Exception? = null)
  fun v(tag: String, message: String, exception: Exception? = null)
  fun i(tag: String, message: String, exception: Exception? = null)
}

/**
 * Creates a new [Logger] that logs to Logcat
 */
internal fun LogcatLogger(): Logger = DeviceLogger()

/**
 * Creates a new [Logger] that doesn't log anything at all
 */
internal fun NoLogger(): Logger = SilentLogger()

internal class SilentLogger: Logger {
  override fun e(tag: String, message: String, exception: Exception?) { }
  override fun w(tag: String, message: String, exception: Exception?) { }
  override fun d(tag: String, message: String, exception: Exception?) { }
  override fun v(tag: String, message: String, exception: Exception?) { }
  override fun i(tag: String, message: String, exception: Exception?) { }

}

internal class DeviceLogger: Logger {
  override fun e(tag: String, message: String, exception: Exception?) {
    Log.e(tag, message, exception)
  }
  override fun w(tag: String, message: String, exception: Exception?) {
    Log.w(tag, message, exception)
  }
  override fun d(tag: String, message: String, exception: Exception?) {
    Log.d(tag, message, exception)
  }
  override fun v(tag: String, message: String, exception: Exception?) {
    Log.v(tag, message, exception)
  }
  override fun i(tag: String, message: String, exception: Exception?) {
    Log.i(tag, message, exception)
  }
}