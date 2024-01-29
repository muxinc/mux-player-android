package com.mux.player.cacheing

import android.util.Log
import java.net.Socket
import java.net.URL
import java.util.concurrent.BlockingDeque
import java.util.concurrent.LinkedBlockingDeque

class PlayerConnection(val socket: Socket) {

    val TAG:String = "||ProxyPlayerConnection"

//    val cdn_url_str = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"
    val cdn_url_str = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism"
//    val cdn_url_str = "http://d3rlna7iyyu8wu.cloudfront.net/skip_armstrong/skip_armstrong_stereo_subs.m3u8"
    val cdn_url:URL = URL(cdn_url_str)

    private var cdnConnection: CDNConnection = CDNConnection(this)
    private val httpParser: HttpParser = HttpParser(socket.getInputStream())
    private var running:Boolean = true
    private var cdnInputQueue: BlockingDeque<ByteArray> = LinkedBlockingDeque()

    private val readThread:Thread = Thread {
        read()
    }
    private val writeThread:Thread = Thread {
        write()
    }

    init{
        cdnConnection.openConnection(cdn_url)
        readThread.start()
        writeThread.start()
    }

    fun send(chunk:ByteArray) {
        Log.e(TAG, "PlayerSend>> size: " + chunk.size + "\n" + chunk)
        cdnInputQueue.put(chunk)
    }

    fun kill() {
        // TODO: kill all threads
        running = false
    }

    /**
     * Read single HTTP request.
     */
    private fun read() {
        try {
            httpParser.parseRequest()
            Log.i(TAG, "FROM_PLAYER>>\n" + httpParser.getRequestString())
            var hostHeaderValue = cdn_url.host
            if (cdn_url.port != -1) {
                hostHeaderValue = cdn_url.host + ":" + cdn_url.port
            }
            httpParser.setHeader("Host", hostHeaderValue)
            httpParser.setHeader("Connection", "close")
            cdnConnection.send(httpParser)
            cdnConnection.processResponse()
        } catch(ex:Exception) {
            Log.e(TAG, "What happend !!!");
            ex.printStackTrace()
        }
    }

    private fun write() {
        val writeHandle = CacheController.downloadStarted(
            requestUrl = cdn_url_str,
            responseHeaders = mapOf(), // todo - real headers
            socket.getOutputStream()
        )
        while(running) {
            val chunk = cdnInputQueue.takeFirst()
            Log.w(TAG, "writing chunk, size:" + chunk.size)
            //writer.write(chunk)
            // todo - how much are we writing here?
            writeHandle.write(chunk)

            // todo - when is EOF?
        }
        // todo - get here somehow.. After we reach the end of the request
        writeHandle.finishedWriting()
    }
}