package com.mux.player.internal.cache

import java.io.File

data class FileRecord(
  val url: String,
  val etag: String,
  val filePath: String,
)
