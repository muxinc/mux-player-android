package com.mux.video

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.mux.video.media.MuxMediaSourceFactory

/**
 * An [ExoPlayer] with a few extra APIs for interacting with Mux Video (TODO: link?)
 * This player also integrates transparently with Mux Data (TODO: link?)
 */
class MuxExoPlayer private constructor(
  private val exoPlayer: ExoPlayer
) : ExoPlayer by exoPlayer {

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
  class Builder(

    /**
     * The [Context] in which you're running your player. Using an `Activity` will provide the most
     * telemetry for Mux Data
     */
    val context: Context,
    private val playerBuilder: ExoPlayer.Builder,
  ) {

    /**
     * Allows you to configure the underlying [ExoPlayer] by adding your own [ExoPlayer.Builder]
     * parameters to it. Note that some of your configuration may be overwritten
     *
     * Calling [build] on this [ExoPlayer.Builder] will lead to problems
     *
     * @see MuxMediaSourceFactory
     */
    fun plusExoConfig(block: (ExoPlayer.Builder) -> Unit): Builder {
      block(playerBuilder)
      return this
    }

    /**
     * Creates a new [MuxExoPlayer].
     */
    fun build(): MuxExoPlayer {
      return MuxExoPlayer(playerBuilder.build())
    }

    private fun setDefaults(builder: ExoPlayer.Builder) {
      playerBuilder.setMediaSourceFactory(MuxMediaSourceFactory(context))
    }

    init {
      setDefaults(playerBuilder)
    }
  }
}