package com.droidective.core.net.http

import java.io.IOException
import java.io.InputStream

/**
 * Reads message heads off a connection and stops at the body, leaving it in [source] for whoever
 * frames it. Give it a [java.io.BufferedInputStream].
 *
 * Lines are capped, and so is how many there may be: a peer that starts a head and never finishes
 * it must not be able to make the proxy allocate without end.
 */
class HttpReader(
    private val source: InputStream,
    private val maxLine: Int = MAX_LINE,
    private val maxHeaders: Int = MAX_HEADERS,
) {
    /** The next request, or null when the connection closed before one began. */
    fun readRequest(): RequestHead? {
        val line = readWireLine(source, maxLine) ?: return null
        val parsed = RequestLine.parse(line) ?: throw IOException("not a request line: \"$line\"")
        return RequestHead(parsed.method, parsed.target, parsed.version, readHeaders())
    }

    /** The next response, or null when the connection closed before one began. */
    fun readResponse(): ResponseHead? {
        val line = readWireLine(source, maxLine) ?: return null
        val parsed = StatusLine.parse(line) ?: throw IOException("not a status line: \"$line\"")
        return ResponseHead(parsed.version, parsed.status, parsed.reason, readHeaders())
    }

    private fun readHeaders(): Headers {
        val headers = mutableListOf<Header>()
        while (true) {
            val line =
                readWireLine(source, maxLine)
                    ?: throw IOException("the head ended before its blank line")
            if (line.isEmpty()) return Headers(headers)
            if (headers.size >= maxHeaders) throw IOException("more than $maxHeaders headers")
            val colon = line.indexOf(':')
            if (colon <= 0) throw IOException("not a header: \"$line\"")
            headers += Header(line.substring(0, colon), line.substring(colon + 1).trim())
        }
    }

    private companion object {
        const val MAX_HEADERS = 200
    }
}
