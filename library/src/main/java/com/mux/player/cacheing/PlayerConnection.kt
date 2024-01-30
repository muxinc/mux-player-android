package com.mux.player.cacheing

import android.util.Log
import java.net.Socket
import java.net.URL
import java.util.concurrent.BlockingDeque
import java.util.concurrent.LinkedBlockingDeque

class PlayerConnection(val socket: Socket, val parent:ProxyServer) {

    val TAG:String = "||ProxyPlayerConnection"

    private val httpParser: HttpParser? = null
    private var running:Boolean = true
    private var cdnInputQueue: BlockingDeque<ByteArray> = LinkedBlockingDeque()

    private val readThread:Thread = Thread {
        read()
    }
    private val writeThread:Thread = Thread {
        write()
    }

    init{
        try {
            HttpParser(socket.getInputStream())
            readThread.start()
            writeThread.start()
        } catch (ex:Exception) {
            ex.printStackTrace()
        }
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
            httpParser!!.parseRequest()
            Log.i(TAG, "FROM_PLAYER>>\n" + httpParser!!.getRequestString())
            var cdnHostHeaderValue = httpParser!!.getHeader("host")
            if (cdnHostHeaderValue.isEmpty()) {
                throw HttpFormatException("Missing Host header in player request")
            }
            val localUrl = "http://" + cdnHostHeaderValue + httpParser!!.path
            var cdnUrl = parent.decodeUrl(URL(localUrl))
            var cdnConnection = CDNConnection(this, parent)
            cdnConnection.openConnection(cdnUrl)

            var hostHeaderValue = cdnUrl.host
            if (cdnUrl.port != -1) {
                hostHeaderValue = cdnUrl.host + ":" + cdnUrl.port
            }
            httpParser!!.setHeader("Host", hostHeaderValue)
            httpParser!!.setHeader("Connection", "close")
            httpParser!!.path = cdnUrl.path
            cdnConnection.send(httpParser!!)
            cdnConnection.processResponse()
        } catch(ex:Exception) {
            Log.e(TAG, "What happend !!!");
            ex.printStackTrace()
        }
    }

    private fun write() {
        val writeHandle = CacheController.downloadStarted(
            requestUrl = "How to get this",
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