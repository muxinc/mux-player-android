package com.mux.video

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player.Listener
import androidx.media3.exoplayer.ExoPlayer
import com.mux.stats.sdk.core.model.CustomerData
import com.mux.stats.sdk.core.model.CustomerVideoData
import com.mux.stats.sdk.muxstats.MuxStatsSdkMedia3
import com.mux.stats.sdk.muxstats.monitorWithMuxData
import com.mux.video.media.MuxMediaSourceFactory

/**
 * An [ExoPlayer] with a few extra APIs for interacting with Mux Video (TODO: link?)
 * This player also integrates transparently with Mux Data (TODO: link?)
 */
class MuxExoPlayer private constructor(
  private val exoPlayer: ExoPlayer,
  private val muxDataKey: String,
  context: Context
) : ExoPlayer by exoPlayer {

  private var muxStats: MuxStatsSdkMedia3<ExoPlayer>? = null

  override fun release() {
    muxStats?.release()
    exoPlayer.release()
  }

  init {
    muxStats = exoPlayer.monitorWithMuxData(
      context = context,
      envKey = muxDataKey,
      customerData = CustomerData()
    )
    exoPlayer.addListener(object : Listener {
      // more listener methods here if required
      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // TODO: When we're associating CustomerVideoDatas with MediaItems, add the right data here
        muxStats?.videoChange(CustomerVideoData())
      }
    })
  }

  /**
   * Builds instances of [MuxExoPlayer]. To configure the underlying [ExoPlayer], you can use
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

    private var dataEnvKey: String = ""

    constructor(context: Context): this(context, ExoPlayer.Builder(context))

    /**
     * Sets a custom Mux Data Env Key for this player. If you're playing videos hosted on Mux Video,
     * this can be inferred to be the env key associated with the video asset's org and environment.
     */
    fun setMuxDataEnv(envKey: String): Builder {
      dataEnvKey = envKey
      return this
    }

    /**
     * Allows you to configure the underlying [ExoPlayer] by adding your own [ExoPlayer.Builder]
     * parameters to it. Note that some of your configuration may be overwritten
     *
     * TODO: Usage
     * TODO: We probably only need one of [plusExoConfig] or [applyExoConfig]
     *
     * Calling `build` on the provided [ExoPlayer.Builder] will result in a crash eventually
     *
     * @see MuxMediaSourceFactory
     */
    fun plusExoConfig(block: (ExoPlayer.Builder) -> Unit): Builder {
      block(playerBuilder)
      return this
    }

    /**
     * Allows you to configure the underlying [ExoPlayer] by adding your own [ExoPlayer.Builder]
     * parameters to it. Note that some of your configuration may be overwritten
     *
     * TODO: Usage
     * TODO: We probably only need one of [plusExoConfig] or [applyExoConfig]
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
     * Creates a new [MuxExoPlayer].
     */
    fun build(): MuxExoPlayer {
      return MuxExoPlayer(
        context = context,
        exoPlayer = this.playerBuilder.build(),
        muxDataKey = this.dataEnvKey,
      )
    }

    private fun setDefaults(builder: ExoPlayer.Builder) {
      playerBuilder.setMediaSourceFactory(MuxMediaSourceFactory(context))
    }

    init {
      setDefaults(playerBuilder)
    }
  }
}
