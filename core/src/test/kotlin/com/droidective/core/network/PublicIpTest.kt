package com.droidective.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PublicIpTest {
    @Test
    fun anAddressComesBackTrimmed() {
        assertEquals("203.0.113.7", PublicIp.parse("203.0.113.7\n"))
        assertEquals("2001:db8::1", PublicIp.parse(" 2001:db8::1 "))
        assertEquals("::ffff:203.0.113.7", PublicIp.parse("::ffff:203.0.113.7"))
    }

    @Test
    fun anythingElseIsNotShownAsAnAddress() {
        listOf(
                "",
                "<html><title>Sign in to Wi-Fi</title></html>",
                "256.1.1.1",
                "1.2.3",
                "not:an:address!",
                "a".repeat(10) + ":" + "b".repeat(40),
                "12:34",
            )
            .forEach { assertNull(PublicIp.parse(it), it) }
    }
}
