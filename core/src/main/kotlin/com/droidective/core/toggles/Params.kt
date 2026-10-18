package com.droidective.core.toggles

import com.droidective.core.shell.shellQuote

/**
 * Values a person sets once and a toggle reuses, such as the HTTP proxy's host and port. A write
 * names one as `{name}`; the runner fills it in, or asks for it when it's missing.
 *
 * @property featureId the tool screen where the value is set.
 */
enum class Param(val key: String, val title: String, val featureId: String) {
    Proxy("proxy", "the proxy’s host and port", "http-proxy"),
    DnsHost("dns_host", "the Private DNS hostname", "private-dns");

    companion object {
        fun of(key: String): Param? = entries.firstOrNull { it.key == key }
    }
}

internal object Placeholders {
    // Both braces escaped: Android's regex engine (ICU) rejects a bare "}", though the JVM's
    // accepts it, so only a phone would ever show the difference.
    private val pattern = Regex("""\{([a-z_]+)\}""")

    sealed interface Result {
        data class Filled(val text: String) : Result

        data class Missing(val param: Param) : Result
    }

    /**
     * Fills every `{name}` in [text] from [values]. Values going into a shell line are quoted;
     * values going through the Settings API are not.
     */
    fun fill(text: String, values: (Param) -> String?, quote: Boolean): Result {
        var missing: Param? = null
        val filled =
            pattern.replace(text) { match ->
                val param = Param.of(match.groupValues[1])
                val value = param?.let(values)
                if (param == null || value.isNullOrBlank()) {
                    missing = missing ?: param
                    match.value
                } else if (quote) shellQuote(value) else value
            }
        return missing?.let { Result.Missing(it) } ?: Result.Filled(filled)
    }
}
