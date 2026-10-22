package com.droidective.core.net.grpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Messages read with their schema, against `check.proto` as protoc compiled it. */
class ProtoSchemaTest {
    private val schema = assertNotNull(ProtoSchema.parse(resource("check.protoset")))

    @Test
    fun aMessageReadsAsProtocDecodePrintsIt() {
        // What protobuf's own Python runtime serialized for a message with every field set; the
        // expected text is `protoc --decode=droidective.check.Everything`'s, but for "héllo",
        // which protoc writes as octal escapes and this writes as text.
        val expected =
            """
            d: 0.5
            f: 1.5
            i64: -2
            u64: 18446744073709551615
            i32: -3
            fx64: 7
            fx32: 8
            b: true
            s: "héllo"
            inner {
              id: 9
              label: "in"
            }
            raw: "\000\377"
            u32: 10
            kind: KIND_B
            sfx32: -11
            sfx64: -12
            si32: -13
            si64: -14
            packed: 1
            packed: 2
            packed: 300
            tags: "a"
            tags: "b"
            inners {
              id: 1
            }
            inners {
              id: 2
            }
            counts {
              key: "x"
              value: 1
            }
            name: "nm"
            nested {
              kind: KIND_A
            }

            """
                .trimIndent()
        assertEquals(expected, schema.render(hex(EVERYTHING), "droidective.check.Everything"))
    }

    @Test
    fun aMethodIsFoundByThePathItIsCalledAt() {
        assertEquals(
            ProtoSchema.Method("droidective.check.Everything", "droidective.check.Inner"),
            schema.method("/droidective.check.Check/Echo"),
        )
        assertNull(schema.method("/droidective.check.Check/Other"))
        assertTrue(schema.has("droidective.check.Everything.Nested"))
        assertTrue(schema.has("droidective.check.Everything.CountsEntry"), "a map's entry type")
    }

    @Test
    fun aFieldTheSchemaDoesNotNameIsShownByItsNumber() {
        // Inner { id: 1 } and a field 99 that a newer server sends and this schema predates.
        assertEquals("id: 1\n99: 5\n", schema.render(hex("0801" + "9806" + "05"), INNER))
    }

    @Test
    fun aFieldOfTheWrongWireTypeIsShownAsTheWireHasIt() {
        // id is an int64, sent here as a string: the schema's word is not taken over the wire's.
        assertEquals("1: \"x\"\n", schema.render(hex("0a0178"), INNER))
    }

    @Test
    fun aTypeItDoesNotKnowOrBytesThatArentAMessageAreNotRendered() {
        assertNull(schema.render(hex("0801"), "droidective.check.Missing"))
        assertNull(schema.render(hex("0b"), INNER))
    }

    @Test
    fun whatIsNotADescriptorSetIsNoSchema() {
        assertNull(ProtoSchema.parse(ByteArray(0)))
        assertNull(ProtoSchema.parse("not a descriptor set".toByteArray()))
        // A message, but one with no files in it.
        assertNull(ProtoSchema.parse(hex("1001")))
    }

    @Test
    fun twoSchemasTogetherKnowWhatEitherDoes() {
        // A second file, package-less: message Other { string note = 1; }
        val other = assertNotNull(ProtoSchema.parse(resource("other.protoset")))
        val both = schema + other
        assertTrue(both.has("Other"))
        assertTrue(both.has(INNER))
        assertEquals("note: \"hi\"\n", both.render(hex("0a026869"), "Other"))
    }

    @Test
    fun anAnyIsOpenedWhenTheSchemaKnowsItsType() {
        // A google.rpc.Status whose one detail is an ErrorInfo, as gRPC servers send errors:
        // protoc --decode leaves the Any as its type_url and value bytes; this opens it.
        val status = assertNotNull(ProtoSchema.parse(resource("status.protoset")))
        val expected =
            """
            code: 7
            message: "Permission denied."
            details {
              [type.googleapis.com/google.rpc.ErrorInfo] {
                reason: "CONSUMER_INVALID"
                domain: "googleapis.com"
                metadata {
                  key: "service"
                  value: "firestore.googleapis.com"
                }
              }
            }

            """
                .trimIndent()
        assertEquals(expected, status.render(hex(STATUS), "google.rpc.Status"))
    }

    @Test
    fun anAnyOfATypeTheSchemaDoesNotKnowIsLeftAsItsTwoFields() {
        val status = assertNotNull(ProtoSchema.parse(resource("status.protoset")))
        // Status { details { type_url: "t/x.Unknown" value: 08 01 } }
        val url = "t/x.Unknown".toByteArray()
        val any = byteArrayOf(0x0a, url.size.toByte()) + url + byteArrayOf(0x12, 2, 8, 1)
        val bytes = byteArrayOf(0x1a, any.size.toByte()) + any
        assertEquals(
            "details {\n  type_url: \"t/x.Unknown\"\n  value: \"\\010\\001\"\n}\n",
            status.render(bytes, "google.rpc.Status"),
        )
    }

    private fun resource(name: String): ByteArray =
        assertNotNull(javaClass.getResource("/grpc/$name"), name).readBytes()

    private fun hex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val INNER = "droidective.check.Inner"

        /** What protobuf's Python runtime serialized for the Status spelled out above. */
        const val STATUS =
            "080712125065726d697373696f6e2064656e6965642e1a730a28747970652e676f6f676c6561706973" +
                "2e636f6d2f676f6f676c652e7270632e4572726f72496e666f12470a10434f4e53554d45525f49" +
                "4e56414c4944120e676f6f676c65617069732e636f6d1a230a077365727669636512186669726573" +
                "746f72652e676f6f676c65617069732e636f6d"

        const val EVERYTHING =
            "09000000000000e03f150000c03f18feffffffffffffffff0120ffffffffffffffffff0128fdffff" +
                "ffffffffff013107000000000000003d0800000040014a0668c3a96c6c6f5a0608091202696e62" +
                "0200ff680a70027df5ffffff8101f4ffffffffffffff88011990011b9a01040102ac02a2010161" +
                "a2010162aa01020801aa01020802b201050a01781001ba01026e6dca01020801"
    }
}
