package com.droidective.core.net

import java.net.InetAddress
import java.net.ServerSocket

/**
 * Where a test listens: on one loopback address, the one it is reached at, never on every
 * interface. A listener on every interface can be handed a port that another program — a Gradle
 * worker, the emulator — already holds on that one address, and a connection there then reaches
 * that program instead: a repeat to a server that only answers 200 came back 401, 501, reset or
 * empty, about once in a thousand under a full build.
 */
internal object Loopback {
    /** 127.0.0.1, as bytes: by name, in tests that prefer IPv6, the loopback address is ::1. */
    val address: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    /**
     * What `localhost`, the test certificate's name, reaches first, looked up as a client looks it
     * up: a TLS server listens here.
     */
    val localhost: InetAddress = InetAddress.getByName("localhost")

    fun server(at: InetAddress = address): ServerSocket = ServerSocket(0, BACKLOG, at)

    /** A port nothing on 127.0.0.1 listens on, for a test of a server that isn't there. */
    fun closedPort(): Int = server().use { it.localPort }

    const val BACKLOG = 50
}
