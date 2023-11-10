package com.mux.stats.sdk.muxstats

//import androidx.media3.exoplayer.ExoPlayer
//import com.mux.stats.sdk.muxstats.ExoPlayerBinding
//import com.mux.stats.sdk.muxstats.MuxStateCollector
//
//open class AutomatedtestsExoPlayerBinding(val testListener:TestEventListener) : ExoPlayerBinding() {
//
//    override fun bindPlayer(player: ExoPlayer, collector: MuxStateCollector) {
//        super.bindPlayer(player, collector)
//        testListener.setCollector(collector)
//        player.addAnalyticsListener(testListener)
//    }
//
//    override fun unbindPlayer(player: ExoPlayer, collector: MuxStateCollector) {
//        super.unbindPlayer(player, collector)
//        player.removeAnalyticsListener(testListener)
//    }
//}