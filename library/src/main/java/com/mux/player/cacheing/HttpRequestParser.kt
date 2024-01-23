package com.mux.player.cacheing

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.util.Hashtable

class HttpRequestParser(val input:InputStream) {

    var requestLine: String = ""
    val requestHeaders: Hashtable<String, String> = Hashtable<String, String>()
    val body: StringBuffer = StringBuffer()
    var method = ""
    var httpVersion = "1.0"

    private val reader:BufferedReader = BufferedReader(InputStreamReader(input))
    private val running:Boolean = true;

    fun parseHttpRequest(): Boolean {
        val total = StringBuilder()
        var line = reader.readLine()
        while (line != null && line.length > 0) {
            total.append(line).append('\n')
            line = reader.readLine()
        }
        if (total.length > 0) {
            parseRequest(total.toString())
            return true
        }
        return false
    }

    /**
     * Parse and HTTP request.
     *
     * @param request String holding http request.
     * @throws IOException         If an I/O error occurs reading the input stream.
     * @throws HttpFormatException If HTTP Request is malformed
     */
    @Throws(IOException::class, HttpFormatException::class)
    fun parseRequest(request: String?) {
        val reader = BufferedReader(StringReader(request))
        requestLine = reader.readLine()
        if (requestLine == null) {
            throw HttpFormatException("Bad protocol, request line empty")
        }
        method = requestLine.split(" ")[0]
        httpVersion = requestLine.substringAfterLast(" ")
        var header = reader.readLine()
        while (header != null && header.length > 0) {
            appendHeaderParameter(header)
            header = reader.readLine()
        }
        // todo - worthwhile to give the caller the inputStream to read so it can take turns writing to Player and file?
        var bodyLine = reader.readLine()
        while (bodyLine != null) {
            appendMessageBody(bodyLine)
            bodyLine = reader.readLine()
        }
    }

    @Throws(HttpFormatException::class)
    private fun appendHeaderParameter(header: String) {
        val idx = header.indexOf(":")
        if (idx == -1) {
            throw HttpFormatException("Invalid Header Parameter: $header")
        }
        requestHeaders.put(header.substring(0, idx), header.substring(idx + 1))
    }

    private fun appendMessageBody(bodyLine: String) {
        body.append(bodyLine).append("\r\n")
    }
}