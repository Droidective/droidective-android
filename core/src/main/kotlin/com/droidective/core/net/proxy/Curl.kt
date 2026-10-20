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
     *   when nothing was kept or what was kept cannot be written as text.
     */
    fun of(flow: Flow, body: ByteArray? = flow.requestBody.bytes): String {
        val lines = mutableListOf<String>()
        val method = flow.request.method.uppercase()
        val start = if (method == "GET") "curl" else "curl -X $method"
        lines += "$start ${shellQuote(flow.url)}"
        flow.request.headers
            .filterNot { skipped(it.name) }
            .forEach { lines += "  -H ${shellQuote("${it.name}: ${it.value}")}" }
        note(flow, body)?.let { lines += it }
        text(body)?.let { lines += "  --data-raw ${shellQuote(it)}" }
        return lines.joinToString(" \\\n")
    }

    /** Says what is missing rather than producing a command that quietly sends something else. */
    private fun note(flow: Flow, body: ByteArray?): String? =
        when {
            body == null || body.isEmpty() -> null
            flow.requestBody.truncated -> "  # only the first ${body.size} bytes were kept"
            text(body) == null -> "  # the body was binary and is not written out here"
            else -> null
        }

    private fun text(body: ByteArray?): String? {
        if (body == null || body.isEmpty()) return null
        return try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /**
     * `curl` works the length out itself, and the headers naming the hop belong to the connection
     * the proxy answered rather than to the request anybody would want to repeat.
     */
    private fun skipped(name: String): Boolean =
        name.equals(Headers.CONTENT_LENGTH, ignoreCase = true) ||
            Headers.HOP_BY_HOP.any { it.equals(name, ignoreCase = true) }
}
