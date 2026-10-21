package com.droidective.core.net.proxy

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DialTest {
    private val ipv4 = InetAddress.getByName("127.0.0.1")
    private val ipv6 = InetAddress.getByName("::1")

    @Test
    fun anAddressThatDoesNotAnswerIsNotTheEnd() {
        // Listening on IPv4 only: the first address the name gives is refused, the second is fine.
        ServerSocket(0, 1, ipv4).use { server ->
            Dial.connect("api.example.test", server.localPort, 5_000) { listOf(ipv6, ipv4) }
                .use { socket -> assertEquals(ipv4, socket.inetAddress) }
        }
    }

    @Test
    fun whenNoAddressAnswersTheLastFailureIsWhatIsSaid() {
        val free = ServerSocket(0, 1, ipv4).use { it.localPort }
        assertFailsWith<IOException> {
            Dial.connect("api.example.test", free, 5_000) { listOf(ipv6, ipv4) }
        }
    }

    @Test
    fun aNameWithNoAddressesIsUnknown() {
        assertFailsWith<UnknownHostException> {
            Dial.connect("nothing.example.test", 443, 5_000) { emptyList() }
        }
    }

    @Test
    fun aNameThatDoesNotResolveSaysSo() {
        assertFailsWith<UnknownHostException> {
            Dial.connect("nothing.example.test", 443, 5_000) { throw UnknownHostException(it) }
        }
    }
}
