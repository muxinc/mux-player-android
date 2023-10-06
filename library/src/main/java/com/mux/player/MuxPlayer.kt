package com.mux.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player.Listener
import androidx.media3.exoplayer.ExoPlayer
import com.mux.stats.sdk.core.model.CustomerData
import com.mux.stats.sdk.muxstats.MuxStatsSdkMedia3
import com.mux.stats.sdk.muxstats.monitorWithMuxData
import com.mux.player.internal.LogcatLogger
import com.mux.player.internal.Logger
import com.mux.player.internal.NoLogger
import com.mux.player.media.MuxMediaSourceFactory

/**
 * An [ExoPlayer] with a few extra APIs for interacting with Mux Video (TODO: link?)
 * This player also integrates transparently with Mux Data (TODO: link?)
 */
class MuxPlayer private constructor(
  private val exoPlayer: ExoPlayer,
  private val muxDataKey: String?,
  private val logger: Logger,
  context: Context,
  initialCustomerData: CustomerData,
) : ExoPlayer by exoPlayer {

  private var muxStats: MuxStatsSdkMedia3<ExoPlayer>? = null

  override fun release() {
    muxStats?.release()
    exoPlayer.release()
  }

  init {
    // listen internally before Mux Data gets events, in case we need to handle something before
    // the data SDK sees (like media metadata for new streams during a MediaItem transition, etc)
    exoPlayer.addListener(object : Listener {
      // more listener methods here if required
      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        //muxStats?.videoChange(CustomerVideoData())
      }
    })

    muxStats = exoPlayer.monitorWithMuxData(
      context = context,
      envKey = muxDataKey ?: "", // empty string should infer the key
      customerData = initialCustomerData,
    )
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
        LogcatLogger()
      } else {
        NoLogger()
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

    /**
     * Allows you to configure the underlying [ExoPlayer] by adding your own [ExoPlayer.Builder]
     * parameters to it. Note that some of your configuration may be overwritten
     *
     * Calling `build` on the provided [ExoPlayer.Builder] will result in a crash eventually
     *
     * @see MuxMediaSourceFactory
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun plusExoConfig(block: (ExoPlayer.Builder) -> Unit): Builder {
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
        logger = logger ?: NoLogger(),
        initialCustomerData = customerData,
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
