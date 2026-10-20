package com.droidective.core.net.http

/** One header line, kept as it arrived. */
data class Header(val name: String, val value: String)

/**
 * A message's headers, in the order they arrived.
 *
 * HTTP does not care about case and lets a name repeat, so lookups fold case and a repeated name
 * keeps every value. Order is kept because a proxy that reorders headers changes what it is
 * showing: two requests that differ only in header order are a real thing to go looking for.
 */
data class Headers(val all: List<Header> = emptyList()) : Iterable<Header> {
    override fun iterator(): Iterator<Header> = all.iterator()

    /** The first value given for [name], or null when it was not sent. */
    operator fun get(name: String): String? = all.firstOrNull { it.named(name) }?.value

    /** Every value given for [name], for the names that may repeat, such as `Set-Cookie`. */
    fun values(name: String): List<String> = all.filter { it.named(name) }.map { it.value }

    fun has(name: String): Boolean = all.any { it.named(name) }

    fun add(name: String, value: String): Headers = Headers(all + Header(name, value))

    /** Without [names] — how a proxy drops the headers that belong to one hop. */
    fun without(vararg names: String): Headers =
        Headers(all.filterNot { header -> names.any { header.named(it) } })

    /** The length the message states, or null when it states none or states nonsense. */
    val contentLength: Long?
        get() = get(CONTENT_LENGTH)?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    /** Whether the body arrives in chunks. Only the last coding applied decides. */
    val chunked: Boolean
        get() = get(TRANSFER_ENCODING)?.split(',')?.lastOrNull()?.trim().equals(CHUNKED, true)

    val contentType: String?
        get() = get(CONTENT_TYPE)

    /** The coding the body was compressed with, lowercased, or null when it was not. */
    val contentEncoding: String?
        get() = get(CONTENT_ENCODING)?.trim()?.lowercase()?.takeUnless { it == "identity" }

    private fun Header.named(name: String) = this.name.equals(name, ignoreCase = true)

    companion object {
        const val CONTENT_LENGTH = "Content-Length"
        const val CONTENT_TYPE = "Content-Type"
        const val CONTENT_ENCODING = "Content-Encoding"
        const val TRANSFER_ENCODING = "Transfer-Encoding"
        const val HOST = "Host"
        const val CHUNKED = "chunked"

        /**
         * The headers that describe one hop rather than the message, which a proxy answers for
         * itself and must not pass along. RFC 9110 names these.
         */
        val HOP_BY_HOP =
            arrayOf(
                "Connection",
                "Keep-Alive",
                "Proxy-Authenticate",
                "Proxy-Authorization",
                "Proxy-Connection",
                "TE",
                "Trailer",
                "Transfer-Encoding",
                "Upgrade",
            )
    }
}
