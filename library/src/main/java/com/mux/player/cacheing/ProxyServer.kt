package com.mux.player.cacheing

import android.util.Log
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.concurrent.LinkedBlockingDeque

class ProxyServer (val port:Int = 5000): Thread() {

    private val TAG = "ProxyServer"

    private var socketServer: ServerSocket
    private var isRunning = true

    // Store all active connections, ideally there will be only one active connection per server instance
    private val activeConnections: LinkedBlockingDeque<PlayerConnection> = LinkedBlockingDeque<PlayerConnection>()

    init {
        try {
            socketServer = ServerSocket(port)
        } catch (ex:IOException) {
            // TODO log this, also maybe try different port
            throw ex
        }
        start()
    }

    /**
     * Convert CDN url to localhost url that proxy server know how to redirect.
     *
     * @param url, url to convert
     */
    fun encodeUrl(url: URL) : URL {
        var protocolCode = "0~"
        if (url.protocol.startsWith("https", 0)) {
            protocolCode = "1~"
        }
        var cdnPort = ""
        if (url.port > 0) {
            cdnPort = "~" + url.port
        }
        var cdnQuery = ""
        if (url.query != null) {
            cdnQuery = "?" + url.query
        }
        var resultStr = "http://localhost:" + port + "/" + protocolCode + url.host + cdnPort +
                url.path + cdnQuery
        return URL(resultStr)
    }

    /**
     * Convert local url (one that target local proxy) to a CDN url (one that actualy contains video.)
     *
     * @param url, url to convert
     */
    fun decodeUrl(url: URL) : URL {
        var hostDetails = url.path.split("/")[1]
        var hostSegments = hostDetails.split("~")
        var cdnProtocol = "https://"
        if (hostSegments[0].equals("0")) {
            cdnProtocol = "http://"
        }
        var cdnPort = ""
        if (hostSegments.size == 3) {
            cdnPort =  ":" + hostSegments[2]
        }
        var cdnQuery = ""
        if (url.query != null) {
            cdnQuery = "?" + url.query
        }
        var cdnPath = url.path.split(hostDetails + "/")[1]
        var resultStr = cdnProtocol + hostSegments[1] + cdnPort + "/" + cdnPath + cdnQuery
        return URL(resultStr)
    }

    override fun run() {
        while(isRunning) {
            try {
                val clientSocket: Socket = socketServer.accept()
                activeConnections.add(PlayerConnection(clientSocket, this))
            } catch (e: IOException) {
                // TODO handle this
                Log.e(TAG, "thrown while acceptConnection()", e)
            }
        }
    }
}