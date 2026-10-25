package com.droidective.core.reactotron

/**
 * One search of the timeline, [kind] and [query] as [ReactotronLabels.matches] takes them, that
 * keeps what it found for each entry. A search reads every payload, megabytes of them, and the
 * timeline grows several times a second; with the answers kept, each pass reads only the entries
 * that arrived since the last. Safe to call from more than one thread.
 */
class ReactotronSearch(private val kind: ReactotronKind?, private val query: String) {
    private val found = HashMap<Long, Boolean>()

    /** The [entries] this search wants, in their order. */
    @Synchronized
    fun filter(entries: List<ReactotronTimeline.Entry>): List<ReactotronTimeline.Entry> {
        // Ids only grow, so one older than the oldest left was dropped and can't come back.
        val oldest = entries.firstOrNull()?.id ?: Long.MAX_VALUE
        found.keys.removeAll { it < oldest }
        return entries.filter {
            found.getOrPut(it.id) { ReactotronLabels.matches(it, kind, query) }
        }
    }
}
