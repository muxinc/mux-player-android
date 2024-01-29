package com.mux.player.cacheing

import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.BlockingDeque
import java.util.concurrent.LinkedBlockingDeque
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class CDNConnection(val playerConnection: PlayerConnection) {

    val TAG = "||ProxyCDNConnection"

    enum class MediaContextType {
        MANIFEST, SEGMENT, UNKNOWN
    }

    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var socket: Socket? = null
    private var contextType = MediaContextType.UNKNOWN
    private var outputWriter:PrintWriter? = null


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
        httpParser.parseResponse()
        Log.i(TAG, "RESPONSE_FROM_CDN>>\n" + httpParser.getResponseeString())
        determineContentType(httpParser)
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
            copyToPlayer(httpParser)
        }
        else if (httpParser.statusCode < 200 || httpParser.statusCode >= 400 ) {
            Log.i(TAG, "Error")
            copyToPlayer(httpParser)
        } else {
            if (contextType == MediaContextType.MANIFEST) {
                val rewrittenManifest = rewriteManifest(httpParser)
                var response = httpParser.responseLine + "\r\n"
                for(header:String in httpParser.headers.keys) {
                    if (header.equals("Content-Length", true)) {
                        response += header + ": "  + rewrittenManifest.length + "\r\n"
                    } else {
                        response += header + ": "  + httpParser.headers.get(header) + "\r\n"
                    }
                }
                response += "\r\n"
                response += rewrittenManifest
                Log.i(TAG, "SENDING_TO_PLAYER>>\nresponse")
                playerConnection.send(response.toByteArray(Charsets.ISO_8859_1))
            }
            else if(contextType == MediaContextType.SEGMENT) {
                Log.i(TAG, "Serve segment")
                copyToPlayer(httpParser)
            } else {
                // This should not happens
                Log.e(TAG, "Not good")
            }
        }
    }

    private fun copyToPlayer(httpParser:HttpParser) {
        var response = httpParser.responseLine + "\r\n"
        for(header:String in httpParser.headers.keys) {
            response += header + ": "  + httpParser.headers.get(header) + "\r\n"
        }
        response += "\r\n"
        playerConnection.send(response.toByteArray(Charsets.ISO_8859_1))
        if (httpParser.body != null) {
            Log.e(TAG, "Sending to player what we have in body, size: "
                    + httpParser.body!!.array().size)
            playerConnection.send(httpParser.body!!.array())
        }
        try {
            while(true) {
                val chunk = httpParser.readNextChunk()
                Log.e(TAG, "Sending next binary chunk, size: " + chunk.size)
                playerConnection.send(chunk)
            }
        } catch (ex:IOException) {
            // Socketr closed.
            Log.i(TAG, "Socket closed ....")
        }
    }

    private fun rewriteManifest(httpParser: HttpParser):String {
        val manifest = StringBuilder()
        val bain = ByteArrayInputStream(httpParser.body!!.array())
        val reader = BufferedReader(InputStreamReader(bain, Charsets.ISO_8859_1))
        var line = reader.readLine()
        // TODO: check if this line correspond to manifest first line
        while(line != null) {
            // TODO: rewrite each relative or absolute path to local path
            manifest.append(line)
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