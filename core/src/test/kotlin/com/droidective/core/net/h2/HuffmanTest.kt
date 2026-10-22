package com.droidective.core.net.h2

import com.droidective.core.net.h2.H2Wire.hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HuffmanTest {
    @Test
    fun theRfcsExamplesDecode() {
        // RFC 7541, Appendix C.4 and C.6.
        val examples =
            mapOf(
                "f1e3c2e5f23a6ba0ab90f4ff" to "www.example.com",
                "a8eb10649cbf" to "no-cache",
                "25a849e95ba97d7f" to "custom-key",
                "25a849e95bb8e8b4bf" to "custom-value",
                "6402" to "302",
                "aec3771a4b" to "private",
                "d07abe941054d444a8200595040b8166e082a62d1bff" to "Mon, 21 Oct 2013 20:13:21 GMT",
                "9d29ad171863c78f0b97c8e9ae82ae43d3" to "https://www.example.com",
                "9bd9ab" to "gzip",
            )
        for ((encoded, text) in examples) assertEquals(text, decode(hex(encoded)))
    }

    @Test
    fun theLongestCodesDecode() {
        // Octet 0 is 13 bits, 0x1ff8; octet 255 is 26, 0x3ffffee. Padding fills each with ones.
        assertContentEquals(byteArrayOf(0), Huffman.decode(hex("ffc7"), 0, 2))
        assertContentEquals(byteArrayOf(-1), Huffman.decode(hex("fffffbbf"), 0, 4))
    }

    @Test
    fun nothingDecodesToNothing() {
        assertEquals("", decode(ByteArray(0)))
    }

    @Test
    fun endOfStringInsideAStringIsNonsense() {
        // Thirty ones is end-of-string; the two after it would be padding.
        assertFailsWith<Http2Exception> { decode(hex("ffffffff")) }
    }

    @Test
    fun paddingMustBeOnes() {
        // "0" is 00000, then 000 where 111 belongs.
        assertEquals("0", decode(hex("07")))
        assertFailsWith<Http2Exception> { decode(hex("00")) }
    }

    @Test
    fun paddingMustBeShorterThanAnOctet() {
        assertFailsWith<Http2Exception> { decode(hex("07ff")) }
    }

    @Test
    fun itReadsOnlyWhereItIsTold() {
        val around = hex("00") + hex("a8eb10649cbf") + hex("00")
        assertEquals("no-cache", String(Huffman.decode(around, 1, 7), Charsets.ISO_8859_1))
    }

    private fun decode(bytes: ByteArray): String =
        String(Huffman.decode(bytes, 0, bytes.size), Charsets.ISO_8859_1)
}
