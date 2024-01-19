package com.mux.player.cacheing

import android.util.Log
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.BlockingDeque
import java.util.concurrent.LinkedBlockingDeque

class PlayerConnection(val socket: Socket) {

    val TAG:String = "PlayerConnection"

    val cdn_url_str = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"
    val cdn_url:URL = URL(cdn_url_str)

    private var cdnConnection: CDNConnection = CDNConnection(this)
    private val httpParser: HttpRequestParser = HttpRequestParser(socket.getInputStream())
    private var running:Boolean = true
    private var cdnInputQueue: BlockingDeque<String> = LinkedBlockingDeque()

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

    fun send(chunk:String) {
        cdnInputQueue.put(chunk)
    }

    fun kill() {
        // TODO: kill all threads
        running = false
    }

    private fun read() {
        while(running) {
            try {
                if (httpParser.parseHttpRequest()) {
                    var cdnRequest: String = ""
                    cdnRequest += httpParser.method + " " + cdn_url_str + " " + httpParser.httpVersion + "\r\n"
                    cdnRequest += "Connection: close\r\n"
                    cdnRequest += "Host: " + cdn_url.host + ":" + cdn_url.port + "\r\n"
                    for ((header: String, value: String) in httpParser.requestHeaders) {
                        if (header.equals("Host", true)) {
                            continue
                        }
                        if (header.equals("Connection", true)) {
                            continue
                        }
                        cdnRequest += header + ": " + value + "\r\n"
                    }
                    cdnRequest += "\r\n"
                    cdnConnection.send(cdnRequest)
                    cdnConnection.send(httpParser.body.toString())
                } else {
                    Thread.sleep(50)
                }
            } catch(ex:Exception) {
                Log.e(TAG, "What happend !!!");
                ex.printStackTrace()
            }
        }
    }

    private fun write() {
        val writer = PrintWriter(
            OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.US_ASCII
            ), true
        )
        while(running) {
            val chunk = cdnInputQueue.takeFirst()
            Log.w(TAG, "writing chunk:\n" + chunk)
            writer.write(chunk)
        }
    }
}