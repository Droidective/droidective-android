package com.droidective.core.reactotron

/** Find inside one entry: where a word is in the text the entry shows. */
object ReactotronFind {
    /**
     * Each place [query], trimmed, is in [text], in order and not overlapping, ignoring case. At
     * most [limit]: a word as short as a letter can be in a long body thousands of times.
     */
    fun matches(text: String, query: String, limit: Int = LIMIT): List<IntRange> {
        val wanted = query.trim()
        if (wanted.isEmpty()) return emptyList()
        val found = ArrayList<IntRange>()
        var at = text.indexOf(wanted, ignoreCase = true)
        while (at >= 0 && found.size < limit) {
            found += at until at + wanted.length
            at = text.indexOf(wanted, at + wanted.length, ignoreCase = true)
        }
        return found
    }

    const val LIMIT = 1_000
}
