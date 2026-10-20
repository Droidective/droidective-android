package com.droidective.core.net.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

internal const val MAX_LINE = 16_384

/**
 * One line off [source], without its ending, or null at a clean end of connection.
 *
 * Reads a byte at a time on purpose: what follows a line is a body nobody has framed yet, so
 * anything read past the newline would be stranded in a buffer the caller cannot reach. Give it a
 * [java.io.BufferedInputStream] so each byte is not a system call.
 */
internal fun readWireLine(source: InputStream, maxLine: Int = MAX_LINE): String? {
    val out = ByteArrayOutputStream()
    while (true) {
        val byte = source.read()
        if (byte == END) {
            if (out.size() == 0) return null
            throw IOException("the connection ended mid-line")
        }
        if (byte == NEWLINE) return withoutReturn(out.toByteArray())
        if (out.size() >= maxLine) throw IOException("a line longer than $maxLine bytes")
        out.write(byte)
    }
}

/** Drops the carriage return a well-formed line ends with; a bare newline is tolerated. */
private fun withoutReturn(bytes: ByteArray): String {
    val end = if (bytes.isNotEmpty() && bytes.last() == RETURN) bytes.size - 1 else bytes.size
    return String(bytes, 0, end, StandardCharsets.ISO_8859_1)
}

private const val END = -1
private val NEWLINE = '\n'.code
private val RETURN = '\r'.code.toByte()
