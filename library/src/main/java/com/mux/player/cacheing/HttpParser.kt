package com.mux.player.cacheing

import android.util.Log
import androidx.browser.trusted.sharing.ShareTarget.EncodingType
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.StringReader
import java.lang.NumberFormatException
import java.lang.StringBuilder
import java.net.URI
import java.nio.ByteBuffer
import java.util.Hashtable

class HttpParser(val input:InputStream) {

    val TAG:String = "Proxy||HttpParser"

    var requestLine: String = ""
    var responseLine: String = ""
    val headers: Hashtable<String, String> = Hashtable<String, String>()
    var body:ByteBuffer? = null
    var method = ""
    var httpVersion = "HTTP/1.1"
    var statusCode = -1
    var path:String = "/"
      set(value) {
        field = value
        updateRequestLine()
    }
    var query = ""
        set(value) {
            field = value
            updateRequestLine()
        }
    private val bufferSize = 12000
    val readBuffer = ByteArray(bufferSize)

//    private val reader:BufferedReader = BufferedReader(InputStreamReader(input))
    private val running:Boolean = true;

    fun parseRequest() {
        var reader = getReader()
        var line = reader.readLine()
        requestLine = line
        if (requestLine == null) {
            throw HttpFormatException("Bad protocol, request line empty")
        }
        method = requestLine.split(" ")[0]
        path = requestLine.split(" ")[1]
        if (path.contains("?")) {
            query = path.split("?")[1]
            path = path.split("?")[0]
        }
        httpVersion = requestLine.substringAfterLast(" ")
        line = reader.readLine()
        while (line.isNotEmpty()) {
            appendHeaderParameter(line)
            line = reader.readLine()
            if (line == null) {
                // Read more data from socket
                reader = getReader()
                line = reader.readLine()
            }
        }
        parseBody(reader)
    }

    fun parseResponse() {
        var reader = getReader()
        var line = reader.readLine()
        responseLine = line
        val statusCodeStr = responseLine.split(" ")[1]
        try {
            statusCode = statusCodeStr.toInt()
        } catch (err: NumberFormatException) {
            throw HttpFormatException("Bad response line format, status code not integer, found: "
                    + statusCodeStr)
        }
        line = reader.readLine()
        while (line.isNotEmpty()) {
            appendHeaderParameter(line)
            line = reader.readLine()
            if (line == null) {
                // Read more data from socket
                reader = getReader()
                line = reader.readLine()
            }
        }
        parseBody(reader)
    }

    private fun updateRequestLine() {
        if (query.isEmpty()) {
            requestLine = "$method $path $httpVersion"
        } else {
            requestLine = "$method $path $httpVersion?$query"
        }
    }

    private fun parseBody(reader:BufferedReader) {
        if(!parseBodyByContentLength(reader)) {
            // copy whatever is remaining in the reader to the body
            val tmpBuff = ByteArray(bufferSize)
            var bytesRead = 0
            var line = reader.readLine()
            while (line != null) {
                if (line.length + bytesRead > bufferSize) {
                    Log.e(TAG, "This should never happend")
                }
                line.toByteArray().copyInto(tmpBuff, bytesRead, line.length)
                bytesRead += line.length
                line = reader.readLine()
            }
            body = ByteBuffer.wrap(tmpBuff.copyOfRange(0, bytesRead))
        }
    }

    private fun parseBodyByContentLength(reader:BufferedReader? = null): Boolean {
        var contentLength = 0
        try {
            contentLength = getHeader("Content-Length").toInt()
        } catch (err:NumberFormatException) {
            return false
        }
        body = ByteBuffer.allocateDirect(contentLength)
        // copy remaining data from reader to body if there is some
        var bytesRead = 0
        if (reader != null) {
            var line = reader!!.readLine()
            while (line != null) {
                line.toByteArray().copyInto(body!!.array(), bytesRead, 0, line.length)
                bytesRead += line.length
                body!!.array()[bytesRead] = '\n'.code.toByte()
                bytesRead++
                line = reader!!.readLine()
            }
        }
        while(bytesRead < contentLength) {
            // See if this is a blocking mode
            val read = input.read(body!!.array(), bytesRead, contentLength - bytesRead)
            if (read == -1) {
                // Maybe break in this case
                Thread.sleep(50)
            } else {
                bytesRead += read
            }
        }
        return true
    }

    fun readNextChunk():ByteArray {
        var bytesRead = input.read(readBuffer)
        while(bytesRead == 0) {
            Thread.sleep(50)
            bytesRead = input.read(readBuffer)
        }
        return readBuffer.copyOfRange(0, bytesRead)
    }

    fun setHeader(name:String, value:String) {
        for(header_name in headers.keys()) {
            if(header_name.equals(name, true)) {
                headers.set(header_name, value)
                return
            }
        }
        headers.set(name, value)
    }

    fun getHeader(name:String): String {
        for(header in headers.keys()) {
            if(header.equals(name, true)) {
                return headers.get(header)!!
            }
        }
        return ""
    }

    fun serializeRequest(out:OutputStream) {
        val response = StringBuilder()
        response.append("$requestLine\r\n")
        for((header:String, value:String) in headers) {
            response.append("$header: $value\r\n")
        }
        response.append("\r\n")
        out.write(response.toString().toByteArray(Charsets.ISO_8859_1))
        if (body != null) {
            out.write(body!!.array(), 0, body!!.position())
        }
    }

    fun serializeResponse() {

    }

    fun getRequestString(): String {
        val result = StringBuilder()
        result.append(requestLine + "\r\n")
        compileHeadersAndBody(result)
        return result.toString()
    }

    fun getResponseeString(): String {
        val result = StringBuilder()
        result.append(responseLine + "\r\n")
        compileHeadersAndBody(result)
        return result.toString()
    }

    private fun compileHeadersAndBody(builder:StringBuilder) {
        for ((header:String, value:String) in headers) {
            builder.append(header + ": " + value + "\r\n")
        }
        builder.append("\r\n")
        var bodySize = 0
        if (body != null) {
            bodySize = body!!.array().size
        }
        builder.append("Body, size: " + bodySize)
    }

    @Throws(HttpFormatException::class)
    private fun appendHeaderParameter(header: String) {
        val idx = header.indexOf(":")
        if (idx == -1) {
            throw HttpFormatException("Invalid Header Parameter: $header")
        }
        headers.put(header.substring(0, idx), header.substring(idx + 1).trim())
    }

    private fun getReader(): BufferedReader {
        var read = 0
        while(read == 0) {
            read = input.read(readBuffer)
            if (read == 0) {
                Thread.sleep(50)
            }
        }
        val bain = ByteArrayInputStream(readBuffer, 0, read)
        return BufferedReader(InputStreamReader(bain, Charsets.ISO_8859_1))
    }

}