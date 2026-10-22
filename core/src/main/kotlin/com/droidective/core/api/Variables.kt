package com.droidective.core.api

/**
 * `{{name}}` in a request, filled in from values kept once — a host, a token — so one request works
 * against staging and production by changing a value rather than the request.
 */
object Variables {
    /** The names [text] asks for, in the order they first appear. */
    fun names(text: String): List<String> =
        PATTERN.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /** [text] with each name [values] has filled in; any it doesn't have are left as they were. */
    fun fill(text: String, values: Map<String, String>): String =
        PATTERN.replace(text) { match -> values[match.groupValues[1]] ?: match.value }

    /**
     * The names any of [texts] asks for that [values] doesn't have, to say before anything goes.
     */
    fun missing(texts: List<String>, values: Map<String, String>): List<String> =
        texts.flatMap(::names).distinct().filterNot { it in values }

    // Every brace escaped: Android's ICU regex refuses a bare one the JVM would accept.
    private val PATTERN = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_.\-]*)\s*\}\}""")
}
