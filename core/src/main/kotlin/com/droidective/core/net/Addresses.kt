package com.droidective.core.net

/** Checks for addresses a person types into the proxy and Private DNS screens. */
object Addresses {
    private val hostname =
        Regex(
            """^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$"""
        )
    private val ipv4 = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")

    /**
     * A proxy as `host:port`, the form Android's `http_proxy` setting takes. Accepts a hostname, an
     * IPv4 address, or a bracketed IPv6 address.
     *
     * @return the normalised `host:port`, or null when [text] isn't one.
     */
    fun proxy(text: String): String? {
        val trimmed = text.trim()
        val separator = trimmed.lastIndexOf(':')
        if (separator <= 0) return null
        val host = trimmed.substring(0, separator)
        val port = trimmed.substring(separator + 1).toIntOrNull() ?: return null
        if (port !in 1..65_535) return null
        val validHost =
            if (host.startsWith("[") && host.endsWith("]")) host.length > 2 && ':' in host
            // "256.1.1.1" is a valid hostname shape; all-numeric hosts must be real IPv4.
            else if (host.all { it.isDigit() || it == '.' }) ipv4.matches(host)
            else hostname.matches(host)
        return if (validHost) "$host:$port" else null
    }

    /** A Private DNS provider must be a hostname: Android rejects an IP address there. */
    fun dnsHostname(text: String): String? {
        val trimmed = text.trim().trimEnd('.')
        return trimmed.takeIf { hostname.matches(it) && !ipv4.matches(it) && '.' in it }
    }
}
