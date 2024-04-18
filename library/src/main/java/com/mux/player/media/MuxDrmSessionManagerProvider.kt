package com.mux.player.media

import androidx.annotation.GuardedBy
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.DrmConfiguration
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import java.util.UUID

@OptIn(UnstableApi::class)
class MuxDrmSessionManagerProvider(
  val drmHttpDataSourceFactory: HttpDataSource.Factory,
) : DrmSessionManagerProvider {

  private val sessionManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { createSessionManager() }

  override fun get(mediaItem: MediaItem): DrmSessionManager {
    return sessionManager
  }

  private fun createSessionManager(): DrmSessionManager {
    // todo - configure DRMSessionManager correctly

    // QUESTIONS FOR CHRISTIAN
    //  playClearSamplesWithoutKeys?
    //  force drm sessions for any track types?

    return DefaultDrmSessionManager.Builder()
      .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
      .setMultiSession(false)
      .setPlayClearSamplesWithoutKeys(true) // todo - right??
      .build(MuxDrmCallback(drmHttpDataSourceFactory))
  }
}

@OptIn(UnstableApi::class)
class MuxDrmCallback(
  val drmHttpDataSourceFactory: HttpDataSource.Factory
) : MediaDrmCallback {

  override fun executeProvisionRequest(
    uuid: UUID,
    request: ExoMediaDrm.ProvisionRequest
  ): ByteArray {
    TODO("Not yet implemented")
  }

  override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
    TODO("Not yet implemented")
  }

}
