package com.droidective.core.net.proxy

import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand

/**
 * Which app a connection belongs to, read out of the kernel's own table of sockets.
 *
 * The proxy sees a connection arrive and knows the port it came from; the table says which uid owns
 * a socket on that port, and the Android layer turns a uid into an app. Nothing else on a phone
 * will answer this question: a proxy is spoken to over a socket, and a socket carries no name.
 *
 * Reading the whole table needs the shell user — since Android 10 an app sees only its own rows —
 * which is the access this app already asks for.
 */
object ConnectionOwners {
    /** IPv4 sockets are in one file and the rest in another; an app may be using either. */
    val read: ShellCommand = ShellCommand("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null")

    /**
     * The uid owning the socket with local port [port].
     *
     * A port is reused over time, so an established socket is preferred over a closing one that has
     * not yet left the table — and a closing one is better than no answer at all.
     */
    fun owner(table: String, port: Int): Int? {
        var closing: Int? = null
        for (line in Parse.lines(table)) {
            val entry = entry(line) ?: continue
            if (entry.port != port) continue
            if (entry.established) return entry.uid
            if (closing == null) closing = entry.uid
        }
        return closing
    }

    /** Every established socket in the table, by local port. */
    fun established(table: String): Map<Int, Int> =
        Parse.lines(table)
            .mapNotNull(::entry)
            .filter { it.established }
            .associate { it.port to it.uid }

    /**
     * One row. The header is skipped by falling out of the port column, which reads `local_address`
     * there and is not a number.
     */
    private fun entry(line: String): Entry? {
        val columns = line.trim().split(WHITESPACE)
        if (columns.size <= UID) return null
        val port = columns[LOCAL].substringAfterLast(':', "").toIntOrNull(HEX) ?: return null
        val uid = columns[UID].toIntOrNull() ?: return null
        return Entry(port, uid, columns[STATE] == ESTABLISHED)
    }

    private data class Entry(val port: Int, val uid: Int, val established: Boolean)

    private val WHITESPACE = Regex("""\s+""")

    /**
     * A row counts its queues and timers as one column each — `00000000:00000082` — so it has fewer
     * columns than the header that names them, and the uid sits at seven.
     */
    private const val LOCAL = 1
    private const val STATE = 3
    private const val UID = 7
    private const val ESTABLISHED = "01"
    private const val HEX = 16
}

/**
 * Who opened a connection the proxy is serving.
 *
 * `core` cannot tell: it has a socket and a port number. The Android layer answers by reading
 * [ConnectionOwners.read] and looking the port up, and the result rides along on every flow from
 * that connection.
 */
fun interface FlowOwners {
    /** The uid behind a connection from [port], or null when nothing can be told. */
    fun uidOf(port: Int): Int?

    companion object {
        /** For a proxy running without the shell, where nothing can be told. */
        val UNKNOWN = FlowOwners { null }
    }
}
