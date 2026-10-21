package com.droidective.core.net.proxy

import com.droidective.core.store.JsonFileStore
import java.io.File
import kotlinx.serialization.Serializable

/**
 * The proxy's rules as somebody chose them, which is what is kept: apps by package name, which
 * outlives a reinstall where a uid does not, and host patterns as cleaned up from what was typed.
 *
 * @property apps the apps to read; empty reads every app.
 * @property passThrough hosts never read, whoever asks for them.
 * @property readOnly hosts to read, leaving every other one alone; empty reads every host.
 */
@Serializable
data class TrafficRules(
    val apps: List<String> = emptyList(),
    val passThrough: List<String> = emptyList(),
    val readOnly: List<String> = emptyList(),
    val stepAside: Boolean = true,
) {
    /**
     * The rules the proxy runs by, with [uidOf] turning a package into its uid, or null for one
     * that isn't installed.
     *
     * Apps chosen of which none is installed read nothing rather than everything: no apps at all
     * means every app, the opposite of what was asked.
     */
    fun toProxyRules(uidOf: (String) -> Int?): ProxyRules {
        val uids = apps.mapNotNull(uidOf).toSet()
        return ProxyRules(
            only = readOnly,
            except = passThrough,
            apps = if (apps.isNotEmpty() && uids.isEmpty()) setOf(NOBODY) else uids,
            stepAside = stepAside,
        )
    }

    fun withApp(packageName: String) = copy(apps = (apps - packageName) + packageName)

    fun withoutApp(packageName: String) = copy(apps = apps - packageName)

    fun passingThrough(pattern: String) = copy(passThrough = (passThrough - pattern) + pattern)

    fun notPassingThrough(pattern: String) = copy(passThrough = passThrough - pattern)

    fun readingOnly(pattern: String) = copy(readOnly = (readOnly - pattern) + pattern)

    fun notReadingOnly(pattern: String) = copy(readOnly = readOnly - pattern)

    companion object {
        /** No app's uid: app uids start at 10000 and system ones at 0. */
        const val NOBODY = -1

        /**
         * A host pattern from what was typed or pasted, or null when it can't be one: a host, a
         * `*.` wildcard for its subdomains, or `*` for all of them. A pasted URL is cut down to its
         * host, since pasting one from the list is what most people will do.
         */
        fun pattern(text: String): String? {
            var host = text.trim().lowercase()
            host = host.substringAfter("://", host)
            host = host.takeWhile { it != '/' && it != '?' && it != '#' }
            host = host.substringAfterLast('@')
            host = host.substringBeforeLast(':').takeIf { ':' in host } ?: host
            if (host == WILDCARD) return host
            val wild = host.startsWith("$WILDCARD.")
            val name = host.removePrefix("$WILDCARD.").trimEnd('.')
            val valid = name.isNotEmpty() && name.split('.').all(::isLabel)
            return if (!valid) null else if (wild) "$WILDCARD.$name" else name
        }

        /** One part of a name between dots, as a host arriving on the wire spells it. */
        private fun isLabel(label: String): Boolean =
            label.isNotEmpty() &&
                label.length <= MAX_LABEL &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } &&
                !label.startsWith('-') &&
                !label.endsWith('-')

        private const val WILDCARD = "*"
        private const val MAX_LABEL = 63
    }
}

/** The rules, kept in `traffic-rules.json`. */
class TrafficRulesRepository(root: File) {
    private val store =
        JsonFileStore(File(root, "traffic-rules.json"), TrafficRules.serializer()) {
            TrafficRules()
        }

    fun load(): TrafficRules = store.load()

    fun save(rules: TrafficRules) = store.save(rules)
}
