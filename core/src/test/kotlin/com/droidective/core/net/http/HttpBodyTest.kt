package com.droidective.core.net.http

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpBodyTest {
    private val sink = ByteArrayOutputStream()

    private fun relay(framing: BodyFraming, wire: String, limit: Int = HttpBody.MAX_CAPTURE) =
        HttpBody.relay(
            framing,
            ByteArrayInputStream(wire.toByteArray(Charsets.ISO_8859_1)),
            sink,
            limit,
        )

    private fun forwarded() = String(sink.toByteArray(), Charsets.ISO_8859_1)

    @Test
    fun anEmptyBodyReadsNothingAndForwardsNothing() {
        val source = ByteArrayInputStream("GET / HTTP/1.1\r\n".toByteArray())
        val captured = HttpBody.relay(BodyFraming.Empty, source, sink)
        assertTrue(captured.isEmpty)
        assertEquals("", forwarded())
        // The next message is still there: an empty body must not eat it.
        assertEquals(16, source.available())
    }

    @Test
    fun aStatedLengthIsCopiedExactlyAndNoFurther() {
        val source = ByteArrayInputStream("hello, worldLEFTOVER".toByteArray())
        val captured = HttpBody.relay(BodyFraming.Fixed(12), source, sink)
        assertContentEquals("hello, world".toByteArray(), captured.bytes)
        assertEquals(12L, captured.wireLength)
        assertEquals("hello, world", forwarded())
        assertContentEquals("LEFTOVER".toByteArray(), source.readBytes())
    }

    @Test
    fun aBodyBiggerThanTheLimitIsForwardedWholeAndKeptInPart() {
        val captured = relay(BodyFraming.Fixed(10), "0123456789", limit = 4)
        assertContentEquals("0123".toByteArray(), captured.bytes)
        assertTrue(captured.truncated)
        assertEquals(10L, captured.wireLength)
        // The point: what the app receives is untouched by what the proxy could afford to keep.
        assertEquals("0123456789", forwarded())
    }

    @Test
    fun aBodyTheSizeOfTheLimitIsNotTruncated() {
        assertFalse(relay(BodyFraming.Fixed(4), "0123", limit = 4).truncated)
    }

    @Test
    fun aStatedLengthThatNeverArrivesIsAnError() {
        assertFailsWith<IOException> { relay(BodyFraming.Fixed(10), "short") }
    }

    @Test
    fun aBodyThatRunsUntilCloseEndsWithTheConnection() {
        val captured = relay(BodyFraming.UntilClose, "everything there is")
        assertContentEquals("everything there is".toByteArray(), captured.bytes)
        assertEquals("everything there is", forwarded())
    }

    @Test
    fun chunksAreDecodedForTheRecordAndPassedOnAsTheyCame() {
        val wire = "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
        val captured = relay(BodyFraming.Chunked, wire)
        assertContentEquals("hello world".toByteArray(), captured.bytes)
        // The far side sees the framing unchanged, byte for byte.
        assertEquals(wire, forwarded())
        assertEquals(wire.length.toLong(), captured.wireLength)
    }

    @Test
    fun aChunkExtensionIsKeptEvenThoughNothingReadsIt() {
        val wire = "5;name=value\r\nhello\r\n0\r\n\r\n"
        assertContentEquals("hello".toByteArray(), relay(BodyFraming.Chunked, wire).bytes)
        assertEquals(wire, forwarded())
    }

    @Test
    fun trailersAfterTheLastChunkGoThroughToo() {
        val wire = "5\r\nhello\r\n0\r\nX-Checksum: 1234\r\n\r\n"
        assertContentEquals("hello".toByteArray(), relay(BodyFraming.Chunked, wire).bytes)
        assertEquals(wire, forwarded())
    }

    @Test
    fun anEmptyChunkedBodyIsStillAWellFormedOne() {
        val captured = relay(BodyFraming.Chunked, "0\r\n\r\n")
        assertEquals(0, captured.bytes.size)
        assertEquals("0\r\n\r\n", forwarded())
    }

    @Test
    fun aChunkedBodyIsTruncatedByPayloadRatherThanByFraming() {
        val captured =
            relay(BodyFraming.Chunked, "5\r\nhello\r\n5\r\nworld\r\n0\r\n\r\n", limit = 7)
        assertContentEquals("hellowo".toByteArray(), captured.bytes)
        assertTrue(captured.truncated)
    }

    @Test
    fun chunkedNonsenseIsAnErrorRatherThanAGuess() {
        assertFailsWith<IOException> { relay(BodyFraming.Chunked, "zz\r\nhello\r\n0\r\n\r\n") }
        assertFailsWith<IOException> { relay(BodyFraming.Chunked, "5\r\nhello") }
        // A chunk has to end where it said it would.
        assertFailsWith<IOException> { relay(BodyFraming.Chunked, "5\r\nhelloXX\r\n0\r\n\r\n") }
    }

    @Test
    fun aBodyCanBeDrainedWithNowhereToSendIt() {
        val source = ByteArrayInputStream("5\r\nhello\r\n0\r\n\r\nNEXT".toByteArray())
        val captured = HttpBody.relay(BodyFraming.Chunked, source, sink = null)
        assertContentEquals("hello".toByteArray(), captured.bytes)
        assertContentEquals("NEXT".toByteArray(), source.readBytes())
    }
}
