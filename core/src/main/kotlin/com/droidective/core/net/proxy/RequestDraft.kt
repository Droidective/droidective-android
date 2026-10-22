package com.droidective.core.net.proxy

import com.droidective.core.net.http.Header
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets

/**
 * A request being edited before it is sent: Charles's *Compose*. Everything is text, the way it is
 * typed, until [build] checks it.
 *
 * @property headers one `Name: value` to a line.
 */
data class RequestDraft(
    val method: String,
    val url: String,
    val headers: String,
    val body: String,
) {
    /** What [build] makes of a draft: a request to send, or what is wrong with it. */
    sealed interface Built {
        data class Ready(val outgoing: Outgoing) : Built

        data class Invalid(val reason: String) : Built
    }

    /**
     * The request this draft describes, checked. A header is a name the protocol allows and a value
     * on one line — anything else would split into a second header on the wire, which is how an
     * edit smuggles in what nobody typed. The length is worked out from the body.
     */
    fun build(): Built {
        val verb = method.trim().uppercase()
        if (!Headers.isToken(verb)) return Built.Invalid("\"$method\" isn't a method.")
        val target =
            target(url.trim()) ?: return Built.Invalid("\"$url\" isn't an http or https URL.")
        val parsed = mutableListOf<Header>()
        for ((number, line) in headers.lines().withIndex()) {
            if (line.isBlank()) continue
            val header = header(line) ?: return Built.Invalid("Line ${number + 1} isn't a header.")
            parsed += header
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        var sent =
            Headers(parsed).without(*Headers.HOP_BY_HOP, Headers.CONTENT_LENGTH, Headers.HOST)
        sent = Headers(listOf(Header(Headers.HOST, target.authority)) + sent.all)
        if (bytes.isNotEmpty()) sent = sent.add(Headers.CONTENT_LENGTH, bytes.size.toString())
        sent = sent.add(CONNECTION, CLOSE)
        val head = RequestHead(verb, target.path, HTTP_1_1, sent)
        return Built.Ready(Outgoing(target.secure, target.origin, head, bytes))
    }

    private class Target(val secure: Boolean, val origin: Origin, val path: String) {
        val authority: String
            get() {
                val standard = if (secure) Origin.HTTPS_PORT else Origin.HTTP_PORT
                val host = if (':' in origin.host) "[${origin.host}]" else origin.host
                return if (origin.port == standard) host else "$host:${origin.port}"
            }
    }

    private fun target(text: String): Target? {
        val uri =
            try {
                URI(text)
            } catch (_: URISyntaxException) {
                return null
            }
        val secure =
            when (uri.scheme?.lowercase()) {
                "https" -> true
                "http" -> false
                else -> return null
            }
        val host = uri.host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() } ?: return null
        val port = uri.port.takeIf { it > 0 } ?: if (secure) Origin.HTTPS_PORT else Origin.HTTP_PORT
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return Target(secure, Origin(host, port), uri.rawPath.ifEmpty { "/" } + query)
    }

    private fun header(line: String): Header? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val name = line.substring(0, colon).trim()
        val value = line.substring(colon + 1).trim()
        if (!Headers.isToken(name) || value.any { it == '\r' || it == '\n' }) return null
        return Header(name, value)
    }

    companion object {
        /**
         * [flow]'s request as a draft to edit, or null when it has nothing to edit: a connection
         * never read, a body kept only in part, or one that isn't text to type over.
         */
        fun of(flow: Flow): RequestDraft? {
            if (flow.opening || flow.requestBody.truncated) return null
            val body = text(flow.requestBody.bytes) ?: return null
            var shown =
                flow.request.headers.without(
                    *Headers.HOP_BY_HOP,
                    Headers.CONTENT_LENGTH,
                    Headers.HOST,
                )
            // On screen as it will go: asking for what this can read, as a repeat does.
            Repeater.readable(shown[ACCEPT_ENCODING])?.let {
                shown = shown.without(ACCEPT_ENCODING).add(ACCEPT_ENCODING, it)
            }
            return RequestDraft(
                method = flow.request.method,
                url = flow.url,
                headers = shown.joinToString("\n") { "${it.name}: ${it.value}" },
                body = body,
            )
        }

        private fun text(bytes: ByteArray): String? =
            try {
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                null
            }

        private const val ACCEPT_ENCODING = "Accept-Encoding"
        private const val HTTP_1_1 = "HTTP/1.1"
        private const val CONNECTION = "Connection"
        private const val CLOSE = "close"
    }
}
