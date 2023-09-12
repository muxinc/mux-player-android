package com.mux.video

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import java.util.PrimitiveIterator

/**
 * An [ExoPlayer] with a few extra APIs for interacting with Mux Video (TODO: link?)
 * This player also integrates transparently with Mux Data (TODO: link?)
 */
class MuxExoPlayer private constructor(
  private val exoPlayer: ExoPlayer
) : ExoPlayer by exoPlayer {

  /**
   * Builds instances of [MuxExoPlayer]. To configure the underlying [ExoPlayer], you can use
   * [plusExoConfig], and provide a function to update an [ExoPlayer.Builder]. Note that the default
   * [build] method can override some configuration. Use [buildWithoutDefaults] if you don't want
   * that behavior
   *
   * @see build
   * @see buildWithoutDefaults
   */
  class Builder private constructor(
    private val playerBuilder: ExoPlayer.Builder,
    private val context: Context,
  ) {
    constructor(context: Context): this(ExoPlayer.Builder(context), context)

    init {
      setDefaults(playerBuilder)
    }

    /**
     * Allows you to configure the underlying [ExoPlayer] by adding your own [ExoPlayer.Builder]
     * parameters to it. Note that some of your configuration may be overwritten
     *
     * Calling [build] on this [ExoPlayer.Builder] is not allowed
     */
    fun plusExoConfig(block: (ExoPlayer.Builder) -> Unit): Builder {
      block(playerBuilder)
      return this
    }

    /**
     * Creates a new [MuxExoPlayer].
     */
    fun build(): MuxExoPlayer {
      setDefaults(playerBuilder)
      return MuxExoPlayer(playerBuilder.build())
    }

    /**
     * Creates a new [MuxExoPlayer] without overwriting any configuration made using [plusExoConfig]
     *
     * When used this way, not all features of [MuxExoPlayer] may be available.
     */
    fun buildWithoutDefaults(): MuxExoPlayer {
      return MuxExoPlayer(playerBuilder.build())
    }

    private fun setDefaults(builder: ExoPlayer.Builder) {
      playerBuilder.setMediaSourceFactory(
        DefaultMediaSourceFactory(context).apply {
          setCmcdConfigurationFactory(CmcdConfiguration.Factory.DEFAULT)
        }
      )
      // TODO: Other stuff?
    }
  }
}