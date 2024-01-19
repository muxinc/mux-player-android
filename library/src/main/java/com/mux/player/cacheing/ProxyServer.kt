package com.mux.player.cacheing

import android.util.Log
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
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

    override fun run() {
        while(isRunning) {
            try {
                val clientSocket: Socket = socketServer.accept()
                activeConnections.add(PlayerConnection(clientSocket))
            } catch (e: IOException) {
                // TODO handle this
                Log.e(TAG, "thrown while acceptConnection()", e)
            }
        }
    }
}