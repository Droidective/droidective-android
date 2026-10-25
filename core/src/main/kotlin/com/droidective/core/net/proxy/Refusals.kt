package com.droidective.core.net.proxy

/**
 * The hosts an app has turned the proxy's certificate down for.
 *
 * Most apps refuse a user certificate, so while the proxy reads everything, every other app on the
 * phone fails every HTTPS connection it makes — and a phone where sign-in, maps and push have all
 * stopped reads as broken. Remembering a refusal lets the next connection from that app to that
 * host pass through untouched, so the app recovers on its own next try, while the app being
 * debugged — which trusts the certificate — is still read.
 *
 * Keyed by app and host. With no shell the app is unknown, and then the host alone is the key.
 */
class Refusals(private val limit: Int = DEFAULT_LIMIT) {
    /** One app turning the certificate down for one host; [uid] is null when it wasn't known. */
    data class Refusal(val uid: Int?, val host: String)

    private val held = LinkedHashSet<Refusal>()

    @Synchronized
    fun note(uid: Int?, host: String) {
        val refusal = Refusal(uid, host.lowercase())
        held.remove(refusal)
        held.add(refusal)
        // The oldest go first: a refusal that has not come up in a long while is the least use.
        while (held.size > limit) held.remove(held.first())
    }

    /** Whether the app with [uid] has refused the certificate for [host] before. */
    @Synchronized fun has(uid: Int?, host: String): Boolean = Refusal(uid, host.lowercase()) in held

    /** Whether any app has refused the certificate for [host]. */
    @Synchronized
    fun mentions(host: String): Boolean {
        val name = host.lowercase()
        return held.any { it.host == name }
    }

    /** Every refusal held, oldest first. */
    @Synchronized fun all(): List<Refusal> = held.toList()

    /** Forgets every host the app with [uid] refused, so its next connections are read again. */
    @Synchronized
    fun forgetApp(uid: Int?) {
        held.removeAll { it.uid == uid }
    }

    @Synchronized
    fun forget(refusal: Refusal) {
        held.remove(refusal)
    }

    @Synchronized
    fun clear() {
        held.clear()
    }

    companion object {
        const val DEFAULT_LIMIT = 1_000
    }
}
