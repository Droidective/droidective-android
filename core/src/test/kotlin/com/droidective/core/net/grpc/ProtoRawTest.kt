package com.droidective.core.net.grpc

import com.droidective.core.net.grpc.ProtoRaw.Field
import com.droidective.core.net.grpc.ProtoRaw.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProtoRawTest {
    @Test
    fun theDocumentationsOwnExampleReads() {
        // protobuf.dev's encoding guide: field 1, the varint 150.
        assertEquals("1: 150\n", render("089601"))
    }

    @Test
    fun everyWireTypeReadsAsProtocWritesIt() {
        val bytes =
            "089601" + // 1: varint
                "120774657374696e67" + // 2: "testing"
                "1a03089601" + // 3: a message holding 1: 150
                "21000000000000f03f" + // 4: fixed64, the double 1.0
                "2d0000803f" + // 5: fixed32, the float 1.0
                "3202ffff" + // 6: bytes that are neither text nor a message
                "3a00" // 7: empty
        assertEquals(
            """
            1: 150
            2: "testing"
            3 {
              1: 150
            }
            4: 0x3ff0000000000000
            5: 0x3f800000
            6: "\377\377"
            7: ""

            """
                .trimIndent(),
            render(bytes),
        )
    }

    @Test
    fun aNegativeNumberIsTheUnsignedVarintTheWireCarries() {
        // An int64 of -1 is ten bytes on the wire, and the wire doesn't say it was signed.
        assertEquals("1: 18446744073709551615\n", render("08ffffffffffffffffff01"))
    }

    @Test
    fun textIsShownAsTextWithItsEscapes() {
        val text = "héllo \"quoted\"\n\ttabbed"
        val bytes = text.toByteArray()
        val field = byteArrayOf(0x0a, bytes.size.toByte()) + bytes
        assertEquals(
            "1: \"héllo \\\"quoted\\\"\\n\\ttabbed\"\n",
            ProtoRaw.render(assertNotNull(ProtoRaw.parse(field))),
        )
    }

    @Test
    fun aNestedMessageWhoseBytesAreNotTextIsOpenedUp() {
        // 1 { 1: "a" 2: 7 }: its first byte is a line break and its second a control character.
        val inner = "0a01611007"
        val fields = assertNotNull(ProtoRaw.parse(hex("0a05$inner")))
        val message = assertIs<Value.Message>(fields.single().value)
        assertEquals(listOf(Field(1, Value.Text("a")), Field(2, Value.Varint(7))), message.fields)
    }

    @Test
    fun theLargestFieldNumberReads() {
        // 2^29 - 1, the most a field number can be, as a varint tag.
        assertEquals("536870911: 1\n", render("f8ffffff0f01"))
    }

    @Test
    fun bytesThatAreNoMessageAreNotOne() {
        assertNull(ProtoRaw.parse(hex("0b")), "a group, which is gone from the format")
        assertNull(ProtoRaw.parse(hex("0001")), "field 0")
        assertNull(ProtoRaw.parse(hex("0a05616263")), "a length past the end")
        assertNull(ProtoRaw.parse(hex("0896")), "a varint that stops part-way")
        assertNull(ProtoRaw.parse(hex("21000000")), "a fixed64 four bytes short")
        assertNull(ProtoRaw.parse(hex("0f")), "wire type 7, which never existed")
    }

    @Test
    fun nestingPastTheLimitIsShownAsBytes() {
        // Forty messages deep, each holding the next.
        var bytes = hex("0801")
        repeat(40) { bytes = byteArrayOf(0x0a, bytes.size.toByte()) + bytes }
        var value: Value = Value.Message(assertNotNull(ProtoRaw.parse(bytes)))
        var depth = 0
        while (value is Value.Message) {
            value = value.fields.single().value
            depth++
        }
        assertIs<Value.Bytes>(value)
        assertEquals(33, depth)
    }

    @Test
    fun nothingIsAnEmptyMessage() {
        assertEquals(emptyList(), ProtoRaw.parse(ByteArray(0)))
    }

    private fun render(hex: String): String =
        ProtoRaw.render(assertNotNull(ProtoRaw.parse(hex(hex))))

    private fun hex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
