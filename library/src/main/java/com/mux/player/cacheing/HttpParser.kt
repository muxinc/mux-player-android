package com.mux.player.cacheing

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.lang.NumberFormatException
import java.lang.StringBuilder
import java.net.SocketException
import java.util.Hashtable

class HttpParser(val input:InputStream) {

    val TAG:String = "Proxy||HttpParser"

    var requestLine: String = ""
    var responseLine: String = ""
    val headers: Hashtable<String, String> = Hashtable<String, String>()
    var body:ByteArray? = null
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
    private val reader = InputReader(input)


//    private val reader:BufferedReader = BufferedReader(InputStreamReader(input))
    private val running:Boolean = true;

    fun parseRequest() {
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
        }
        parseBody()
    }

    fun parseResponse() {
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
        }
        parseBody()
    }

    private fun updateRequestLine() {
        if (query.isEmpty()) {
            requestLine = "$method $path $httpVersion"
        } else {
            requestLine = "$method $path $httpVersion?$query"
        }
    }

    private fun parseBody() {
        var contentLength = 0
        try {
            contentLength = getHeader("Content-Length").toInt()
        } catch (err:NumberFormatException) {
            Log.e(TAG, "No ContentLengthHeader")
            return
        }
        body = ByteArray(contentLength)
        reader.readAllBytes(body!!)
    }

    fun readNextChunk():ByteArray {
        return reader.readNextChunk()
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
            out.write(body!!)
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
            bodySize = body!!.size
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

    class InputReader(val input:InputStream) {
        val TAG = "InputReader||Proxy"

        private val bufferSize = 12000
        private val readBuffer = ByteArray(bufferSize)
        private var position = 0
        private var limit = 0

        fun readLine() : String {
            readFromInput()
            for(index in position..limit) {
                if (readBuffer[index] == '\n'.code.toByte()) {
                    val result = readBuffer.copyOfRange(position, index).toString(Charsets.ISO_8859_1).trim()
                    position = index + 1
                    return result
                }
            }
            return readBuffer.copyOfRange(position, limit).toString(Charsets.ISO_8859_1).trim()
        }

        fun readAllBytes(destination:ByteArray) {
            var read = 0;
            while(read < destination.size) {
                readFromInput()
                try {
                    val remaining = destination.size - read
                    if (remaining < limit - position) {
                        readBuffer.copyInto(destination, read, position, remaining)
                        read += remaining
                        position += remaining
                    } else {
                        readBuffer.copyInto(destination, read, position, limit)
//                        readBuffer.copyInto(destination, read, position, limit - position)
                        read += limit - position
                        position = limit
                    }
                } catch (ex:Exception) {
                    ex.printStackTrace()
                }
//                Log.i(TAG, "Read: $read bytes of total ${destination.size}")
                System.out.println("Read: $read bytes of total ${destination.size}")
            }
        }

//        fun readBytes(destination:ByteArray) : Int {
//            readFromInput()
//            readBuffer.copyInto(destination, 0, position, limit - position)
//            val copied = limit - position
//            position = limit
//            return copied
//        }

        fun readNextChunk() : ByteArray {
            readFromInput()
            val result = readBuffer.copyOfRange(position, limit)
            position = limit
            return result
        }

        private fun rewindReadBuffer() {
            if (position > bufferSize/2) {
                // Move remaining bytes to the begining
                readBuffer.copyInto(readBuffer, 0, position, limit - position)
                limit = limit - position
                position = 0
            }
        }

        private fun readFromInput() {
            if (position == limit) {
                position = 0
                limit = 0
                // Read next chunk, block if necessary
                limit = input.read(readBuffer)
                while(limit <= 0) {
                    if (limit == 0) {
                        Thread.sleep(50)
                    }
                    if (limit == -1) {
                        throw SocketException("Socket closed")
                    }
                    limit = input.read(readBuffer)
                }
            }
//            else if (limit < bufferSize) {
//                rewindReadBuffer()
//                // Try to fill the rest of the buffer if possible, do not block
//                val read = input.read(readBuffer, limit, bufferSize - limit)
//                if (read == -1) {
//                    return
//                } else {
//                    limit += read
//                }
//            }
        }
    }

}