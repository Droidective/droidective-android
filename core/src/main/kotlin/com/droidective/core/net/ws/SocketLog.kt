package com.droidective.core.net.ws

/**
 * What the sockets said, by the flow each socket is, oldest first. Bounded by count and by bytes
 * across every socket at once: a chatty one can't keep the rest, and the oldest message goes first.
 */
class SocketLog(
    private val maxMessages: Int = DEFAULT_MESSAGES,
    private val maxBytes: Long = DEFAULT_BYTES,
) {
    private val bySocket = HashMap<Long, ArrayDeque<SocketMessage>>()
    private val arrivals = ArrayDeque<Long>()
    private var bytes = 0L

    @Synchronized
    fun put(flowId: Long, message: SocketMessage) {
        bySocket.getOrPut(flowId) { ArrayDeque() }.addLast(message)
        arrivals.addLast(flowId)
        bytes += weigh(message)
        while (arrivals.size > maxMessages || bytes > maxBytes) dropOldest()
    }

    /**
     * What the socket that is flow [flowId] said, oldest first. A copy: the proxy keeps writing.
     */
    @Synchronized fun of(flowId: Long): List<SocketMessage> = bySocket[flowId]?.toList().orEmpty()

    @Synchronized fun count(flowId: Long): Int = bySocket[flowId]?.size ?: 0

    @Synchronized
    fun clear() {
        bySocket.clear()
        arrivals.clear()
        bytes = 0
    }

    /** Roughly what is held, for the log's own bound. */
    @Synchronized fun heldBytes(): Long = bytes

    private fun dropOldest() {
        val flowId = arrivals.removeFirst()
        val messages = bySocket.getValue(flowId)
        bytes -= weigh(messages.removeFirst())
        if (messages.isEmpty()) bySocket.remove(flowId)
    }

    private fun weigh(message: SocketMessage): Long = message.data.size + ALLOWANCE

    companion object {
        const val DEFAULT_MESSAGES = 5_000
        const val DEFAULT_BYTES = 16L * 1024 * 1024

        /** What a message costs to keep beyond its bytes: the object and its fields. */
        private const val ALLOWANCE = 64L
    }
}
