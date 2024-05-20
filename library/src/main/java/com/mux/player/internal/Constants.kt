package com.mux.player.internal

internal object Constants {
  const val MIME_TS = "video/MP2T"
  const val MIME_M4S = "video/mp4"
  const val MIME_M4S_ALT = "video/iso.segment"
  const val EXT_M4S = "m4s"
  const val EXT_TS = "ts"

  /**
   * Can be rooted in cache or files dir on either internal or external storage
   */
  const val CACHE_BASE_DIR = "mux/player"
  /**
   * In the cacheDir
   */
  const val CACHE_FILES_DIR = "$CACHE_BASE_DIR/cache"
  /**
   * In the cacheDir
   */
  const val TEMP_FILE_DIR = "$CACHE_BASE_DIR/cache/tmp"
}
