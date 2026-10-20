package com.droidective.core.net.proxy

/**
 * The exchanges the proxy has seen, oldest first.
 *
 * Bounded twice over, by how many flows and by how much they hold. The second bound is the one that
 * matters: a few hundred JSON responses cost little and a few hundred image downloads cost hundreds
 * of megabytes, so counting flows alone would mean a limit that is either uselessly small most of
 * the time or ruinous on the day somebody watches a video.
 *
 * A flow arrives twice — once when the request goes out, once when the answer comes back — and the
 * second replaces the first without moving it, so the list keeps the order things were asked in
 * rather than the order they came back.
 */
class FlowStore(
    private val maxFlows: Int = DEFAULT_FLOWS,
    private val maxBytes: Long = DEFAULT_BYTES,
) {
    private val flows = LinkedHashMap<Long, Flow>()
    private var bytes = 0L

    @Synchronized
    fun put(flow: Flow) {
        val replaced = flows.put(flow.id, flow)
        bytes += weigh(flow) - (replaced?.let(::weigh) ?: 0L)
        trim()
    }

    /** Everything held, oldest first. A copy: the proxy keeps writing while this is read. */
    @Synchronized fun all(): List<Flow> = flows.values.toList()

    @Synchronized fun get(id: Long): Flow? = flows[id]

    @Synchronized
    fun clear() {
        flows.clear()
        bytes = 0
    }

    @Synchronized fun size(): Int = flows.size

    /** Roughly what is being held, for the store's own bound and for saying so on screen. */
    @Synchronized fun heldBytes(): Long = bytes

    private fun trim() {
        val oldest = flows.keys.iterator()
        while (oldest.hasNext() && (flows.size > maxFlows || bytes > maxBytes)) {
            val id = oldest.next()
            bytes -= flows.getValue(id).let(::weigh)
            oldest.remove()
        }
    }

    /**
     * What one flow costs to keep: the bodies it held on to, plus a flat allowance for the heads,
     * which are small and numerous rather than large and few.
     */
    private fun weigh(flow: Flow): Long =
        HEAD_ALLOWANCE + flow.requestBody.bytes.size + (flow.responseBody?.bytes?.size ?: 0)

    companion object {
        const val DEFAULT_FLOWS = 2_000
        const val DEFAULT_BYTES = 48L * 1024 * 1024
        private const val HEAD_ALLOWANCE = 2_048L
    }
}
