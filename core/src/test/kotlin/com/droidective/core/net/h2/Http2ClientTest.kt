package com.droidective.core.net.h2

import com.droidective.core.net.Loopback
import com.droidective.core.net.h2.H2Wire.data
import com.droidective.core.net.h2.H2Wire.frame
import com.droidective.core.net.h2.H2Wire.headers
import com.droidective.core.net.h2.Http2Frame.Companion.ACK
import com.droidective.core.net.h2.Http2Frame.Companion.CONTINUATION
import com.droidective.core.net.h2.Http2Frame.Companion.DATA
import com.droidective.core.net.h2.Http2Frame.Companion.END_HEADERS
import com.droidective.core.net.h2.Http2Frame.Companion.END_STREAM
import com.droidective.core.net.h2.Http2Frame.Companion.GOAWAY
import com.droidective.core.net.h2.Http2Frame.Companion.HEADERS
import com.droidective.core.net.h2.Http2Frame.Companion.PADDED
import com.droidective.core.net.h2.Http2Frame.Companion.PING
import com.droidective.core.net.h2.Http2Frame.Companion.PRIORITY
import com.droidective.core.net.h2.Http2Frame.Companion.SETTINGS
import com.droidective.core.net.h2.Http2Frame.Companion.WINDOW_UPDATE
import com.droidective.core.net.http.Header
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Http2ClientTest {
    private val call =
        listOf(
            Header(":method", "POST"),
            Header(":scheme", "https"),
            Header(":authority", "api.example.com"),
            Header(":path", "/helloworld.Greeter/SayHello"),
            Header("content-type", "application/grpc"),
            Header("te", "trailers"),
        )

    @Test
    fun aCallGoesOutWholeAndItsAnswerComesBackWhole() =
        talk(
            server = {
                val opening = frame()
                assertEquals(SETTINGS, opening.type)
                // No pushes, and a window of 16 MB a stream.
                assertEquals(mapOf(0x2 to 0L, 0x4 to (1L shl 24)), settings(opening))
                val update = frame()
                assertEquals(WINDOW_UPDATE to 0, update.type to update.stream)
                write(frame(SETTINGS, 0, 0))
                val (fields, body) = request()
                assertEquals(call, fields)
                assertEquals("hello?", String(body))
                write(
                    headers(
                        1,
                        ":status" to "200",
                        "content-type" to "application/grpc",
                        end = false,
                    ),
                    data(1, "hello", end = false),
                    headers(1, "grpc-status" to "0"),
                )
                until { it.type == SETTINGS && it.has(ACK) }
            }
        ) { client ->
            val answer = client.send(call, "hello?".toByteArray())
            assertEquals(
                listOf(Header(":status", "200"), Header("content-type", "application/grpc")),
                answer.fields,
            )
            assertEquals("hello", String(answer.body.bytes))
            assertEquals(listOf(Header("grpc-status", "0")), answer.trailers)
        }

    @Test
    fun aBodyPastTheServersWindowWaitsForIt() {
        val body = ByteArray(100_000) { (it % 13).toByte() }
        talk(
            server = {
                write(frame(SETTINGS, 0, 0))
                val got = ByteArrayOutputStream()
                // Everything a connection may send before it hears more: 65,535 bytes.
                while (got.size() < 65_535) {
                    val next = frame()
                    if (next.type != DATA) continue
                    assertTrue(next.payload.size <= 16_384, "a frame past the largest allowed")
                    got.write(next.payload)
                }
                Thread.sleep(200)
                assertEquals(65_535, got.size(), "it sent past the window")
                // What it sent while it waited: the acknowledgement of these settings, no body.
                while (available() > 0) assertTrue(frame().type != DATA, "it sent past the window")
                write(windowUpdate(0, 50_000), windowUpdate(1, 50_000))
                while (true) {
                    val next = frame()
                    if (next.type != DATA) continue
                    got.write(next.payload)
                    if (next.has(END_STREAM)) break
                }
                assertContentEquals(body, got.toByteArray())
                write(headers(1, ":status" to "200"))
            }
        ) { client ->
            assertEquals("200", client.send(call, body).fields.single().value)
        }
    }

    @Test
    fun aHeaderBlockPastOneFrameGoesOnInContinuations() {
        val big = call + Header("cookie", "c".repeat(40_000))
        talk(
            server = {
                val first = frame { it.type == HEADERS }
                assertTrue(!first.has(END_HEADERS) && first.payload.size == 16_384)
                val second = frame { it.type == CONTINUATION }
                val third = frame { it.type == CONTINUATION }
                assertTrue(!second.has(END_HEADERS) && third.has(END_HEADERS))
                val block = first.payload + second.payload + third.payload
                assertEquals(big, HpackDecoder().decode(block))
                write(headers(1, ":status" to "200"))
            }
        ) { client ->
            client.send(big, ByteArray(0))
        }
    }

    @Test
    fun aResetFailsTheCallAndNotTheConnection() =
        talk(
            server = {
                request()
                write(H2Wire.reset(1, 7))
                assertEquals(3, request().let { frames.last { it.type == HEADERS }.stream })
                write(headers(3, ":status" to "200"))
            }
        ) { client ->
            val reset = assertFailsWith<IOException> { client.send(call, "a".toByteArray()) }
            assertEquals("the server reset it (refused)", reset.message)
            assertEquals("200", client.send(call, "b".toByteArray()).fields.single().value)
        }

    @Test
    fun aServerGoingAwayFinishesWhatItTookAndTakesNoMore() =
        talk(
            server = {
                request()
                write(goAway(last = 1, code = 0), headers(1, ":status" to "200"))
            }
        ) { client ->
            assertEquals("200", client.send(call, ByteArray(0)).fields.single().value)
            val after = assertFailsWith<IOException> { client.send(call, ByteArray(0)) }
            assertEquals("the server closed the connection (no error)", after.message)
        }

    @Test
    fun aStreamPastWhatTheServerTookFails() =
        talk(server = { write(goAway(last = 0, code = 11)) }) { client ->
            val gone = assertFailsWith<IOException> { client.send(call, ByteArray(0)) }
            assertEquals("the server closed the connection (enhance your calm)", gone.message)
        }

    @Test
    fun pingsAreAnsweredAndPaddingAndPriorityAreReadPast() =
        talk(
            server = {
                request()
                write(frame(PING, 0, 0, "pingpong".toByteArray()))
                val block = H2Wire.block(":status" to "200")
                val priority = byteArrayOf(0, 0, 0, 0, 16)
                write(
                    frame(
                        HEADERS,
                        END_HEADERS or PADDED or PRIORITY,
                        1,
                        byteArrayOf(2) + priority + block + ByteArray(2),
                    ),
                    frame(
                        DATA,
                        END_STREAM or PADDED,
                        1,
                        byteArrayOf(3) + "body".toByteArray() + ByteArray(3),
                    ),
                )
                val pong = frame { it.type == PING }
                assertTrue(pong.has(ACK))
                assertEquals("pingpong", String(pong.payload))
            }
        ) { client ->
            assertEquals("body", String(client.send(call, ByteArray(0)).body.bytes))
        }

    @Test
    fun aOneHundredIsNotTheAnswer() =
        talk(
            server = {
                request()
                write(
                    headers(1, ":status" to "100", end = false),
                    headers(1, ":status" to "200", end = false),
                    data(1, "done"),
                )
            }
        ) { client ->
            val answer = client.send(call, ByteArray(0))
            assertEquals("200", answer.fields.single().value)
            assertEquals("done", String(answer.body.bytes))
        }

    @Test
    fun aConnectionClosedPartWayFailsAndStaysClosed() =
        talk(
            server = {
                request()
                write(headers(1, ":status" to "200", end = false))
                socket.close()
            }
        ) { client ->
            val cut = assertFailsWith<IOException> { client.send(call, ByteArray(0)) }
            assertEquals("the connection closed before the answer finished", cut.message)
            assertFailsWith<IOException> { client.send(call, ByteArray(0)) }
        }

    @Test
    fun theServersTableCarriesFromOneCallToTheNext() =
        talk(
            server = {
                request()
                write(
                    frame(
                        HEADERS,
                        END_HEADERS or END_STREAM,
                        1,
                        H2Wire.block(":status" to "200") + H2Wire.indexing("x-trace" to "abc"),
                    )
                )
                request()
                write(
                    frame(
                        HEADERS,
                        END_HEADERS or END_STREAM,
                        3,
                        H2Wire.block(":status" to "200") + H2Wire.indexed(62),
                    )
                )
            }
        ) { client ->
            client.send(call, ByteArray(0))
            assertEquals(Header("x-trace", "abc"), client.send(call, ByteArray(0)).fields.last())
        }

    @Test
    fun aSmallerTableTheServerAsksForIsSaidInTheNextBlock() =
        talk(
            server = {
                write(frame(SETTINGS, 0, 0, setting(0x1, 0)))
                request()
                write(headers(1, ":status" to "200"))
                until { it.type == SETTINGS && it.has(ACK) }
                request()
                val second = frames.last { it.type == HEADERS }
                assertEquals(0x20, second.payload[0].toInt() and 0xff)
                write(headers(3, ":status" to "200"))
            }
        ) { client ->
            client.send(call, ByteArray(0))
            client.send(call, ByteArray(0))
        }

    @Test
    fun aBodyWithNoAnswerFirstIsNonsense() =
        talk(
            server = {
                request()
                write(data(1, "orphan"))
            }
        ) { client ->
            val wrong = assertFailsWith<IOException> { client.send(call, ByteArray(0)) }
            assertEquals("a body before any answer", wrong.message)
        }

    /** [client] against a server of the test's own, run by [server] on a thread of its own. */
    private fun talk(server: Peer.() -> Unit, client: (Http2Client) -> Unit) {
        Loopback.server().use { listener ->
            val failure = AtomicReference<Throwable?>()
            val serving =
                thread(name = "h2-server") {
                    try {
                        listener.accept().use { Peer(it).apply { preface() }.server() }
                    } catch (e: Throwable) {
                        failure.set(e)
                    }
                }
            val clientFailure =
                Socket(Loopback.address, listener.localPort).use { socket ->
                    socket.soTimeout = TIMEOUT
                    runCatching {
                        client(Http2Client(socket.getInputStream(), socket.getOutputStream()))
                    }
                        .also { serving.join(TIMEOUT.toLong()) }
                        .exceptionOrNull()
                }
            // The server's failure first: a client that failed was most often told to.
            failure.get()?.let { server ->
                clientFailure?.let(server::addSuppressed)
                throw server
            }
            clientFailure?.let { throw it }
        }
    }

    /** The server's end, read a frame at a time, every frame it reads kept in [frames]. */
    private class Peer(val socket: Socket) {
        private val input = DataInputStream(socket.getInputStream())
        private val decoder = HpackDecoder()
        val frames = mutableListOf<Http2Frame>()

        fun preface() {
            val preface = ByteArray(H2Wire.PREFACE.size)
            input.readFully(preface)
            assertContentEquals(H2Wire.PREFACE, preface)
        }

        fun frame(): Http2Frame {
            val header = ByteArray(9)
            input.readFully(header)
            val length = (header[0].int shl 16) or (header[1].int shl 8) or header[2].int
            val payload = ByteArray(length).also(input::readFully)
            val stream =
                (header[5].int shl 24) or
                    (header[6].int shl 16) or
                    (header[7].int shl 8) or
                    header[8].int
            return Http2Frame(header[3].int, header[4].int, stream, payload).also { frames += it }
        }

        /** The next frame [wanted] picks, passing over the rest. */
        fun frame(wanted: (Http2Frame) -> Boolean): Http2Frame {
            while (true) frame().let { if (wanted(it)) return it }
        }

        fun until(wanted: (Http2Frame) -> Boolean) {
            if (frames.none(wanted)) frame(wanted)
        }

        /** The next request, its fields and its body, read to its END_STREAM. */
        fun request(): Pair<List<Header>, ByteArray> {
            val opening = frame { it.type == HEADERS }
            val fields = decoder.decode(opening.payload)
            val body = ByteArrayOutputStream()
            var ended = opening.has(END_STREAM)
            while (!ended) {
                val next = frame { it.type == DATA }
                body.write(next.payload)
                ended = next.has(END_STREAM)
            }
            return fields to body.toByteArray()
        }

        fun write(vararg frames: ByteArray) {
            frames.forEach(socket.getOutputStream()::write)
            socket.getOutputStream().flush()
        }

        fun available(): Int = input.available()

        fun settings(frame: Http2Frame): Map<Int, Long> =
            frame.payload.toList().chunked(6).associate { six ->
                val bytes = six.map { it.int.toLong() }
                ((bytes[0] shl 8) or bytes[1]).toInt() to
                    ((bytes[2] shl 24) or (bytes[3] shl 16) or (bytes[4] shl 8) or bytes[5])
            }

        private val Byte.int: Int
            get() = toInt() and 0xff
    }

    private companion object {
        const val TIMEOUT = 5_000

        fun windowUpdate(stream: Int, increment: Int) =
            frame(WINDOW_UPDATE, 0, stream, int(increment))

        fun goAway(last: Int, code: Int) = frame(GOAWAY, 0, 0, int(last) + int(code))

        fun setting(id: Int, value: Int) =
            byteArrayOf((id ushr 8).toByte(), id.toByte()) + int(value)

        fun int(value: Int) =
            byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            )
    }
}
