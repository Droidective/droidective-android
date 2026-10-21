package com.droidective.core.net.proxy

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/**
 * Opening a connection to a host the way a browser does: to the first of its addresses that
 * answers.
 *
 * A name with several addresses is only as reachable as the address tried, and the next one may
 * well be fine — a server that is down in one region, or an IPv6 address on a network with no IPv6
 * route. Trying only the first, the proxy failed a page the same app loads without it.
 */
object Dial {
    /** What a name resolves to, in the order the resolver prefers. */
    fun interface Resolver {
        @Throws(IOException::class) fun addresses(host: String): List<InetAddress>

        companion object {
            val SYSTEM = Resolver { InetAddress.getAllByName(it).toList() }
        }
    }

    /**
     * A socket connected to [host]'s first address to answer within [timeoutMillis] in all.
     *
     * Each address but the last gets a share of the time and no more, so one that never answers
     * leaves the rest something to try with.
     *
     * @throws IOException the last address's failure, when none of them answered.
     */
    fun connect(
        host: String,
        port: Int,
        timeoutMillis: Int,
        resolver: Resolver = Resolver.SYSTEM,
    ): Socket {
        val addresses = resolver.addresses(host)
        if (addresses.isEmpty()) throw UnknownHostException(host)
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        var failure: IOException? = null
        for ((index, address) in addresses.withIndex()) {
            val left = ((deadline - System.nanoTime()) / NANOS_PER_MILLI).toInt()
            if (left <= 0) break
            val last = index == addresses.lastIndex
            val socket = Socket()
            try {
                socket.connect(
                    InetSocketAddress(address, port),
                    if (last) left else minOf(left, ATTEMPT_MILLIS),
                )
                return socket
            } catch (e: IOException) {
                socket.close()
                failure = e
            }
        }
        throw failure ?: IOException("no address of $host answered within $timeoutMillis ms")
    }

    /** Long enough for a slow mobile network, short enough that a dead address isn't the end. */
    private const val ATTEMPT_MILLIS = 4_000
    private const val NANOS_PER_MILLI = 1_000_000L
}
