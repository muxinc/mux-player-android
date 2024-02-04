package com.mux.player.cacheing

import android.util.Log
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.BlockingDeque
import java.util.concurrent.LinkedBlockingDeque

class PlayerConnection(val socket: Socket, val parent:ProxyServer) {

    val TAG:String = "||ProxyPlayerConnection"

    private var httpParser: HttpParser? = null
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
            httpParser = HttpParser(socket.getInputStream())
            readThread.start()
            writeThread.start()
        } catch (ex:Exception) {
            ex.printStackTrace()
        }
    }

    fun send(chunk:ByteArray) {
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

            // todo - query if we should use cdn connection
            // todo - handle 'cache holes'

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
      val writer = //PrintWriter(
        OutputStreamWriter(
          socket.getOutputStream(), StandardCharsets.US_ASCII
        )//, true
      //)
      val outputStream = socket.getOutputStream()
      while(running) {
        val chunk = cdnInputQueue.takeFirst()
        Log.w(TAG, "writing chunk:\n" + chunk.contentToString())
        outputStream.write(chunk)
//        writer.write(chunk)
        // todo - Write to the cache too.
      }
    }
}