package com.mux.player.offline

import android.util.Base64
import org.json.JSONObject

/**
 * Mux's own data about a download, stored as UTF-8 JSON on
 * [androidx.media3.exoplayer.offline.DownloadRequest.data] so it lives in the `DownloadIndex`
 * alongside the download.
 *
 * Downloads written before a field existed won't have it, so parsing is lenient: anything missing
 * or unreadable comes back null instead of throwing.
 */
internal data class ExtraData(
  /**
   * The Widevine PSSH from the stream's `#EXT-X-SESSION-KEY` (or `#EXT-X-KEY`), as it was when the
   * download's license was acquired.
   *
   * Widevine can't always renew an offline license in place, so a renewal may have to request a
   * brand-new one. That needs the PSSH, and by then the media is on disk and its playlists aren't
   * being parsed again.
   */
  val widevinePssh: ByteArray? = null,
) {

  fun toUtf8Bytes(): ByteArray =
    JSONObject()
      .apply { widevinePssh?.let { put(KEY_WIDEVINE_PSSH, Base64.encodeToString(it, BASE64_FLAGS)) } }
      .toString()
      .toByteArray(Charsets.UTF_8)

  // by hand because the generated ones would compare the pssh by reference, and this rides on
  // MuxDownload, which callers may well compare
  override fun equals(other: Any?): Boolean =
    this === other ||
        (other is ExtraData && widevinePssh.contentEquals(other.widevinePssh))

  override fun hashCode(): Int = widevinePssh.contentHashCode()

  companion object {
    private const val KEY_WIDEVINE_PSSH = "widevinePssh"
    private const val BASE64_FLAGS = Base64.NO_WRAP

    /** Reads [ExtraData] back out of [bytes], which may be empty or not ours at all. */
    fun fromUtf8Bytes(bytes: ByteArray): ExtraData {
      if (bytes.isEmpty()) {
        return ExtraData()
      }

      return try {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        ExtraData(
          widevinePssh = json.optString(KEY_WIDEVINE_PSSH)
            .takeIf { it.isNotEmpty() }
            ?.let { Base64.decode(it, BASE64_FLAGS) },
        )
      } catch (e: Exception) {
        ExtraData()
      }
    }
  }
}
