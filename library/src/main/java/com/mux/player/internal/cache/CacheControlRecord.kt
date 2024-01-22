package com.mux.player.internal.cache

import java.util.regex.Pattern

data class CacheControlRecord(
  val url: String,
  val etag: String?,
)
