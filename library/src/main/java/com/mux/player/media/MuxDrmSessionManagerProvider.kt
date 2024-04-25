package com.mux.player.media

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import com.mux.player.internal.Constants
import com.mux.player.internal.executePost
import java.util.UUID

@OptIn(UnstableApi::class)
class MuxDrmSessionManagerProvider(
  val drmHttpDataSourceFactory: HttpDataSource.Factory,
) : DrmSessionManagerProvider {

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
    // todo - resolve the !!s
    return DefaultDrmSessionManager.Builder()
      .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
      .setMultiSession(false)
      //.setPlayClearSamplesWithoutKeys(true) // todo - right?? Well probably not
      .build(
        MuxDrmCallback(
          drmHttpDataSourceFactory,
          domain = getUriDomain(mediaItem.localConfiguration!!.uri),
          drmKey = mediaItem.getDrmKey()!!,
          playbackId = mediaItem.getPlaybackId()!!,
        )
      )
  }

  private fun MediaItem.getPlaybackId(): String? {
    return requestMetadata.extras?.getString(Constants.BUNDLE_PLAYBACK_ID, null)
  }

  private fun MediaItem.getDrmKey(): String? {
    return requestMetadata.extras?.getString(Constants.BUNDLE_DRM_TOKEN, null)
  }

  private fun getUriDomain(uri: Uri): String {
    // todo - so like, license.stream.mux.com??
    return uri.host!!
  }
}

@OptIn(UnstableApi::class)
class MuxDrmCallback(
  private val drmHttpDataSourceFactory: HttpDataSource.Factory,
  private val domain: String,
  private val drmKey: String,
  private val playbackId: String,
) : MediaDrmCallback {

  companion object {
    const val TAG = "MuxDrmCallback"
  }

  override fun executeProvisionRequest(
    uuid: UUID,
    request: ExoMediaDrm.ProvisionRequest
  ): ByteArray {
    // todo - the request itself has a url too, would it be correct/does it come from the manifest?
    // todo - some headers and stuff required?
    Log.d(TAG, "executeProvisionRequest: Default URL is ${request.defaultUrl}")
    return executePost(
      uri = createLicenseUri(playbackId, drmKey, domain),
      headers = mapOf(),
      requestBody = request.data,
      dataSourceFactory = drmHttpDataSourceFactory,
    )
  }

  override fun executeKeyRequest(
    uuid: UUID,
    request: ExoMediaDrm.KeyRequest
  ): ByteArray {
    // todo - some headers and stuff required?
    // todo - the request itself has a url too, would it be correct to use it?
    Log.d(TAG, "executeKeyRequest: license server url is ${request.licenseServerUrl}")
    return executePost(
      uri = createKeyUri(playbackId, drmKey, domain),
      headers = mapOf(),
      requestBody = request.data,
      dataSourceFactory = drmHttpDataSourceFactory,
    )
  }

  /**
   * @param licenseDomain The domain for the license server (eg, license.mux.com)
   */
  private fun createLicenseUri(playbackId: String, drmToken: String, licenseDomain: String): Uri {
    return "https://${licenseDomain}/license/widevine/${playbackId}?token=${drmToken}".toUri()
  }

  private fun createKeyUri(playbackId: String, drmToken: String, licenseDomain: String): Uri {
    // todo - assumption that the keys are at key.stream.mux.com (or whatever)
    return "https://${licenseDomain}/license/widevine/${playbackId}?token=${drmToken}".toUri()
  }
}
