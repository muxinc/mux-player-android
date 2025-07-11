package com.mux.player.media

import android.annotation.SuppressLint
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmUtil
import androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest
import androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import com.mux.player.internal.Logger
import com.mux.player.internal.createLicenseUri
import com.mux.player.internal.createNoLogger
import com.mux.player.internal.executePost
import com.mux.player.internal.getDrmToken
import com.mux.player.internal.getLicenseUrlHost
import com.mux.player.internal.getPlaybackDomain
import com.mux.player.internal.getPlaybackId
import java.io.IOException
import java.util.UUID

@OptIn(UnstableApi::class)
class MuxDrmSessionManagerProvider(
  val drmHttpDataSourceFactory: HttpDataSource.Factory,
  val logger: Logger,
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
      if (currentSessionManager == null || needNewSessionManager(mediaItem)) {
        val sessionManager = createSessionManager(mediaItem)
        this.sessionManager = sessionManager
        this.mediaItem = mediaItem

        return sessionManager
      } else {
        return currentSessionManager
      }
    }
  }

  private fun needNewSessionManager(incomingMediaItem: MediaItem): Boolean {
    return (
        incomingMediaItem != this.mediaItem
            || incomingMediaItem.getDrmToken() != this.mediaItem?.getDrmToken()
            || incomingMediaItem.getPlaybackId() != this.mediaItem?.getPlaybackId()
        )
  }

  private fun createSessionManager(mediaItem: MediaItem): DrmSessionManager {
    logger.i(TAG, "createSessionManager: called with $mediaItem")
    val playbackId = mediaItem.getPlaybackId()
    val drmToken = mediaItem.getDrmToken()

    logger.v(TAG, "createSessionManager: for playbackId $playbackId")
    logger.v(TAG, "createSessionManager: for drm token $drmToken")
    logger.v(TAG, "createSessionManager: for custom video domain ${mediaItem.getPlaybackDomain()}")

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
          logger = logger,
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
  private val logger: Logger = createNoLogger(),
) : MediaDrmCallback {

  companion object {
    const val TAG = "MuxDrmCallback"
  }

  override fun executeProvisionRequest(
    uuid: UUID,
    request: ProvisionRequest
  ): ByteArray {
    val widevine = uuid == C.WIDEVINE_UUID;
    if (!widevine) {
      throw IOException("Mux player does not support scheme: $uuid")
    }

    val url = request.defaultUrl + "&signedRequest=" + Util.fromUtf8Bytes(request.data)
    logger.d(TAG, "executeProvisionRequest: license URI is $url")

    try {
      return DrmUtil.executePost(
        drmHttpDataSourceFactory.createDataSource(),
        url,
        null,
        emptyMap()
      )
    } catch (e: InvalidResponseCodeException) {
      logger.e(TAG, "Provisioning/License Request failed!", e)
      logger.d(TAG, "Failed data spec: ${e.dataSpec}")
      throw e
    } catch (e: HttpDataSourceException) {
      logger.e(TAG, "Provisioning Request failed!", e)
      logger.d(TAG, "Failed data spec: ${e.dataSpec}")
      throw e
    } catch (e: Exception) {
      logger.e(TAG, "Provisioning Request failed!", e)
      throw e
    }
  }

  override fun executeKeyRequest(
    uuid: UUID,
    request: KeyRequest
  ): ByteArray {
    val widevine = uuid == C.WIDEVINE_UUID
    if (!widevine) {
      throw IOException("Mux player does not support scheme: $uuid")
    }

    val url = createLicenseUri(playbackId, drmToken, licenseEndpointHost)
    val headers = mapOf(
      "Content-Type" to listOf("application/octet-stream")
    )
    logger.d(TAG, "Key Request URI is $url")

    try {
      return executePost(
        uri = url,
        headers = headers,
        requestBody = request.data,
        dataSourceFactory = drmHttpDataSourceFactory,
      )
    } catch (e: InvalidResponseCodeException) {
      logger.e(TAG, "key request failed!", e)
      logger.d(TAG, "Failed data spec: ${e.dataSpec}")
      throw e
    } catch (e: HttpDataSourceException) {
      logger.e(TAG, "Key Request failed!", e)
      logger.d(TAG, "Failed data spec: ${e.dataSpec}")
      throw e
    } catch (e: Exception) {
      logger.e(TAG, "KEY Request failed!", e)
      throw e
    }
  }
}
