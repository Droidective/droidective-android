package com.droidective.core.net.proxy

/**
 * The search box over the flows.
 *
 * Words are joined by and, a `-` in front of one means the opposite, and a handful of names narrow
 * what a word is asked about: `app:`, `host:`, `method:`, `status:`, `scheme:` and `is:`. Anything
 * else is matched against the URL and the app.
 *
 * A name only counts when it is one of those. Pasting a URL in — which has a colon in it and is the
 * most obvious thing anyone will try — searches for the URL, rather than for a field called `https`
 * that does not exist.
 */
data class FlowFilter(val terms: List<Term>) {
    val isEmpty: Boolean
        get() = terms.isEmpty()

    fun matches(flow: Flow): Boolean = terms.all { it.matches(flow) }

    /** One thing asked of a flow: a word, possibly aimed at a field, possibly inverted. */
    data class Term(val field: String?, val value: String, val negated: Boolean = false) {
        fun matches(flow: Flow): Boolean = holds(flow) != negated

        private fun holds(flow: Flow): Boolean =
            when (field) {
                METHOD -> flow.request.method.equals(value, ignoreCase = true)
                HOST -> flow.host.contains(value, ignoreCase = true)
                APP -> flow.app?.contains(value, ignoreCase = true) == true
                SCHEME -> flow.scheme.equals(value, ignoreCase = true)
                STATUS -> statusHolds(flow.status, value)
                STATE -> stateHolds(flow, value)
                else -> anywhere(flow)
            }

        private fun anywhere(flow: Flow) =
            flow.url.contains(value, ignoreCase = true) ||
                flow.app?.contains(value, ignoreCase = true) == true

        /** A number, or a class of them written `4xx`. */
        private fun statusHolds(status: Int?, wanted: String): Boolean {
            if (status == null) return false
            val exact = wanted.toIntOrNull()
            if (exact != null) return status == exact
            val family = wanted.lowercase()
            if (family.length != 3 || !family.endsWith("xx")) return false
            val first = family[0].digitToIntOrNull() ?: return false
            return status / 100 == first
        }

        private fun stateHolds(flow: Flow, wanted: String): Boolean =
            when (wanted.lowercase()) {
                "failed" -> flow.failure != null
                "tunnelled",
                "tunneled" -> flow.tunnelled
                "passed" -> flow.tunnelled && flow.response == null
                "repeat" -> flow.repeatOf != null
                "mocked" -> flow.mocked != null
                "held" -> flow.paused != null
                "changed" -> flow.edited
                "pending" -> flow.response == null && flow.failure == null
                "secure" -> flow.secure
                else -> false
            }
    }

    companion object {
        const val METHOD = "method"
        const val HOST = "host"
        const val APP = "app"
        const val SCHEME = "scheme"
        const val STATUS = "status"
        const val STATE = "is"

        private val FIELDS = setOf(METHOD, HOST, APP, SCHEME, STATUS, STATE)

        val NONE = FlowFilter(emptyList())

        fun parse(query: String): FlowFilter =
            FlowFilter(query.split(' ', '\t', '\n').mapNotNull(::term))

        /** Whether [query] already holds [word], as a word of its own rather than part of one. */
        fun holds(query: String, word: String): Boolean = word in words(query)

        /**
         * [query] with [word] added, or taken out when it is already there: what tapping a chip
         * does, leaving whatever else was typed as it was.
         */
        fun toggled(query: String, word: String): String {
            val words = words(query)
            return (if (word in words) words - word else words + word).joinToString(" ")
        }

        private fun words(query: String): List<String> =
            query.split(' ', '\t', '\n').filter { it.isNotEmpty() }

        private fun term(word: String): Term? {
            val negated = word.startsWith("-")
            val rest = if (negated) word.substring(1) else word
            if (rest.isEmpty()) return null
            val colon = rest.indexOf(':')
            if (colon <= 0) return Term(null, rest, negated)
            val name = rest.substring(0, colon).lowercase()
            if (name !in FIELDS) return Term(null, rest, negated)
            val value = rest.substring(colon + 1)
            return if (value.isEmpty()) null else Term(name, value, negated)
        }
    }
}

/** What the flows add up to, for the rows of chips a filter is usually built by tapping. */
object FlowSummary {
    /** The apps seen, the busiest first, with how many flows each accounts for. */
    fun apps(flows: List<Flow>): List<Pair<String, Int>> = counted(flows.mapNotNull { it.app })

    /** The hosts spoken to, the busiest first. */
    fun hosts(flows: List<Flow>): List<Pair<String, Int>> = counted(flows.map { it.host })

    private fun counted(values: List<String>): List<Pair<String, Int>> =
        values
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
}
