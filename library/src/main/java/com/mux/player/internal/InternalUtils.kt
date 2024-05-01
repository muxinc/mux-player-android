package com.mux.player.internal

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import com.mux.player.media.MediaItems.MUX_VIDEO_DEFAULT_DOMAIN
import com.mux.player.media.MuxDrmCallback.Companion.TAG
import com.mux.player.media.MuxDrmSessionManagerProvider
import com.mux.player.media.MuxDrmSessionManagerProvider.Companion
import kotlin.jvm.Throws

@Throws
@JvmSynthetic
@OptIn(UnstableApi::class)
internal fun executePost(
  uri: Uri,
  headers: Map<String, List<String>>,
  requestBody: ByteArray,
  dataSourceFactory: DataSource.Factory,
  ): ByteArray {
  val dataSpec = DataSpec.Builder()
    .setUri(uri)
    .setHttpRequestHeaders(headers.mapValues { it.value.last() })
    .setHttpBody(requestBody)
    .build()

  val dataSource = dataSourceFactory.createDataSource()
  return try {
    dataSource.open(dataSpec)
    DataSourceInputStream(dataSource, dataSpec).use { bodyInputStream ->
      bodyInputStream.readBytes()
    }
  } finally {
    runCatching { dataSource.close() }
  }
}

@JvmSynthetic internal fun MediaItem.getPlaybackId(): String? {
  return requestMetadata.extras?.getString(Constants.BUNDLE_PLAYBACK_ID, null)
}

@JvmSynthetic internal fun MediaItem.getDrmToken(): String? {
  return requestMetadata.extras?.getString(Constants.BUNDLE_DRM_TOKEN, null)
}

@JvmSynthetic internal fun MediaItem.getPlaybackDomain(): String {
  return requestMetadata.extras?.getString(
    Constants.BUNDLE_PLAYBACK_DOMAIN,
    MUX_VIDEO_DEFAULT_DOMAIN,
  )!! //!! safe by the contract of getString (ie, a default value is provided)
}

/**
 * @param host The domain for the license server (eg, license.mux.com)
 */
@JvmSynthetic internal fun createLicenseUri(
  playbackId: String,
  drmToken: String,
  host: String,
): Uri {
  val uriPath = "https://$host/license/widevine/$playbackId"
  val provisionUri = Uri.Builder()
    .encodedPath(uriPath)
    .appendQueryParameter("token", drmToken)
    .build()
  Log.d(TAG, "built provision uri: $provisionUri")
  return provisionUri
}

@JvmSynthetic internal fun MediaItem.getLicenseUrlHost(): String {
  val customMuxVideoDomain = getPlaybackDomain()
  val host = "license.${customMuxVideoDomain}"
  Log.w("MUX", "license domain should be: $host")
  // todo - this if-statement should not make it to prod, will eventually break drm against staging
  return if (customMuxVideoDomain == "staging.mux.com") {
    "license.gcp-us-west1-vos1.staging.mux.com"
  } else {
    host
  }
  //return host
}
