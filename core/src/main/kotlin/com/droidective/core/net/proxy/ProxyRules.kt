package com.droidective.core.net.proxy

/**
 * Which hosts the proxy opens up and which it passes straight through without looking.
 *
 * Passing a host through matters more than it sounds. An app that pins its certificates will fail
 * against any proxy, and a device where sign-in, maps and push have all quietly stopped working
 * reads as a broken phone rather than as a tool left switched on. [except] is how that host keeps
 * working while the one being worked on is still open.
 *
 * [only] is the other half of the same thought: name the API being worked on and everything else is
 * left alone, which is usually what somebody actually wants.
 *
 * A pattern is a host, or `*.example.com` for its subdomains, or `*` for all of them.
 */
data class ProxyRules(
    val only: List<String> = emptyList(),
    val except: List<String> = emptyList(),
) {
    /** Whether [host] is read, rather than passed through as bytes. */
    fun opens(host: String): Boolean {
        if (except.any { matches(it, host) }) return false
        return only.isEmpty() || only.any { matches(it, host) }
    }

    companion object {
        fun matches(pattern: String, host: String): Boolean {
            val wanted = pattern.trim().lowercase()
            val name = host.trim().lowercase().trimEnd('.')
            if (wanted.isEmpty()) return false
            if (wanted == "*") return true
            if (wanted.startsWith("*.")) {
                // A subdomain of it, and not the domain itself: *.example.com is not example.com.
                return name.endsWith(wanted.substring(1)) && name != wanted.substring(2)
            }
            return name == wanted
        }
    }
}
