package com.droidective.core.net.ws

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SocketFramesTest {
    private val mask = byteArrayOf(0x37, 0xfa.toByte(), 0x21, 0x3d)

    /** Reads [bytes] as one direction would arrive, [split] bytes at a time. */
    private fun read(
        bytes: ByteArray,
        fromApp: Boolean = false,
        negotiated: Negotiated = Negotiated.PLAIN,
        split: Int = bytes.size,
        keep: Int = FrameReader.MAX_KEPT,
    ): Pair<List<SocketMessage>, FrameReader> {
        val messages = mutableListOf<SocketMessage>()
        val reader = FrameReader(fromApp, negotiated, clock = { 7 }, keep = keep) { messages += it }
        var at = 0
        while (at < bytes.size) {
            val count = minOf(split, bytes.size - at)
            reader.feed(bytes, at, count)
            at += count
        }
        return messages to reader
    }

    @Test
    fun aServerTextFrameIsAMessage() {
        val (messages, reader) = read(frame(TEXT, "hello".toByteArray()))

        val message = messages.single()
        assertEquals(SocketMessage.Kind.Text, message.kind)
        assertEquals("hello", message.text)
        assertFalse(message.fromApp)
        assertEquals(7, message.at)
        assertFalse(reader.broken)
    }

    @Test
    fun theAppsMaskingIsUndone() {
        val (messages, _) =
            read(frame(TEXT, "{\"type\":\"ping\"}".toByteArray(), mask = mask), fromApp = true)

        assertEquals("{\"type\":\"ping\"}", messages.single().text)
        assertTrue(messages.single().fromApp)
    }

    @Test
    fun howTheBytesArriveMakesNoDifference() {
        val stream =
            frame(TEXT, "one".toByteArray(), mask = mask) +
                frame(BINARY, ByteArray(300) { it.toByte() }, mask = mask) +
                frame(TEXT, "x".repeat(70_000).toByteArray(), mask = mask)
        val whole = read(stream, fromApp = true).first.map { it.kind to it.data.toList() }

        for (split in listOf(1, 2, 3, 5, 13, 127, 4096)) {
            val pieces = read(stream, fromApp = true, split = split).first
            assertEquals(whole, pieces.map { it.kind to it.data.toList() }, "split $split")
        }
        assertEquals(
            listOf(3L, 300L, 70_000L),
            read(stream, fromApp = true).first.map { it.length },
        )
    }

    @Test
    fun aMessageInPiecesIsOneAndAPingBetweenThemIsItsOwn() {
        val stream =
            frame(TEXT, "Hel".toByteArray(), fin = false) +
                frame(PING, "are you there".toByteArray()) +
                frame(CONTINUATION, "lo, ".toByteArray(), fin = false) +
                frame(CONTINUATION, "world".toByteArray())

        val (messages, _) = read(stream)

        assertEquals(
            listOf(SocketMessage.Kind.Ping, SocketMessage.Kind.Text),
            messages.map { it.kind },
        )
        assertEquals("are you there", messages[0].text)
        assertEquals("Hello, world", messages[1].text)
    }

    @Test
    fun aCloseSaysItsStatus() {
        val payload = byteArrayOf(0x03, 0xe8.toByte()) + "bye".toByteArray()
        val (messages, _) = read(frame(CLOSE, payload))

        assertEquals(1000, messages.single().closeCode)
        assertNull(read(frame(TEXT, "hi".toByteArray())).first.single().closeCode)
    }

    @Test
    fun aLongMessageIsCountedWholeAndKeptInPart() {
        val (messages, _) = read(frame(BINARY, ByteArray(200_000)), keep = 1024)

        val message = messages.single()
        assertEquals(200_000, message.length)
        assertEquals(1024, message.data.size)
        assertTrue(message.truncated)
    }

    @Test
    fun compressedMessagesAreInflatedWithTheWindowCarriedOver() {
        val negotiated = Negotiated.of(listOf("permessage-deflate; client_max_window_bits"))
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val said =
            listOf("{\"price\":12.5,\"currency\":\"GBP\"}", "{\"price\":12.5,\"currency\":\"GBP\"}")
        // The second refers back to the first, so it only reads with the first still in mind.
        val stream =
            said.map { frame(TEXT, squeeze(deflater, it), rsv1 = true) }.reduce(ByteArray::plus)

        for (split in listOf(1, 7, stream.size)) {
            val (messages, reader) = read(stream, negotiated = negotiated, split = split)
            assertEquals(said, messages.map { it.text }, "split $split")
            assertFalse(reader.broken)
        }
    }

    @Test
    fun aSideThatStartsEachMessageAfreshIsReadThatWay() {
        val negotiated =
            Negotiated.of(
                listOf("permessage-deflate; client_no_context_takeover; server_no_context_takeover")
            )
        assertTrue(negotiated.appResets && negotiated.serverResets)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val stream =
            listOf("first message", "second message").map {
                frame(TEXT, squeeze(deflater, it), rsv1 = true, mask = mask).also {
                    deflater.reset()
                }
            }

        val (messages, _) =
            read(stream.reduce(ByteArray::plus), fromApp = true, negotiated = negotiated)

        assertEquals(listOf("first message", "second message"), messages.map { it.text })
    }

    @Test
    fun anUncompressedMessageOnACompressedSocketIsReadAsItIs() {
        val negotiated = Negotiated.of(listOf("permessage-deflate"))
        assertEquals(
            "plain",
            read(frame(TEXT, "plain".toByteArray()), negotiated = negotiated).first.single().text,
        )
    }

    @Test
    fun nonsenseStopsTheReadingAndNothingAfterIt() {
        listOf(
                // Compressed, on a socket that agreed no compression.
                frame(TEXT, "x".toByteArray(), rsv1 = true),
                // An opcode nobody has defined.
                frame(3, "x".toByteArray()),
                // A continuation with nothing to continue.
                frame(CONTINUATION, "x".toByteArray()),
                // A control frame in pieces.
                frame(PING, "x".toByteArray(), fin = false),
                // A control frame over 125 bytes.
                frame(PING, ByteArray(126)),
            )
            .forEach { bad ->
                val (messages, reader) = read(bad + frame(TEXT, "after".toByteArray()))
                assertTrue(reader.broken)
                assertEquals(emptyList(), messages)
            }
    }

    @Test
    fun randomBytesNeverThrow() {
        val random = Random(42)
        repeat(200) {
            val noise = random.nextBytes(random.nextInt(1, 2_000))
            read(
                noise,
                negotiated = Negotiated.of(listOf("permessage-deflate")),
                split = random.nextInt(1, 64),
            )
        }
    }

    @Test
    fun anExtensionNobodyKnowsLeavesTheSocketUnread() {
        val negotiated = Negotiated.of(listOf("x-webkit-deflate-frame"))
        assertFalse(negotiated.readable)
        val (messages, reader) = read(frame(TEXT, "hi".toByteArray()), negotiated = negotiated)
        assertTrue(reader.broken)
        assertEquals(emptyList(), messages)
    }

    @Test
    fun noExtensionsIsPlain() {
        assertEquals(Negotiated.PLAIN, Negotiated.of(emptyList()))
        assertEquals(Negotiated.PLAIN, Negotiated.of(listOf("")))
    }

    @Test
    fun keptBytesAreTheMessagesFirst() {
        val payload = ByteArray(5_000) { (it % 251).toByte() }
        val message = read(frame(BINARY, payload), keep = 100).first.single()
        assertContentEquals(payload.copyOf(100), message.data)
    }

    /** A frame as RFC 6455 lays it out, with a 16- or 64-bit length when it needs one. */
    private fun frame(
        opcode: Int,
        payload: ByteArray,
        fin: Boolean = true,
        mask: ByteArray? = null,
        rsv1: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0) or (if (rsv1) 0x40 else 0) or opcode)
        val maskBit = if (mask != null) 0x80 else 0
        when {
            payload.size < 126 -> out.write(maskBit or payload.size)
            payload.size <= 0xffff -> {
                out.write(maskBit or 126)
                out.write(payload.size shr 8)
                out.write(payload.size and 0xff)
            }
            else -> {
                out.write(maskBit or 127)
                for (shift in 56 downTo 0 step 8) out.write(
                    ((payload.size.toLong() shr shift) and 0xff).toInt()
                )
            }
        }
        if (mask != null) {
            out.write(mask)
            out.write(
                ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
            )
        } else {
            out.write(payload)
        }
        return out.toByteArray()
    }

    /**
     * [text] as permessage-deflate sends it: deflated, flushed, the empty block's tail left off.
     */
    private fun squeeze(deflater: Deflater, text: String): ByteArray {
        deflater.setInput(text.toByteArray())
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)
            out.write(buffer, 0, count)
            if (count < buffer.size) break
        }
        val bytes = out.toByteArray()
        return bytes.copyOf(bytes.size - 4)
    }

    private companion object {
        const val CONTINUATION = 0
        const val TEXT = 1
        const val BINARY = 2
        const val CLOSE = 8
        const val PING = 9
    }
}
