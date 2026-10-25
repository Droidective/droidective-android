package com.droidective.core.net.proxy

/**
 * Why a flow's request and answer weren't read, so there's nothing in it to show: what to tell the
 * person looking for their app's response, and what fixes it.
 */
sealed interface Unread {
    /** Only some apps are read, and this flow's app isn't one of them, or couldn't be told. */
    data object NotChosen : Unread

    /**
     * A host rule leaves it alone: [pattern], a pass-through rule, names it, or with no [pattern]
     * the read-only hosts don't.
     */
    data class HostRule(val pattern: String?) : Unread

    /**
     * The app turned the certificate down, [before] for this host and stepped aside for since, or
     * just now. An app trusts a user certificate only if its build says so.
     */
    data class Refused(val before: Boolean) : Unread

    /** It spoke HTTP/2 that stopped making sense, and the rest was passed on unread. */
    data object Unreadable : Unread

    /**
     * One app's calls that weren't read for one reason, newest first, and the hosts they went to.
     */
    data class ByApp(val app: String?, val why: Unread, val count: Int, val hosts: List<String>)

    companion object {
        /**
         * Why [flow] wasn't read under [rules], or null when it was, or failed for another reason.
         */
        fun of(flow: Flow, rules: TrafficRules): Unread? {
            if (flow.failure?.startsWith(TlsFailure.REFUSED) == true) return Refused(before = false)
            if (!flow.tunnelled || flow.response != null) return null
            return when (flow.passing) {
                Passing.Refused -> Refused(before = true)
                Passing.OtherApp -> NotChosen
                Passing.Rule ->
                    HostRule(rules.passThrough.firstOrNull { ProxyRules.matches(it, flow.host) })
                Passing.Unreadable -> Unreadable
                null -> null
            }
        }

        /**
         * The calls in [flows] that weren't read, by app and reason, the most recent first: what an
         * app being worked on has lost, said once instead of on every row. A refusal is one reason
         * whether it was just now or before, since the fix is the same. A reason that no longer
         * holds is left out: the app is read now, the rule is gone, or the app's calls have been
         * read since it refused.
         */
        fun byApp(flows: List<Flow>, rules: TrafficRules): List<ByApp> {
            val groups = LinkedHashMap<Pair<String?, Unread>, MutableList<Flow>>()
            val readSince = HashSet<String?>()
            for (flow in flows.asReversed()) {
                if (read(flow)) readSince += flow.app
                val why = of(flow, rules) ?: continue
                if (!holds(why, flow, rules, readSince)) continue
                val key = flow.app to (if (why is Refused) Refused(before = false) else why)
                groups.getOrPut(key, ::mutableListOf) += flow
            }
            return groups.map { (key, unread) ->
                ByApp(key.first, key.second, unread.size, unread.map { it.host }.distinct())
            }
        }

        /** What a row says about why: short, since the fix is in the call's detail. */
        fun label(why: Unread): String =
            when (why) {
                is Refused -> "doesn't trust the certificate"
                NotChosen -> "not read: not an app being read"
                is HostRule ->
                    if (why.pattern != null) "not read: ${why.pattern} is passed through"
                    else "not read: not a host being read"
                Unreadable -> "the rest passed on unread"
            }

        private fun read(flow: Flow): Boolean = flow.response != null && !flow.tunnelled

        /** Whether [why] still keeps [flow]'s app from being read, as things are now. */
        private fun holds(why: Unread, flow: Flow, rules: TrafficRules, readSince: Set<String?>) =
            when (why) {
                NotChosen -> rules.apps.isNotEmpty() && flow.app !in rules.apps
                is HostRule ->
                    if (why.pattern != null) why.pattern in rules.passThrough
                    else
                        rules.readOnly.isNotEmpty() &&
                            rules.readOnly.none { ProxyRules.matches(it, flow.host) }
                is Refused -> flow.app !in readSince
                Unreadable -> true
            }
    }
}
