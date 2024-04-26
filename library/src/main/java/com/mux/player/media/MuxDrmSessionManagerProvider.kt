package com.mux.player.media

import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest
import androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import com.mux.player.internal.Constants
import com.mux.player.internal.executePost
import java.util.UUID

@OptIn(UnstableApi::class)
class MuxDrmSessionManagerProvider(
  val drmHttpDataSourceFactory: HttpDataSource.Factory,
) : DrmSessionManagerProvider {

  companion object {
    private const val TAG = "DrmSessionManagerProv"
  }

  private val lock = Any()
  private var mediaItem: MediaItem? = null
  private var sessionManager: DrmSessionManager? = null

  override fun get(mediaItem: MediaItem): DrmSessionManager {
    synchronized(lock) {
      val currentSessionManager = sessionManager
      // todo - do we need to change for every new media item? or just if drm key is different?
      //  i *think* we want to do make new a session manager for new keys || new playbackIds
      if (currentSessionManager != null && this.mediaItem == mediaItem) {
        return currentSessionManager
      } else {
        return createSessionManager(mediaItem)
      }
    }
  }

  private fun createSessionManager(mediaItem: MediaItem): DrmSessionManager {
    Log.i(TAG, "createSessionManager: called with $mediaItem")
    val playbackId = mediaItem.getPlaybackId()
    val drmToken = mediaItem.getDrmToken()

    // Mux Video requires both of these for its DRM system
    if (playbackId == null || drmToken == null) {
      return DrmSessionManager.DRM_UNSUPPORTED
    }

    return DefaultDrmSessionManager.Builder()
      .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
      .setMultiSession(false)
      //.setPlayClearSamplesWithoutKeys(true) // todo - right?? Well probably not
      .build(
        MuxDrmCallback(
          drmHttpDataSourceFactory,
          playbackDomain = getLicenseUriDomain(mediaItem.localConfiguration!!.uri),
          drmToken = drmToken,
          playbackId = playbackId,
        )
      )
  }

  private fun MediaItem.getPlaybackId(): String? {
    return requestMetadata.extras?.getString(Constants.BUNDLE_PLAYBACK_ID, null)
  }

  private fun MediaItem.getDrmToken(): String? {
    return requestMetadata.extras?.getString(Constants.BUNDLE_DRM_TOKEN, null)
  }

  private fun getLicenseUriDomain(uri: Uri): String {
    return "license.gcp-us-west1-vos1.staging.mux.com"
    //return uri.host!!
  }
}

@OptIn(UnstableApi::class)
class MuxDrmCallback(
  private val drmHttpDataSourceFactory: HttpDataSource.Factory,
  private val playbackDomain: String, // eg, stream.mux.com or a custom domain (abc123.customer.com)
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
    // todo - the request itself has a url too, would it be correct/does it come from the manifest?
    // todo - some headers and stuff required?
    Log.d(TAG, "executeProvisionRequest: Default URL is ${request.defaultUrl}")
    val uri = createLicenseUri(playbackId, drmToken, playbackDomain, request)
    Log.d(TAG, "executeProvisionRequest: license URI is $uri")

    // todo - no need to try{} here unless debugging
    try {
      return executePost(
//        Uri.parse(hardcodedUri),
        uri,
        headers = mapOf(),
        requestBody = request.data,
        dataSourceFactory = drmHttpDataSourceFactory,
      ).also {
        Log.i(TAG, "License Response: ${Base64.encodeToString(it, Base64.NO_WRAP)}")
      }
    } catch(e: InvalidResponseCodeException) {
      Log.e(TAG, "Provisioning/License Request failed!", e)
      Log.d(TAG, "Dumping data spec: ${e.dataSpec}")
      Log.d(TAG, "Error Body Bytes: ${Base64.encodeToString(e.responseBody, Base64.NO_WRAP)}")
      throw e
    } catch(e: HttpDataSourceException) {
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
    // todo - some headers and stuff required?
    // todo - the request itself has a url too, would it be correct to use it?
    Log.d(TAG, "executeKeyRequest: licenseServerUrl is ${request.licenseServerUrl}")
    return executePost(
      uri = createKeyUri(playbackId, drmToken, playbackDomain),
      headers = mapOf(),
      requestBody = request.data,
      dataSourceFactory = drmHttpDataSourceFactory,
    )
  }

  /**
   * @param licenseDomain The domain for the license server (eg, license.mux.com)
   */
  private fun createLicenseUri(
    playbackId: String,
    drmToken: String,
    licenseDomain: String,
    request: ProvisionRequest
  ): Uri {

    // todo - build this url from the licenseDomain, drmToken, and playbackId
    val hardcodedUri = "https://license.gcp-us-west1-vos1.staging.mux.com/license/widevine/UHMpUMz4l00SmDcgAAQPd4Yk01200IDwD4uD7K24GPp01yg?token=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJsIiwiZXhwIjoxNzIyNjE2OTE0LCJraWQiOiJFelE2SkI1ZkQwMmd5TmVxUmE4MDJYT2xnMDE0SzAxckxwdXNDbklRSjJobEtYbyIsInN1YiI6IlVITXBVTXo0bDAwU21EY2dBQVFQZDRZazAxMjAwSUR3RDR1RDdLMjRHUHAwMXlnIn0.tHmqMgHf3pY2adP9QVvx9VIUVZvaxzWZP8Qf4DSUBnT4Zxac-tRPBsHDtBlFIILhmPhjBa2IAmD2PdqgHopSxw_zDp9ktTl6QAKCGgw40ZUKt4GD4aZKubKzAyfPm5q0-7f8aW8oNDbejQ1VjN5QqIBb50ytyPc4NkIzwqJ3P3azrr4TSlo-NiXbXhwWuiMHGqspoNPk8BGBcXpSML7vghlncxwKWYAwbpPaz5q5AEMmN5sqKo7woSVsXBxoe78al6cfT2SRdDR6bu92kMf5zSZ9600boNSjmNn2Dx5IidFAZMYy9qVj22W1T-7rCthmc37c9OcUGK9g0unHEAFE6A"
    if (true) {
      return Uri.parse(hardcodedUri)
    }

    // NOT THE CORRECT URL
    val provisionUri = Uri.parse(request.defaultUrl).buildUpon()
//      .appendQueryParameter("token", drmToken)
      .appendQueryParameter("signedRequest", Util.fromUtf8Bytes(request.data))
      .build()
    if (true) {
      return provisionUri
    }
    Log.d(TAG, "Default URI: $provisionUri")

    return "https://${licenseDomain}/license/widevine/${playbackId}?token=${drmToken}".toUri()
  }

  private fun createKeyUri(playbackId: String, drmToken: String, licenseDomain: String): Uri {
    // todo - assumption that the keys are at key.stream.mux.com (or whatever)
    return "https://${licenseDomain}/license/widevine/${playbackId}?token=${drmToken}".toUri()
  }
}
