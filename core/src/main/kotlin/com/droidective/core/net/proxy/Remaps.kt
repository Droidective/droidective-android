package com.droidective.core.net.proxy

import com.droidective.core.net.http.Header
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.Serializable

/** A server to send requests to: where it is, and whether it is spoken to over TLS. */
data class Server(val secure: Boolean, val origin: Origin) {
    /** As `Host` names it: the port left out when it is the scheme's, an IPv6 address bracketed. */
    val authority: String
        get() {
            val standard = if (secure) Origin.HTTPS_PORT else Origin.HTTP_PORT
            val host = if (':' in origin.host) "[${origin.host}]" else origin.host
            return if (origin.port == standard) host else "$host:${origin.port}"
        }

    val url: String
        get() = "${if (secure) "https" else "http"}://$authority"

    /** [head] with its `Host` naming this server, which a server hosting several sites goes by. */
    fun hosting(head: RequestHead): RequestHead =
        head.with(
            headers =
                Headers(
                    listOf(Header(Headers.HOST, authority)) + head.headers.without(Headers.HOST).all
                )
        )

    companion object {
        /**
         * The server [text] names: `http://` or `https://`, a host, a port if it isn't the
         * scheme's, and nothing after. Null for anything else, a path included — a remap keeps the
         * path the app asked for.
         */
        fun of(text: String): Server? {
            val uri =
                try {
                    URI(text.trim())
                } catch (_: URISyntaxException) {
                    return null
                }
            val secure =
                when (uri.scheme?.lowercase()) {
                    "https" -> true
                    "http" -> false
                    else -> return null
                }
            val host =
                uri.host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() } ?: return null
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null)
                return null
            if (uri.rawPath.orEmpty() != "" && uri.rawPath != "/") return null
            val port =
                uri.port.takeIf { it > 0 } ?: if (secure) Origin.HTTPS_PORT else Origin.HTTP_PORT
            return Server(secure, Origin(host, port))
        }
    }
}

/**
 * Requests to [from] sent to another server instead, their path and query as the app asked:
 * Charles's *Map Remote*. The way to point an app at staging, or at a server on the laptop, without
 * a new build.
 *
 * @property from the URL it applies to, without the query; `*` stands for any run of characters.
 * @property to the server they go to instead, as `https://host` or `http://host:port`.
 */
@Serializable
data class Remap(val id: String, val from: String, val to: String, val enabled: Boolean = true) {
    fun matches(url: String): Boolean = enabled && UrlPattern.matches(from, url)

    /** What is wrong with this remap as it was typed, or null when it can be kept. */
    fun problem(): String? =
        when {
            !UrlPattern.isPattern(from) -> "The URL needs http:// or https:// and a host."
            Server.of(to) == null ->
                "Send them to http:// or https:// and a host, with a port if need be, and " +
                    "nothing after it: the path stays the one the app asked for."
            else -> null
        }

    companion object {
        /**
         * Where [url] goes instead, by the first enabled remap for it in the order they were made,
         * or null when it goes where it was asked.
         */
        fun route(remaps: List<Remap>, url: String): Server? =
            remaps.firstNotNullOfOrNull { remap ->
                if (remap.matches(url)) Server.of(remap.to) else null
            }

        /** A remap for every request to [flow]'s server, its destination left to fill in. */
        fun of(flow: Flow, id: String): Remap =
            Remap(id, "${flow.scheme}://${flow.authority}/*", "")
    }
}
