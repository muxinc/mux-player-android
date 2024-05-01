package com.mux.player.media

import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest
import androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import com.mux.player.internal.Constants
import com.mux.player.internal.createLicenseUri
import com.mux.player.internal.executePost
import com.mux.player.internal.getDrmToken
import com.mux.player.internal.getLicenseUrlHost
import com.mux.player.internal.getPlaybackDomain
import com.mux.player.internal.getPlaybackId
import com.mux.player.media.MediaItems.MUX_VIDEO_DEFAULT_DOMAIN
import java.io.IOException
import java.util.UUID

@OptIn(UnstableApi::class)
class MuxDrmSessionManagerProvider(
  val drmHttpDataSourceFactory: HttpDataSource.Factory,
) : DrmSessionManagerProvider {

  companion object {
    private const val TAG = "DrmSessionManagerProv"
  }

  private val lock = Any()
  // NOTE - Guarded by `lock`
  private var mediaItem: MediaItem? = null
  // NOTE - Guarded by `lock`
  private var sessionManager: DrmSessionManager? = null

  override fun get(mediaItem: MediaItem): DrmSessionManager {
    synchronized(lock) {
      val currentSessionManager = sessionManager
      if (currentSessionManager != null && this.mediaItem == mediaItem) {
        return currentSessionManager
      } else {
        val sessionManager = createSessionManager(mediaItem)
        this.sessionManager = sessionManager
        this.mediaItem = mediaItem

        return sessionManager
      }
    }
  }

  private fun createSessionManager(mediaItem: MediaItem): DrmSessionManager {
    Log.i(TAG, "createSessionManager: called with $mediaItem")
    val playbackId = mediaItem.getPlaybackId()
    val drmToken = mediaItem.getDrmToken()

    Log.v(TAG, "createSessionManager: for playbackId $playbackId")
    Log.v(TAG, "createSessionManager: for drm token $drmToken")
    Log.v(TAG, "createSessionManager: for custom video domain ${mediaItem.getPlaybackDomain()}")

    // Mux Video requires both of these for its DRM system
    if (playbackId == null || drmToken == null) {
      return DrmSessionManager.DRM_UNSUPPORTED
    }

    return DefaultDrmSessionManager.Builder()
      .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
      .setMultiSession(false)
      .build(
        MuxDrmCallback(
          drmHttpDataSourceFactory,
          licenseEndpointHost = mediaItem.getLicenseUrlHost(),
          drmToken = drmToken,
          playbackId = playbackId,
        )
      )
  }
}

@OptIn(UnstableApi::class)
class MuxDrmCallback(
  private val drmHttpDataSourceFactory: HttpDataSource.Factory,
  private val licenseEndpointHost: String, // eg, 'license.mux.com' or 'license.custom.abc1234.com'
  private val drmToken: String,
  private val playbackId: String,
) : MediaDrmCallback {

  companion object {
    const val TAG = "MuxDrmCallback"
  }

  override fun executeProvisionRequest(
    uuid: UUID,
    request: ProvisionRequest
  ): ByteArray {
    Log.i(TAG, "executeProvisionRequest: called")
    val uri = createLicenseUri(playbackId, drmToken, licenseEndpointHost)
    Log.d(TAG, "executeProvisionRequest: license URI is $uri")

    try {
      return executePost(
        uri,
        headers = mapOf(),
        requestBody = request.data,
        dataSourceFactory = drmHttpDataSourceFactory,
      ).also {
        Log.i(TAG, "License Response: ${Base64.encodeToString(it, Base64.NO_WRAP)}")
      }
    } catch (e: InvalidResponseCodeException) {
      Log.e(TAG, "Provisioning/License Request failed!", e)
      Log.d(TAG, "Dumping data spec: ${e.dataSpec}")
      Log.d(TAG, "Error Body Bytes: ${Base64.encodeToString(e.responseBody, Base64.NO_WRAP)}")
      throw e
    } catch (e: HttpDataSourceException) {
      Log.e(TAG, "Provisioning/License Request failed!", e)
      Log.d(TAG, "Dumping data spec: ${e.dataSpec}")
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Provisioning/License Request failed!", e)
      throw e
    }
  }

  override fun executeKeyRequest(
    uuid: UUID,
    request: KeyRequest
  ): ByteArray {
    val widevine = uuid == C.WIDEVINE_UUID;
    if (!widevine) {
      throw IOException("Mux player does not support scheme: $uuid")
    }

    val url = createLicenseUri(playbackId, drmToken, licenseEndpointHost)
    val headers = mapOf(
      Pair("Content-Type", listOf("application/octet-stream")),
    )
    Log.d(TAG, "Key Request URI is $url")

    try {
      return executePost(
        uri = url,
        headers = headers,
        requestBody = request.data,
        dataSourceFactory = drmHttpDataSourceFactory,
      )
    } catch (e: InvalidResponseCodeException) {
      Log.e(TAG, "key request failed!", e)
      throw e
    } catch (e: HttpDataSourceException) {
      Log.e(TAG, "Key Request failed!", e)
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "KEY Request failed!", e)
      throw e
    }
  }
}
