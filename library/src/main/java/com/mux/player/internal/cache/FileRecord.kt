package com.mux.player.internal.cache

import com.mux.player.cacheing.CacheController
import java.io.File

data class FileRecord(
  val url: String,
  val etag: String,
  val file: File,
  val downloadedAtLocalMillis: Long,
  val cacheMaxAge: Long? = null,
  val cacheAge: Long? = null,
)
