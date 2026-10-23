package com.droidective.core.net.proxy

import com.droidective.core.net.http.Headers
import com.droidective.core.shell.shellQuote
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets

/**
 * A flow written back out as a `curl` command, which is how a request gets out of the phone and
 * into somebody else's hands — a terminal, a bug report, a colleague.
 *
 * Every value is quoted for the shell, because a header or a body is whatever the app chose to send
 * and a command built by pasting is a command that will one day be run.
 */
object Curl {
    /**
     * @param body the request body to include, decoded. Null leaves it out, which is what happens
     *   when nothing was kept.
     */
    fun of(flow: Flow, body: ByteArray? = flow.requestBody.bytes): String {
        val text = text(body)
        val binary = if (text == null) printf(body) else null
        val method = flow.request.method.uppercase()
        var start = "curl"
        http2(flow)?.let { start += " $it" }
        if (method != "GET") start += " -X $method"
        if (binary != null) start = "printf $binary | $start"
        val lines = mutableListOf("$start ${shellQuote(flow.url)}")
        flow.request.headers
            .filterNot { skipped(it.name) && !trailers(flow, it.name, it.value) }
            .forEach { lines += "  -H ${shellQuote("${it.name}: ${it.value}")}" }
        text?.let { lines += "  --data-raw ${shellQuote(it)}" }
        binary?.let { lines += "  --data-binary @-" }
        // Ahead of the command: a comment inside one ends it at the comment.
        return (notes(flow, body, text, binary) + lines.joinToString(" \\\n")).joinToString("\n")
    }

    /** Says what is missing rather than producing a command that quietly sends something else. */
    private fun notes(flow: Flow, body: ByteArray?, text: String?, binary: String?): List<String> {
        if (body == null || body.isEmpty()) return emptyList()
        return listOfNotNull(
            "# only the first ${body.size} bytes were kept, so only they are sent"
                .takeIf { flow.requestBody.truncated },
            "# the body was ${body.size} bytes of binary, too long to write out here"
                .takeIf { text == null && binary == null },
        )
    }

    /** `te: trailers`, the one TE HTTP/2 allows, which a gRPC server may refuse a call without. */
    private fun trailers(flow: Flow, name: String, value: String): Boolean =
        flow.http2 && name.equals("te", ignoreCase = true) && value.trim() == "trailers"

    /** HTTP/2 as the app spoke it, which a gRPC server insists on. */
    private fun http2(flow: Flow): String? =
        when {
            !flow.http2 -> null
            flow.secure -> "--http2"
            else -> "--http2-prior-knowledge"
        }

    /** [body] as text to pass as an argument, which a NUL can't be part of. */
    private fun text(body: ByteArray?): String? {
        if (body == null || body.isEmpty() || body.contains(0)) return null
        return try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /**
     * [body] as a quoted `printf` format that writes exactly its bytes: printable ASCII as itself,
     * everything else an octal escape. A quote, a backslash or a percent sign is never left bare,
     * so nothing in the body can end the quoting or be read as a conversion. Null past
     * [MAX_PRINTF], or for no body.
     */
    private fun printf(body: ByteArray?): String? {
        if (body == null || body.isEmpty() || body.size > MAX_PRINTF) return null
        val format = StringBuilder("'")
        for (byte in body) {
            val value = byte.toInt() and BYTE
            when {
                value == PERCENT -> format.append("%%")
                value in PRINTABLE && value != QUOTE && value != BACKSLASH ->
                    format.append(value.toChar())
                else -> format.append('\\').append(value.toString(OCTAL).padStart(3, '0'))
            }
        }
        return format.append("'").toString()
    }

    /**
     * `curl` works the length out itself, and the headers naming the hop belong to the connection
     * the proxy answered rather than to the request anybody would want to repeat.
     */
    private fun skipped(name: String): Boolean =
        name.equals(Headers.CONTENT_LENGTH, ignoreCase = true) ||
            Headers.HOP_BY_HOP.any { it.equals(name, ignoreCase = true) }

    /** A gRPC call or a small upload, written out; a megabyte of escapes is no command to paste. */
    private const val MAX_PRINTF = 64 * 1024
    private const val BYTE = 0xff
    private const val OCTAL = 8
    private const val PERCENT = '%'.code
    private const val QUOTE = '\''.code
    private const val BACKSLASH = '\\'.code
    private val PRINTABLE = 0x20..0x7e
}
