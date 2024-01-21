package com.mux.player.cacheing

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.BlockingDeque
import java.util.concurrent.LinkedBlockingDeque
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class CDNConnection(playerConnection: PlayerConnection) {

    val TAG = "CDNConnection"

    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var socket: Socket? = null
    private var running:Boolean = true
    private var playerInputQueue: BlockingDeque<String> = LinkedBlockingDeque()

    private val readThread:Thread = Thread {
        while(running) {
            val chunk = inputStream!!.readBytes().toString()
          // todo - Read in buffers instead
            if (chunk.length > 0) {
                playerConnection.send(chunk)
            }
            Thread.sleep(50)
        }
    }

    private val writeThread:Thread = Thread {
        val writer = PrintWriter(
            OutputStreamWriter(
                outputStream!!, StandardCharsets.US_ASCII
            ), true
        )
        while(running) {
            val chunk = playerInputQueue.takeFirst()
            Log.w(TAG, "writing chunk:\n" + chunk)
            writer.write(chunk)
        }
    }

    fun openConnection(url: URL) {
        var socketFactory = SocketFactory.getDefault()
        var defaultPort = 80
        if (url.protocol.startsWith("https", true)) {
            socketFactory = SSLSocketFactory.getDefault()
            defaultPort = 443
        }
        if (url.port != -1) {
            socket = socketFactory.createSocket(url.host, url.port)
        } else {
            socket = socketFactory.createSocket(url.host, defaultPort)
        }
        inputStream = socket!!.getInputStream()
        outputStream = socket!!.getOutputStream()
        readThread.start()
        writeThread.start()
    }

    fun send(chunk:String) {
        playerInputQueue.put(chunk)
    }
}