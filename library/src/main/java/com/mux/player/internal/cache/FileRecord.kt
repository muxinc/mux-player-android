package com.mux.player.internal.cache

import java.io.File

data class FileRecord(
  val url: String,
  val etag: String,
  val file: File,
  val downloadedAtUtcSecs: Long,
  val cacheMaxAge: Long,
  val cacheAge: Long,
  val cacheControl: String,
)
