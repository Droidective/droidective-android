package com.droidective.core.net.proxy

/**
 * Android's own proxy setting, `http_proxy` in the Global table, as the debugging proxy uses it.
 *
 * Pointing the phone here and taking it off again are the two moments its network is in this app's
 * hands. Getting either wrong leaves a phone whose apps cannot reach anything and nothing on screen
 * saying why, so the decisions live here, where they are tested, rather than at the call site.
 */
object ProxySetting {
    const val KEY = "http_proxy"

    /** How Android clears it. An empty value is ignored and leaves the old proxy in force. */
    const val OFF = ":0"

    private const val LOOPBACK = "127.0.0.1"
    private const val LOCALHOST = "localhost"

    /** What the setting holds while the phone is pointed at the proxy on [port]. */
    fun here(port: Int): String = "$LOOPBACK:$port"

    /** Whether [stored] sends the phone's traffic to the proxy on [port]. */
    fun pointsHere(stored: String?, port: Int): Boolean {
        val value = stored?.trim()?.lowercase() ?: return false
        return value == here(port) || value == "$LOCALHOST:$port"
    }

    /**
     * What to put back when the proxy stops, given what the setting held before it was pointed
     * here. Someone who had their own proxy set — Charles on a laptop, say — gets it back, rather
     * than no proxy at all; anyone else gets none.
     *
     * A [before] that already pointed here is a leftover from a run that never got to clean up, and
     * putting that back would be the one answer guaranteed to leave the phone without a network.
     */
    fun restore(before: String?, port: Int): String {
        val value = before?.trim()
        if (value.isNullOrEmpty() || value == OFF || value == NULL) return OFF
        return if (pointsHere(value, port)) OFF else value
    }

    /** What `settings get` prints for a key that was never set. */
    private const val NULL = "null"
}
