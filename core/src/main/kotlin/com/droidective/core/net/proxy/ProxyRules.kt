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
    /**
     * The apps to read, by uid. Empty reads every app. Named, only their HTTPS is opened and every
     * other app's passes through untouched: one app debugged, and the rest of the phone working.
     */
    val apps: Set<Int> = emptySet(),
    /** Whether a host an app refused the certificate for is passed through for it afterwards. */
    val stepAside: Boolean = true,
) {
    /** Whether [host] is read for the app with [uid], rather than passed through as bytes. */
    fun opens(host: String, uid: Int? = null): Boolean = passing(host, uid) == null

    /**
     * Why [host] is passed through for the app with [uid], or null when it is read. An app that
     * cannot be told, while only some apps are read, is passed through: reading it could break an
     * app nobody asked about.
     */
    fun passing(host: String, uid: Int?): Passing? =
        when {
            apps.isNotEmpty() && (uid == null || uid !in apps) -> Passing.OtherApp
            except.any { matches(it, host) } -> Passing.Rule
            only.isNotEmpty() && only.none { matches(it, host) } -> Passing.Rule
            else -> null
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

/** Why a connection was passed through rather than read. */
enum class Passing {
    /** A host the rules leave alone. */
    Rule,

    /** An app other than the ones being read. */
    OtherApp,

    /** The app refused the certificate for this host before. */
    Refused,

    /** The app would speak only HTTP/2, which is carried to the server but not read. */
    Http2,
}
