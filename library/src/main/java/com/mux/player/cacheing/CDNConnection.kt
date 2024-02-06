package com.mux.player.cacheing

import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

class CDNConnection(val playerConnection: PlayerConnection, val parent:ProxyServer) {

    val TAG = "||ProxyCDNConnection"

    enum class MediaContextType {
        MANIFEST, SEGMENT, UNKNOWN
    }

    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var socket: Socket? = null
    private var contextType = MediaContextType.UNKNOWN
    private var outputWriter:PrintWriter? = null
    private var cdnUrl:URL? = null


    fun openConnection(url: URL) {
        cdnUrl = url
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
        outputWriter = PrintWriter(
            OutputStreamWriter(
                outputStream!!, StandardCharsets.US_ASCII
            ), true
        )
    }

    fun send(httpParser:HttpParser) {
        Log.i(TAG, "SENDING_TO_CDN>>\n" + httpParser.getRequestString())
        httpParser.serializeRequest(outputStream!!)
    }

    fun send(chunk:String) {
        if (chunk.length > 0) {
            outputWriter!!.write(chunk)
            outputWriter!!.flush()
        }
    }

    fun processResponse() {
        val httpParser = HttpParser(socket!!.getInputStream())
        httpParser.parseResponse(readBody = false)
        determineContentType(httpParser)

        Log.i(TAG, "RESPONSE_FROM_CDN>>\n" + httpParser.getResponseeString())

      val writeHandle = CacheController.downloadStarted(
        requestUrl = cdnUrl!!.toString(),
        responseHeaders = httpParser.headers.mapValues{ listOf(it.value) },
        playerOutputStream = playerConnection.getStreamToPlayer()
      )

        if (httpParser.statusCode in 300..399) {
            // This is a redirect, find location header
            var redirectLocation:String = httpParser.getHeader("location")
            if (redirectLocation.length == 0) {
                throw HttpFormatException(
                    "Http response of status: " + httpParser.statusCode +
                            " is missing location header"
                )
            }
            // TODO: rewrite the location url and send to player
            //copyToPlayer(httpParser)
            consumeIntoHandle(httpParser.input, writeHandle)
        }
        else if (httpParser.statusCode < 200 || httpParser.statusCode >= 400 ) {
            //copyToPlayer(httpParser)
          consumeIntoHandle(httpParser.input, writeHandle)
        } else {
//            if (contextType == MediaContextType.MANIFEST) {
//                val rewrittenManifest = rewriteManifest(httpParser)
//                httpParser.body = rewrittenManifest.toByteArray(Charsets.ISO_8859_1)
//                httpParser.setHeader("Content-Length", httpParser.body!!.size.toString())
//                copyToPlayer(httpParser)
//            }
//            else {
//                 This is segment
//                copyToPlayer(httpParser)
//            }
          consumeIntoHandle(httpParser.input, writeHandle)
        }
    }

    private val READ_SIZE = 64 * 1024

    private fun consumeIntoHandle(
      externalInput: InputStream,
      writeHandle: CacheController.WriteHandle
    ) {
      val readBuf = ByteArray(READ_SIZE)

      while (true) {
        val readBytes = externalInput.read(readBuf)
        if (readBytes == -1) {
          // done
          break
        } else {
          writeHandle.write(readBuf, 0, readBytes)
        }
      }
      writeHandle.finishedWriting()
    }

//    private fun copyToPlayer(httpParser:HttpParser) {
//        Log.i(TAG, "SENDING_TO_PLAYER>>\n" + httpParser.getResponseeString())
//        var response = httpParser.responseLine + "\r\n"
//        for(header:String in httpParser.headers.keys) {
//            response += header + ": "  + httpParser.headers.get(header) + "\r\n"
//        }
//        response += "\r\n"
//        var responseChunkSize = response.length
//        if (httpParser.body != null) {
//            responseChunkSize = response.length + httpParser.body!!.size
//        }
//        val chunk = ByteArray(responseChunkSize)
//        response.toByteArray(Charsets.ISO_8859_1).copyInto(chunk)
//        if (httpParser.body != null) {
//            httpParser.body!!.copyInto(chunk, response.length, 0, httpParser.body!!.size)
//        }
////        playerConnection.send(chunk)
//        try {
//            while(true) {
//                val chunk = httpParser.readNextChunk()
//                Log.e(TAG, "Sending next binary chunk, size: " + chunk.size)
////                playerConnection.send(chunk)
//            }
//        } catch (ex:IOException) {
//            // Socketr closed.
//            Log.i(TAG, "CDN closed the connection")
//        }
//    }

    fun rewriteManifest(httpParser: HttpParser):String {
        val manifest = StringBuilder()
        val bain = ByteArrayInputStream(httpParser.body!!)
        val reader = BufferedReader(InputStreamReader(bain, Charsets.ISO_8859_1))
        var line = reader.readLine()
        // TODO: check if this line correspond to manifest first line
        var lineIsUrl = false
        while(line != null) {
            if (lineIsUrl) {
                if(line.startsWith("http")) {
                    // This is absolute URL, convert to local url
                    line = parent.encodeUrl(URL(line)).toString()
                } else {
                    // This is relative url, convert to absolute local
                    val pathSegments = cdnUrl!!.path.split("/")
                    val lastSegment = pathSegments[pathSegments.size -1]
                    var absUrlPath = cdnUrl!!.path.replace(lastSegment, line)
                    var absUrlQuery = ""
                    if (cdnUrl!!.query != null && cdnUrl!!.query.isNotEmpty()) {
                        absUrlQuery = "?" + cdnUrl!!.query
                    }
                    var absUrlPort = ""
                    if (cdnUrl!!.port > 0) {
                        absUrlPort = ":" + cdnUrl!!.port
                    }
                    val absPath = cdnUrl!!.protocol + "://" + cdnUrl!!.host + absUrlPort + absUrlPath +
                            absUrlQuery
                    line = parent.encodeUrl(URL(absPath)).toString()
                }
            }
            lineIsUrl = line.startsWith("#EXT-X-STREAM") || line.startsWith("#EXTINF")
            manifest.append("$line\n")
            line = reader.readLine()
        }
        return manifest.toString()
    }

    private fun determineContentType(httpParser:HttpParser) {
        val contentTypeHeader = httpParser.getHeader("Content-Type")
        if (contentTypeHeader.isEmpty()) {
            // TODO read first line of body and see if it is #EXTM3U
        }
        else if (contentTypeHeader.equals("application/vnd.apple.mpegurl", true)
            || contentTypeHeader.equals("audio/mpegurl", true)
            || contentTypeHeader.equals("application/mpegurl", true)
            || contentTypeHeader.equals("application/x-mpegurl", true)
            || contentTypeHeader.equals("audio/x-mpegurl", true)
            ) {
            contextType = MediaContextType.MANIFEST
        } else {
            contextType = MediaContextType.SEGMENT
        }
    }
}