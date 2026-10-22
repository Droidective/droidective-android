package com.droidective.core.net.h2

import com.droidective.core.net.h2.H2Wire.hex
import com.droidective.core.net.h2.H2Wire.indexed
import com.droidective.core.net.h2.H2Wire.indexing
import com.droidective.core.net.h2.H2Wire.integer
import com.droidective.core.net.http.Header
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HpackTest {
    @Test fun requestsWithoutHuffmanShareOneTable() = decodesInTurn(C3, HpackDecoder())

    @Test fun requestsWithHuffmanShareOneTable() = decodesInTurn(C4, HpackDecoder())

    @Test
    fun answersWithoutHuffmanEvictTheOldest() = decodesInTurn(C5, HpackDecoder(), shrink = true)

    @Test fun answersWithHuffmanEvictTheOldest() = decodesInTurn(C6, HpackDecoder(), shrink = true)

    @Test
    fun fieldZeroIsNoField() {
        val failure = assertFailsWith<Http2Exception> { HpackDecoder().decode(indexed(0)) }
        assertTrue("field 0" in failure.message.orEmpty())
    }

    @Test
    fun anIndexPastTheTableIsNonsense() {
        val decoder = HpackDecoder()
        decoder.decode(indexing("x-one" to "1"))
        assertEquals(listOf(Header("x-one", "1")), decoder.decode(indexed(62)))
        assertFailsWith<Http2Exception> { decoder.decode(indexed(63)) }
    }

    @Test
    fun aTableAskedForPastTheLimitIsRefused() {
        val decoder = HpackDecoder(limit = 1000)
        decoder.decode(integer(1000, 5, 0x20))
        assertFailsWith<Http2Exception> { decoder.decode(integer(1001, 5, 0x20)) }
    }

    @Test
    fun aFieldBiggerThanTheTableEmptiesIt() {
        val decoder = HpackDecoder()
        // Room for one small field (32 + 2), not the next.
        decoder.decode(integer(40, 5, 0x20) + indexing("a" to "b"))
        assertEquals(listOf(Header("a", "b")), decoder.decode(indexed(62)))
        decoder.decode(indexing("longer" to "than forty with the overhead"))
        assertFailsWith<Http2Exception> { decoder.decode(indexed(62)) }
    }

    @Test
    fun shrinkingTheTableEvictsAtOnce() {
        val decoder = HpackDecoder()
        decoder.decode(indexing("a" to "1", "b" to "2"))
        // Room for one 34-octet field: the older goes, and the newer is still 62.
        decoder.decode(integer(34, 5, 0x20))
        assertEquals(listOf(Header("b", "2")), decoder.decode(indexed(62)))
        assertFailsWith<Http2Exception> { decoder.decode(indexed(63)) }
    }

    @Test
    fun aBlockThatStopsPartWayIsNonsense() {
        // A literal promising a ten-octet name and giving three.
        assertFailsWith<Http2Exception> { HpackDecoder().decode(hex("000a616263")) }
        // An integer that says it goes on, and doesn't.
        assertFailsWith<Http2Exception> { HpackDecoder().decode(hex("ff")) }
    }

    @Test
    fun anIntegerTooLongToBeOneIsNonsense() {
        assertFailsWith<Http2Exception> { HpackDecoder().decode(hex("ffffffffffff7f")) }
    }

    @Test
    fun aNeverIndexedFieldIsReadAndNotKept() {
        val decoder = HpackDecoder()
        // 0x10: never indexed, a new name. Such a field — a cookie, a token — stays out of the
        // table.
        val field = hex("10") + hex("0161") + hex("0162")
        assertEquals(listOf(Header("a", "b")), decoder.decode(field))
        assertFailsWith<Http2Exception> { decoder.decode(indexed(62)) }
    }

    /**
     * Each block in [blocks] decoded in turn by one [decoder], as RFC 7541's Appendix C does. The
     * answers there assume a 256-octet table, which a sender says with a size update first.
     */
    private fun decodesInTurn(
        blocks: List<Pair<String, List<Pair<String, String>>>>,
        decoder: HpackDecoder,
        shrink: Boolean = false,
    ) {
        for ((index, example) in blocks.withIndex()) {
            val (encoded, expected) = example
            val block =
                if (shrink && index == 0) integer(256, 5, 0x20) + hex(encoded) else hex(encoded)
            assertEquals(
                expected.map { (name, value) -> Header(name, value) },
                decoder.decode(block),
            )
        }
    }

    private companion object {
        // RFC 7541, Appendix C, taken from the RFC's text rather than typed.
        val C3 =
            listOf(
                "828684410f7777772e6578616d706c652e636f6d" to
                    listOf(
                        ":method" to "GET",
                        ":scheme" to "http",
                        ":path" to "/",
                        ":authority" to "www.example.com",
                    ),
                "828684be58086e6f2d6361636865" to
                    listOf(
                        ":method" to "GET",
                        ":scheme" to "http",
                        ":path" to "/",
                        ":authority" to "www.example.com",
                        "cache-control" to "no-cache",
                    ),
                "828785bf400a637573746f6d2d6b65790c637573746f6d2d76616c7565" to
                    listOf(
                        ":method" to "GET",
                        ":scheme" to "https",
                        ":path" to "/index.html",
                        ":authority" to "www.example.com",
                        "custom-key" to "custom-value",
                    ),
            )
        val C4 =
            listOf(
                "828684418cf1e3c2e5f23a6ba0ab90f4ff" to
                    listOf(
                        ":method" to "GET",
                        ":scheme" to "http",
                        ":path" to "/",
                        ":authority" to "www.example.com",
                    ),
                "828684be5886a8eb10649cbf" to
                    listOf(
                        ":method" to "GET",
                        ":scheme" to "http",
                        ":path" to "/",
                        ":authority" to "www.example.com",
                        "cache-control" to "no-cache",
                    ),
                "828785bf408825a849e95ba97d7f8925a849e95bb8e8b4bf" to
                    listOf(
                        ":method" to "GET",
                        ":scheme" to "https",
                        ":path" to "/index.html",
                        ":authority" to "www.example.com",
                        "custom-key" to "custom-value",
                    ),
            )
        val C5 =
            listOf(
                "4803333032580770726976617465611d4d6f6e2c203231204f637420323031332032303a31333a323120474d546e1768747470733a2f2f7777772e6578616d706c652e636f6d" to
                    listOf(
                        ":status" to "302",
                        "cache-control" to "private",
                        "date" to "Mon, 21 Oct 2013 20:13:21 GMT",
                        "location" to "https://www.example.com",
                    ),
                "4803333037c1c0bf" to
                    listOf(
                        ":status" to "307",
                        "cache-control" to "private",
                        "date" to "Mon, 21 Oct 2013 20:13:21 GMT",
                        "location" to "https://www.example.com",
                    ),
                "88c1611d4d6f6e2c203231204f637420323031332032303a31333a323220474d54c05a04677a69707738666f6f3d4153444a4b48514b425a584f5157454f50495541585157454f49553b206d61782d6167653d333630303b2076657273696f6e3d31" to
                    listOf(
                        ":status" to "200",
                        "cache-control" to "private",
                        "date" to "Mon, 21 Oct 2013 20:13:22 GMT",
                        "location" to "https://www.example.com",
                        "content-encoding" to "gzip",
                        "set-cookie" to "foo=ASDJKHQKBZXOQWEOPIUAXQWEOIU; max-age=3600; version=1",
                    ),
            )
        val C6 =
            listOf(
                "488264025885aec3771a4b6196d07abe941054d444a8200595040b8166e082a62d1bff6e919d29ad171863c78f0b97c8e9ae82ae43d3" to
                    listOf(
                        ":status" to "302",
                        "cache-control" to "private",
                        "date" to "Mon, 21 Oct 2013 20:13:21 GMT",
                        "location" to "https://www.example.com",
                    ),
                "4883640effc1c0bf" to
                    listOf(
                        ":status" to "307",
                        "cache-control" to "private",
                        "date" to "Mon, 21 Oct 2013 20:13:21 GMT",
                        "location" to "https://www.example.com",
                    ),
                "88c16196d07abe941054d444a8200595040b8166e084a62d1bffc05a839bd9ab77ad94e7821dd7f2e6c7b335dfdfcd5b3960d5af27087f3672c1ab270fb5291f9587316065c003ed4ee5b1063d5007" to
                    listOf(
                        ":status" to "200",
                        "cache-control" to "private",
                        "date" to "Mon, 21 Oct 2013 20:13:22 GMT",
                        "location" to "https://www.example.com",
                        "content-encoding" to "gzip",
                        "set-cookie" to "foo=ASDJKHQKBZXOQWEOPIUAXQWEOIU; max-age=3600; version=1",
                    ),
            )
    }
}
