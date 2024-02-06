package com.mux.player.cacheing

import android.util.Log
import java.io.OutputStream
import java.net.Socket
import java.net.URL
import java.util.concurrent.BlockingDeque
import java.util.concurrent.LinkedBlockingDeque

class PlayerConnection(val socket: Socket, val parent: ProxyServer) {

  val TAG: String = "||ProxyPlayerConnection"

  private var httpParser: HttpParser? = null
  private var running: Boolean = true
  private var cdnInputQueue: BlockingDeque<ByteArray> = LinkedBlockingDeque()

  private val readThread: Thread = Thread {
    read()
  }
  private val writeThread: Thread = Thread {
    write()
  }

  init {
    try {
      httpParser = HttpParser(socket.getInputStream())
      readThread.start()
      //writeThread.start()
    } catch (ex: Exception) {
      ex.printStackTrace()
    }
  }

  fun getStreamToPlayer(): OutputStream = socket.getOutputStream()

//    fun send(chunk:ByteArray) {
//        cdnInputQueue.put(chunk)
//    }

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

      // todo - read from cache here
      val readHandle = CacheController.tryRead(cdnUrl.toString())
      if (readHandle == null) {
        val cdnConnection = CDNConnection(this, parent)
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
      } else {
        readHandle.use { it.readAllInto(getStreamToPlayer()) }
      }
    } catch (ex: Exception) {
      Log.e(TAG, "What happend !!!", ex);
    }
  }


  private fun write() {
    // todo - write thread not necessary, we are writing from the CDNConnection using the stream
//        val writeHandle = CacheController.downloadStarted(
//            requestUrl = "How to get this",
//            responseHeaders = mapOf(), // todo - real headers
//            socket.getOutputStream()
//        )
//        try {
//            while (running) {
//                val chunk = cdnInputQueue.takeFirst()
//                // todo - how much are we writing here?
//                writeHandle.write(chunk)
//
//                // todo - when is EOF?
//            }
//            // todo - get here somehow.. After we reach the end of the request
//            writeHandle.finishedWriting()
//        } catch(ex:SocketException) {
//            Log.i(TAG, "Player closed the connection")
//        }
  }
}