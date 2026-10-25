package com.droidective.core.network

/** The address servers see, asked of a service that answers with nothing else. */
object PublicIp {
    /** Answers a GET with the caller's address as plain text, IPv4 or IPv6. */
    const val URL = "https://api64.ipify.org"

    private val ipv4 = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")
    private val ipv6 = Regex("""^[0-9A-Fa-f:.]+$""")

    /**
     * The address in [body], or null when it isn't one: a captive portal's page, an error, or
     * anything else that shouldn't be shown as an address.
     */
    fun parse(body: String): String? {
        val text = body.trim()
        val v6 =
            ':' in text &&
                text.length <= MAX_V6 &&
                ipv6.matches(text) &&
                text.count { it == ':' } >= 2
        return text.takeIf { ipv4.matches(it) || v6 }
    }

    private const val MAX_V6 = 45
}
