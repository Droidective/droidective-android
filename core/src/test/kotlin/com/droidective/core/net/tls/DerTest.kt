package com.droidective.core.net.tls

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DerTest {
    @Test
    fun writesLengthsOnBothSidesOfTheShortForm() {
        assertContentEquals(byteArrayOf(0x04, 0x00), Der.octetString(ByteArray(0)))
        assertEquals(2 + 127, Der.octetString(ByteArray(127)).size)
        // From 128 the length no longer fits in a byte: the header says how many bytes follow.
        val long = Der.octetString(ByteArray(128))
        assertContentEquals(byteArrayOf(0x04, 0x81.toByte(), 0x80.toByte()), long.copyOfRange(0, 3))
        val longer = Der.octetString(ByteArray(300))
        assertContentEquals(byteArrayOf(0x04, 0x82.toByte(), 0x01, 0x2C), longer.copyOfRange(0, 4))
    }

    @Test
    fun writesObjectIdentifiers() {
        assertContentEquals(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03), Der.oid("2.5.4.3"))
        // sha256WithRSAEncryption: the arcs past 127 are the ones that need continuation bits.
        assertContentEquals(
            byteArrayOf(
                0x06,
                0x09,
                0x2A,
                0x86.toByte(),
                0x48,
                0x86.toByte(),
                0xF7.toByte(),
                0x0D,
                0x01,
                0x01,
                0x0B,
            ),
            Der.oid("1.2.840.113549.1.1.11"),
        )
    }

    @Test
    fun writesTwoDigitYearsUntilTheCenturyRollsOver() {
        val near = Der.time(1_767_225_600_000L)
        assertEquals(Der.UTC_TIME, near[0].toInt())
        assertEquals("260101000000Z", String(near, 2, near.size - 2, Charsets.US_ASCII))
        val far = Der.time(2_524_608_000_000L)
        assertEquals(Der.GENERALIZED_TIME, far[0].toInt())
        assertEquals("20500101000000Z", String(far, 2, far.size - 2, Charsets.US_ASCII))
    }

    @Test
    fun integersKeepRoomForTheirSign() {
        assertContentEquals(byteArrayOf(0x02, 0x01, 0x00), Der.integer(0))
        assertContentEquals(byteArrayOf(0x02, 0x01, 0x7F), Der.integer(127))
        // 128 would read as negative in one byte, so it gets a leading zero.
        assertContentEquals(byteArrayOf(0x02, 0x02, 0x00, 0x80.toByte()), Der.integer(128))
        assertEquals(BigInteger.ONE, BigInteger(Der.integer(1).copyOfRange(2, 3)))
    }

    @Test
    fun findsTheKeyInsideASubjectPublicKeyInfo() {
        val key = byteArrayOf(1, 2, 3, 4)
        val info =
            Der.sequence(
                Der.sequence(Der.oid("1.2.840.113549.1.1.1"), Der.nullValue()),
                Der.bitString(key),
            )
        assertContentEquals(key, Der.publicKeyBits(info))
    }
}
