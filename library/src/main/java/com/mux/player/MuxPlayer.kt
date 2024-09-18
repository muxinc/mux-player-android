package com.mux.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player.Listener
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.mux.player.internal.cache.CacheController
import com.mux.stats.sdk.core.model.CustomerData
import com.mux.stats.sdk.core.model.CustomerViewData
import com.mux.stats.sdk.muxstats.MuxStatsSdkMedia3
import com.mux.player.internal.createLogcatLogger
import com.mux.player.internal.Logger
import com.mux.player.internal.createNoLogger
import com.mux.player.internal.Constants
import com.mux.player.media.MuxDataSource
import com.mux.player.media.MuxMediaSourceFactory
import com.mux.player.media.MediaItems
import com.mux.stats.sdk.muxstats.ExoPlayerBinding
import com.mux.stats.sdk.muxstats.INetworkRequest
import com.mux.stats.sdk.muxstats.MuxDataSdk
import com.mux.stats.sdk.muxstats.media3.BuildConfig as MuxDataBuildConfig

/**
 * Mux player for native Android. An [ExoPlayer] with a few extra APIs for interacting with
 * Mux Video. This player also integrates transparently with Mux Data when you play Mux Video Assets
 *
 * ### Basic Usage
 * MuxPlayer is almost a direct drop-in replacement for [ExoPlayer]. To create instances of
 * [MuxPlayer], use our [Builder]
 *
 * To play Mux Assets, you can create a MediaItem using [MediaItems.fromMuxPlaybackId], or
 * [MediaItems.builderFromMuxPlaybackId]
 *
 * ### Customizing ExoPlayer
 * The underlying [ExoPlayer.Builder] can be reached using [Builder.applyExoConfig] (java callers
 * can use [Builder.plusExoConfig]). If you need to inject any custom objects into the underlying
 * ExoPlayer, you are able to do so this way. Please note that doing this may interfere with Mux
 * Player's features.
 */
class MuxPlayer private constructor(
  private val exoPlayer: ExoPlayer,
  private val muxDataKey: String?,
  private val logger: Logger,
  private val muxCacheEnabled: Boolean = true,
  private val didAddMonitoringData: Boolean = false,
  context: Context,
  initialCustomerData: CustomerData,
  network: INetworkRequest? = null,
  exoPlayerBinding: ExoPlayerBinding? = null
) : ExoPlayer by exoPlayer {

  private var muxStats: MuxStatsSdkMedia3<ExoPlayer>? = null
  private var released: Boolean = false

  override fun release() {
    // good to release muxStats first, so it doesn't call to the player after release
    muxStats?.release()
    muxStats = null
    // exoPlayer can handle multiple calls itself, not our deal
    exoPlayer.release()

    released = true
  }

  init {
    if (muxCacheEnabled) {
      CacheController.setup(context, null)
      CacheController.onPlayerCreated()
    }

    // listen internally before Mux Data gets events, in case we need to handle something before
    // the data SDK sees (like media metadata for new streams during a MediaItem transition, etc)
    exoPlayer.addListener(object : Listener {
      // more listener methods here if required
      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Check if a DRM token is set, set View Drm Type if it is
        if (mediaItem?.requestMetadata?.extras?.getString(Constants.BUNDLE_DRM_TOKEN) != null && !didAddMonitoringData) {
          val viewData = CustomerViewData()
          // Assumes only widevine DRM playback is supported
          // If playready support is added in future, update to select between widevine and playready
          viewData.viewDrmType = Constants.VIEW_DRM_TYPE_WIDEVINE

          // This doesn't overwrite other keys like view session ID to null
          val customerData = CustomerData()
          customerData.customerViewData = viewData
          muxStats?.updateCustomerData(customerData)
        }
      }
    })

    // init Mux Data
    val muxPlayerDevice = MuxDataSdk.AndroidDevice(
      ctx = context,
      playerVersion = BuildConfig.LIB_VERSION,
      muxPluginName = "mux-media3",
      muxPluginVersion = MuxDataBuildConfig.LIB_VERSION,
      playerSoftware = "mux-player-android"
    )
    if (exoPlayerBinding == null) {
      muxStats = MuxStatsSdkMedia3(
        context = context,
        envKey = muxDataKey ?: "", // empty string should infer the key
        customerData = initialCustomerData,
        player = exoPlayer,
        device = muxPlayerDevice,
        playerBinding = ExoPlayerBinding(),
      )
    } else {
      muxStats = MuxStatsSdkMedia3(
        context = context,
        envKey = muxDataKey ?: "", // empty string should infer the key
        customerData = initialCustomerData,
        player = this,
        playerView = null,
        customOptions = null,
        device = muxPlayerDevice,
        network = network,
        playerBinding = exoPlayerBinding,
      )
    }
  }

  /**
   * Builds instances of [MuxPlayer]. To configure the underlying [ExoPlayer], you can use
   * [plusExoConfig], and provide a function to update an [ExoPlayer.Builder]. Note that configuring
   * or overriding certain objects with [plusExoConfig] may degrade the player's behavior
   *
   * Mux provides some specially-configured media3 factories such as [MuxMediaSourceFactory] that
   * you should prefer to use with this SDK.
   *
   * ### Customizing ExoPlayer
   * If you need to customize the underlying exoplayer, you can use [applyExoConfig]. Note that this
   * may interfere with Mux Player's features. See [MuxMediaSourceFactory] for more details on what
   * we do to configure exoplayer if you are having issues
   *
   * @see build
   *
   * @see MuxMediaSourceFactory
   */
  class Builder private constructor(

    /**
     * The [Context] in which you're running your player. Using an `Activity` will provide the most
     * telemetry for Mux Data
     */
    private val context: Context,
    private val playerBuilder: ExoPlayer.Builder,
  ) {

    private var dataEnvKey: String? = null
    private var enableSmartCache: Boolean = false
    private var logger: Logger? = null
    private var customerData: CustomerData = CustomerData()
    private var didAddMonitoringData: Boolean = false
    private var exoPlayerBinding: ExoPlayerBinding? = null
    private var network: INetworkRequest? = null

    constructor(context: Context) : this(context, ExoPlayer.Builder(context))

    /**
     * Sets a custom Mux Data Env Key for this player. If you're playing videos hosted on Mux Video,
     * this can be inferred to be the env key associated with the video asset's org and environment.
     */
    @Suppress("unused")
    fun setMuxDataEnv(envKey: String): Builder {
      dataEnvKey = envKey
      return this
    }

    /**
     * Enable or disable Mux Player's smart cache for this player.
     *
     * Mux's Smart caching differs from ExoPlayer's SimpleCache in that it can respond to directives
     * from Mux Video's delivery infrastructure to maximize cache efficiency and playback
     * experience.
     *
     * The smart-cache API is experimental and may not yet be suitable for all playback situations.
     * Smart caching should be most effective for short-form content. Support for additional
     * situations and use-cases is planned before 1.0 of Mux Player.
     */
    @Suppress("unused")
    fun enableSmartCache(enable: Boolean): Builder {
      enableSmartCache = enable
      setUpMediaSourceFactory(this.playerBuilder)
      return this
    }

    /**
     * Enables logcat from Mux's custom player components. ExoPlayer's logging cannot be turned off
     */
    @Suppress("unused")
    fun enableLogcat(enableLogcat: Boolean): Builder {
      logger = if (enableLogcat) {
        createLogcatLogger()
      } else {
        createNoLogger()
      }
      return this
    }

    /**
     * Adds the given Mux Data metadata to this player. Previously-added data will not be cleared,
     * but can be overridden with other values
     */
    @Suppress("unused")
    fun addMonitoringData(customerData: CustomerData): Builder {
      this.customerData.update(customerData)
      this.didAddMonitoringData = true
      return this
    }

    @Suppress("unused")
    fun addExoPlayerBinding(binding: ExoPlayerBinding): Builder {
      this.exoPlayerBinding = binding
      return this
    }

    @Suppress("unused")
    fun addNetwork(network: INetworkRequest): Builder {
      this.network = network
      return this
    }

    /**
     * Allows you to configure the underlying [ExoPlayer] by adding your own [ExoPlayer.Builder]
     * parameters to it. Note that some of your configuration may be overwritten
     *
     * Calling `build` on the provided [ExoPlayer.Builder] will result in a crash eventually
     *
     * @see MuxMediaSourceFactory
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun plusExoConfig(plus: PlusExoBuilder): Builder {
      plus.apply(playerBuilder)
      return this
    }

    /**
     * Allows you to configure the underlying [ExoPlayer] by adding your own [ExoPlayer.Builder]
     * parameters to it. Note that some of your configuration may be overwritten
     *
     * Calling `build` in this block will result in a crash eventually, so don't.
     *
     * @see MuxMediaSourceFactory
     */
    @JvmSynthetic // Hide from java, "Thing.() -> Unit" doesn't translate well
    fun applyExoConfig(block: ExoPlayer.Builder.() -> Unit): Builder {
      playerBuilder.block()
      return this
    }

    /**
     * Creates a new [MuxPlayer].
     */
    fun build(): MuxPlayer {
      return MuxPlayer(
        context = context,
        exoPlayer = this.playerBuilder.build(),
        muxDataKey = this.dataEnvKey,
        muxCacheEnabled = enableSmartCache,
        didAddMonitoringData = this.didAddMonitoringData,
        logger = logger ?: createNoLogger(),
        initialCustomerData = customerData,
        network = network,
        exoPlayerBinding = exoPlayerBinding,
      )
    }

    /**
     * Internal function allows adding arbitrary loggers. Good for unit tests where you don't have a
     * real logcat
     */
    @Suppress("unused")
    @JvmSynthetic
    internal fun setLogger(logger: Logger): Builder {
      this.logger = logger
      return this
    }

    private fun setUpMediaSourceFactory(builder: ExoPlayer.Builder) {
      // For now, the only time to use MuxDataSource is when caching is enabled so do this check
      val mediaSourceFactory = if (enableSmartCache) {
        MuxMediaSourceFactory.create(
          ctx = context,
          logger = this.logger ?: createNoLogger(),
          dataSourceFactory = DefaultDataSource.Factory(context, MuxDataSource.Factory()),
        )
      } else {
        MuxMediaSourceFactory.create(
          ctx = context,
          logger = this.logger ?: createNoLogger(),
          dataSourceFactory = DefaultDataSource.Factory(context),
        )
      }
      builder.setMediaSourceFactory(mediaSourceFactory)
    }

    init {
      setUpMediaSourceFactory(playerBuilder)
    }

    /**
     * Use with [plusExoConfig] to configure MuxPlayer's underlying [ExoPlayer]
     */
    fun interface PlusExoBuilder {
      fun apply(builder: ExoPlayer.Builder)
    }
  }
}
