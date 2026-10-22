package com.droidective.core.net.proxy

/**
 * A URL with `*` standing for any run of characters, as mocks and breakpoints name what they apply
 * to. The query isn't part of it, since it changes from request to request; the scheme and host
 * fold case, as they do on the wire, and the path doesn't.
 */
object UrlPattern {
    fun matches(pattern: String, url: String): Boolean = regex(pattern).matches(normalised(url))

    /** Whether [pattern] can be one: http or https, with a host. */
    fun isPattern(pattern: String): Boolean {
        val trimmed = pattern.trim()
        val rest =
            when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(HTTPS.length)
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(HTTP.length)
                else -> return false
            }
        return rest.isNotEmpty() && !rest.startsWith("/") && rest.none { it.isWhitespace() }
    }

    private fun regex(pattern: String): Regex =
        Regex(normalised(pattern).split('*').joinToString(".*") { Regex.escape(it) })

    private fun normalised(url: String): String {
        val bare = url.substringBefore('?')
        val split = bare.indexOf("://")
        if (split < 0) return bare
        val afterScheme = split + "://".length
        val pathStart = bare.indexOf('/', afterScheme).takeIf { it >= 0 } ?: bare.length
        return bare.substring(0, pathStart).lowercase() + bare.substring(pathStart)
    }

    private const val HTTPS = "https://"
    private const val HTTP = "http://"
}
