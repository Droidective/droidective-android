package com.droidective.core.net.grpc

import com.droidective.core.net.http.Headers
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrpcTest {
    @Test
    fun aBodyIsMessagesOneAfterAnother() {
        val body =
            framed("first".toByteArray()) + framed(ByteArray(0)) + framed("third".toByteArray())
        val messages = Grpc.messages(body, encoding = null)
        assertEquals(listOf("first", "", "third"), messages.map { String(it.bytes) })
        assertTrue(messages.all { it.problem == null && !it.compressed })
        assertEquals(5, messages.first().wireLength)
    }

    @Test
    fun aCompressedMessageIsOpenedWithTheCallsEncoding() {
        val gzipped =
            ByteArrayOutputStream().also {
                GZIPOutputStream(it).use { gz -> gz.write("hello".toByteArray()) }
            }
        val message =
            Grpc.messages(framed(gzipped.toByteArray(), compressed = true), "gzip").single()
        assertContentEquals("hello".toByteArray(), message.bytes)
        assertTrue(message.compressed)
        assertEquals(gzipped.size(), message.wireLength)
    }

    @Test
    fun aCompressionThisCantUndoSaysWhich() {
        val message =
            Grpc.messages(framed(byteArrayOf(1, 2, 3), compressed = true), "snappy").single()
        assertEquals("compressed with snappy", message.problem)
    }

    @Test
    fun aBodyCutOffEndsWithWhatCameOfItsLastMessage() {
        val whole = framed("complete".toByteArray()) + framed("cut off here".toByteArray())
        val messages = Grpc.messages(whole.copyOf(whole.size - 4), encoding = null)
        assertNull(messages[0].problem)
        assertEquals("cut off ".length, messages[1].wireLength)
        assertEquals("only part of it was kept", messages[1].problem)
        // Cut inside the next message's prefix.
        val prefix = Grpc.messages(framed("one".toByteArray()) + byteArrayOf(0, 0), null)
        assertEquals("only part of it was kept", prefix.last().problem)
    }

    @Test
    fun theStatusIsInTheTrailers() {
        val status =
            GrpcStatus.of(
                Headers().add("content-type", "application/grpc"),
                Headers().add("grpc-status", "5").add("grpc-message", "no%20such%20%E2%9C%93 doc"),
            )
        assertEquals(GrpcStatus(5, "no such ✓ doc"), status)
        assertEquals("NOT_FOUND: no such ✓ doc", status.toString())
        assertTrue(status!!.callers)
        assertFalse(status.ok)
    }

    @Test
    fun anAnswerThatIsOnlyTrailersHasItsStatusInItsHeaders() {
        val status = GrpcStatus.of(Headers().add("grpc-status", "14"), Headers())
        assertEquals("UNAVAILABLE", status?.name)
        assertFalse(status!!.callers)
    }

    @Test
    fun aMessageIsTakenAsItIsWhereItIsNotAnEscape() {
        // gRPC escapes only with %XX; a plus is a plus, and a stray % is itself.
        val status =
            GrpcStatus.of(
                Headers(),
                Headers().add("grpc-status", "3").add("grpc-message", "1+1 is 100%"),
            )
        assertEquals("1+1 is 100%", status?.message)
    }

    @Test
    fun aCodePastTheNamedOnesIsItsNumber() {
        assertEquals("42", GrpcStatus(42, null).name)
        assertEquals("OK", GrpcStatus(0, null).toString())
        assertNull(GrpcStatus.of(Headers().add("grpc-status", "soon"), Headers()))
        assertNull(GrpcStatus.of(Headers(), Headers()))
    }

    @Test
    fun theMethodIsTheServicesLastNameAndTheMethod() {
        assertEquals(
            "Firestore/GetDocument",
            Grpc.method("/google.firestore.v1.Firestore/GetDocument"),
        )
        assertEquals("Greeter/SayHello", Grpc.method("/Greeter/SayHello"))
        assertNull(Grpc.method("/"))
        assertNull(Grpc.method("/a/b/c"))
        assertNull(Grpc.method("/only"))
    }

    @Test
    fun gRpcIsSaidByItsContentType() {
        assertTrue(Grpc.carries("application/grpc"))
        assertTrue(Grpc.carries("application/grpc+proto"))
        assertTrue(Grpc.carries("Application/GRPC; charset=utf-8"))
        assertFalse(Grpc.carries("application/grpc-web"))
        assertFalse(Grpc.carries("application/json"))
        assertFalse(Grpc.carries(null))
    }

    @Test
    fun aBinaryHeaderIsReadAsTheMessageItCarries() {
        // A google.rpc.Status: code 7, and its message.
        val status = byteArrayOf(0x08, 0x07, 0x12, 0x02) + "no".toByteArray()
        val unpadded = Base64.getEncoder().withoutPadding().encodeToString(status)
        assertEquals("1: 7\n2: \"no\"\n", Grpc.binaryHeader("grpc-status-details-bin", unpadded))
        assertNull(Grpc.binaryHeader("grpc-message", unpadded), "not a binary header")
        assertNull(Grpc.binaryHeader("x-trace-bin", "not base64!"))
        assertNull(Grpc.binaryHeader("x-trace-bin", ""), "nothing in it")
    }

    /** One length-prefixed message, as gRPC frames it. */
    private fun framed(bytes: ByteArray, compressed: Boolean = false): ByteArray {
        val length = bytes.size
        val prefix =
            byteArrayOf(
                if (compressed) 1 else 0,
                (length ushr 24).toByte(),
                (length ushr 16).toByte(),
                (length ushr 8).toByte(),
                length.toByte(),
            )
        return prefix + bytes
    }
}
