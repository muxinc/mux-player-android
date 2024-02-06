package com.mux.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player.Listener
import androidx.media3.exoplayer.ExoPlayer
import com.mux.player.cacheing.CacheController
import com.mux.stats.sdk.core.model.CustomerData
import com.mux.stats.sdk.muxstats.MuxStatsSdkMedia3
import com.mux.player.internal.createLogcatLogger
import com.mux.player.internal.Logger
import com.mux.player.internal.createNoLogger
import com.mux.player.media.MuxMediaSourceFactory
import com.mux.stats.sdk.muxstats.ExoPlayerBinding
import com.mux.stats.sdk.muxstats.INetworkRequest
import com.mux.stats.sdk.muxstats.MuxDataSdk
import com.mux.stats.sdk.muxstats.media3.BuildConfig as MuxDataBuildConfig

/**
 * An [ExoPlayer] with a few extra APIs for interacting with Mux Video (TODO: link?)
 * This player also integrates transparently with Mux Data (TODO: link?)
 */
class MuxPlayer private constructor(
  private val exoPlayer: ExoPlayer,
  private val muxDataKey: String?,
  private val logger: Logger,
  private val muxCacheEnabled: Boolean = false,
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
    // exoPlayer can handle multiple calls itself, not our deal
    exoPlayer.release()

    // our own cleanup should only happen once
    if (!released) {
      if (muxCacheEnabled) {
        CacheController.onPlayerReleased()
      }
    }

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
        //muxStats?.videoChange(CustomerVideoData())
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
    private var optOutOfData: Boolean = false
    private var logger: Logger? = null
    private var customerData: CustomerData = CustomerData()
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
    fun plusExoConfig(block: (ExoPlayer.Builder) -> Void): Builder {
      block(playerBuilder)
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
        logger = logger ?: createNoLogger(),
        initialCustomerData = customerData,
        network = network,
        exoPlayerBinding = exoPlayerBinding
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

    private fun setDefaults(builder: ExoPlayer.Builder) {
      builder.setMediaSourceFactory(MuxMediaSourceFactory(context))
    }

    init {
      setDefaults(playerBuilder)
    }
  }
}
