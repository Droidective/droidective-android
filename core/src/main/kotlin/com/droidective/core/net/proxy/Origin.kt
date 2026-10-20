package com.droidective.core.net.proxy

import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import java.net.URI
import java.net.URISyntaxException

/** The server a request is aimed at. */
data class Origin(val host: String, val port: Int) {
    companion object {
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443

        /**
         * Where a request is going: a request handed to a proxy names the whole URL, and one
         * arriving through an opened tunnel names only a path and leaves the rest to `Host`.
         *
         * @return null when neither says, which is a request no proxy can route.
         */
        fun of(head: RequestHead, secure: Boolean): Origin? {
            val fallback = if (secure) HTTPS_PORT else HTTP_PORT
            if (head.absolute) return fromUrl(head.target, fallback)
            return head.headers[Headers.HOST]?.let { authority(it, fallback) }
        }

        /** A `CONNECT` target: `host:port`, with the port never left out in practice. */
        fun authority(target: String, fallback: Int = HTTPS_PORT): Origin? {
            val trimmed = target.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            if (trimmed.startsWith("[")) return bracketed(trimmed, fallback)
            val colon = trimmed.lastIndexOf(':')
            if (colon < 0) return Origin(trimmed, fallback).takeIf { it.host.isNotEmpty() }
            val host = trimmed.substring(0, colon)
            val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
            return if (host.isNotEmpty() && port in 1..MAX_PORT) Origin(host, port) else null
        }

        private fun fromUrl(target: String, fallback: Int): Origin? {
            val uri =
                try {
                    URI(target)
                } catch (_: URISyntaxException) {
                    return null
                }
            val host = uri.host?.removeSurrounding("[", "]") ?: return null
            val port = uri.port.takeIf { it > 0 } ?: portForScheme(uri.scheme) ?: fallback
            return Origin(host, port)
        }

        /** An IPv6 address keeps its brackets on the wire and loses them everywhere else. */
        private fun bracketed(target: String, fallback: Int): Origin? {
            val close = target.indexOf(']')
            if (close <= 1) return null
            val host = target.substring(1, close)
            val rest = target.substring(close + 1)
            if (rest.isEmpty()) return Origin(host, fallback)
            val port = rest.removePrefix(":").toIntOrNull() ?: return null
            return if (rest.startsWith(":") && port in 1..MAX_PORT) Origin(host, port) else null
        }

        private fun portForScheme(scheme: String?): Int? =
            when (scheme?.lowercase()) {
                "http" -> HTTP_PORT
                "https" -> HTTPS_PORT
                else -> null
            }

        private const val MAX_PORT = 65_535
    }
}
