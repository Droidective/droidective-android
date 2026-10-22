package com.droidective.core.net.grpc

/**
 * What an app's `.proto` files say, read from the descriptor set `protoc` compiles them into
 * (`protoc --include_imports --descriptor_set_out=app.protoset …`, as grpcurl's `-protoset` takes):
 * each message's fields by number, each enum's names, and each method's request and answer.
 *
 * With it a message reads the way `protoc --decode` prints it — field names, enum names, signed and
 * floating numbers as what they are — rather than as numbers and a guess.
 */
class ProtoSchema
private constructor(
    private val messages: Map<String, MessageType>,
    private val enums: Map<String, Map<Int, String>>,
    private val methods: Map<String, Method>,
) {
    /** A gRPC method, by the path it is called at: `/package.Service/Method`. */
    data class Method(val input: String, val output: String)

    /** The message type a body is, in this schema. */
    class Typed(val schema: ProtoSchema, val type: String) {
        fun render(bytes: ByteArray): String? = schema.render(bytes, type)
    }

    /** What the body of a call to [path] is: its request's type, or its answer's. */
    fun typed(path: String, answer: Boolean): Typed? {
        val method = method(path) ?: return null
        return Typed(this, if (answer) method.output else method.input)
    }

    private class MessageType(val fields: Map<Int, FieldType>)

    private class FieldType(
        val name: String,
        val type: Int,
        val typeName: String?,
        val repeated: Boolean,
    )

    /** How many message types it knows. */
    val size: Int
        get() = messages.size

    /** The method called at [path], when the schema has its service. */
    fun method(path: String): Method? = methods[path.substringBefore('?')]

    fun has(type: String): Boolean = type in messages

    /** This and [other] together, as the files of one folder are. */
    operator fun plus(other: ProtoSchema): ProtoSchema =
        ProtoSchema(messages + other.messages, enums + other.enums, methods + other.methods)

    /**
     * [bytes] as a message of [type] (a full name, `package.Message`), in `protoc --decode`'s text
     * format: null when the schema doesn't know the type or the bytes aren't a message. A field the
     * schema doesn't name is shown by its number, as `--decode_raw` would.
     */
    fun render(bytes: ByteArray, type: String): String? {
        val message = messages[type] ?: return null
        val fields = ProtoWire.fields(bytes) ?: return null
        return buildString { message(fields, message, indent = 0, depth = 0) }
    }

    private fun StringBuilder.message(
        fields: List<WireField>,
        type: MessageType,
        indent: Int,
        depth: Int,
    ) {
        for (wire in fields) {
            val field = type.fields[wire.number]
            if (field == null || !typed(wire, field, indent, depth)) {
                append(ProtoRaw.render(listOf(ProtoRaw.field(wire, depth)), indent))
            }
        }
    }

    /** Writes [wire] as [field], or says it can't: the wire type isn't the one declared. */
    private fun StringBuilder.typed(
        wire: WireField,
        field: FieldType,
        indent: Int,
        depth: Int,
    ): Boolean {
        val bytes = wire.bytes
        if (field.type == MESSAGE) {
            val nested = messages[field.typeName] ?: return false
            val inner = bytes?.let(ProtoWire::fields) ?: return false
            if (depth >= MAX_DEPTH) return false
            line(indent).append(field.name).append(" {\n")
            if (field.typeName != ANY || !any(inner, indent + 1, depth + 1)) {
                message(inner, nested, indent + 1, depth + 1)
            }
            line(indent).append("}\n")
            return true
        }
        if (wire.wireType == ProtoWire.LENGTH && field.repeated && field.type !in DELIMITED) {
            // A packed repeated field: every value of it in one run of bytes.
            val values = packed(checkNotNull(bytes), field.type) ?: return false
            for (value in values) line(indent)
                .append(field.name)
                .append(": ")
                .append(scalar(value, field))
                .append('\n')
            return true
        }
        if (wire.wireType != wireTypeOf(field.type)) return false
        val shown =
            when (field.type) {
                STRING -> ProtoRaw.text(checkNotNull(bytes))
                BYTES -> ProtoRaw.quoted(checkNotNull(bytes))
                else -> scalar(wire.bits, field)
            }
        line(indent).append(field.name).append(": ").append(shown).append('\n')
        return true
    }

    /**
     * An `Any` opened up, as protoc writes one: `[its type URL] { its fields }`. Only when the
     * schema has the type the URL names; otherwise it is left as its two fields.
     */
    private fun StringBuilder.any(fields: List<WireField>, indent: Int, depth: Int): Boolean {
        val url =
            fields.lastOrNull { it.number == ANY_URL }?.bytes?.let { String(it, Charsets.UTF_8) }
        val type = messages[url?.substringAfterLast('/')] ?: return false
        val value = fields.lastOrNull { it.number == ANY_VALUE }?.bytes ?: ByteArray(0)
        val inner = ProtoWire.fields(value) ?: return false
        line(indent).append('[').append(url).append("] {\n")
        message(inner, type, indent + 1, depth + 1)
        line(indent).append("}\n")
        return true
    }

    private fun scalar(bits: Long, field: FieldType): String =
        when (field.type) {
            DOUBLE -> Double.fromBits(bits).let { number(it, it.toString()) }
            FLOAT -> Float.fromBits(bits.toInt()).let { number(it.toDouble(), it.toString()) }
            INT64,
            SFIXED64 -> bits.toString()
            UINT64,
            FIXED64 -> java.lang.Long.toUnsignedString(bits)
            INT32,
            SFIXED32 -> bits.toInt().toString()
            UINT32,
            FIXED32 -> (bits and UINT32_MASK).toString()
            BOOL -> (bits != 0L).toString()
            ENUM -> enums[field.typeName]?.get(bits.toInt()) ?: bits.toInt().toString()
            SINT32 -> (bits.toInt().let { (it ushr 1) xor -(it and 1) }).toString()
            SINT64 -> ((bits ushr 1) xor -(bits and 1)).toString()
            else -> bits.toString()
        }

    private fun packed(bytes: ByteArray, type: Int): List<Long>? =
        when (wireTypeOf(type)) {
            ProtoWire.VARINT -> ProtoWire.varints(bytes)
            ProtoWire.FIXED64 -> ProtoWire.fixed(bytes, LONG_BYTES)
            ProtoWire.FIXED32 -> ProtoWire.fixed(bytes, INT_BYTES)
            else -> null
        }

    private fun StringBuilder.line(indent: Int): StringBuilder = append("  ".repeat(indent))

    companion object {
        /**
         * The schema in [bytes], a `FileDescriptorSet`, or null when they aren't one: what a folder
         * of files gets is whatever someone pushed into it.
         */
        fun parse(bytes: ByteArray): ProtoSchema? {
            val files = ProtoWire.fields(bytes)?.filter { it.number == SET_FILE } ?: return null
            if (files.isEmpty()) return null
            val reading = Reading()
            for (file in files) reading.file(file.bytes ?: return null) ?: return null
            return ProtoSchema(reading.messages, reading.enums, reading.methods)
        }

        /** The wire type a field of [type] (a `FieldDescriptorProto.Type`) arrives in, unpacked. */
        private fun wireTypeOf(type: Int): Int =
            when (type) {
                DOUBLE,
                FIXED64,
                SFIXED64 -> ProtoWire.FIXED64
                FLOAT,
                FIXED32,
                SFIXED32 -> ProtoWire.FIXED32
                STRING,
                BYTES,
                MESSAGE -> ProtoWire.LENGTH
                else -> ProtoWire.VARINT
            }

        /** As protoc writes a number: a whole one without a point, the rest as [text] has it. */
        private fun number(value: Double, text: String): String =
            when {
                value.isNaN() -> "nan"
                value.isInfinite() -> if (value > 0) "inf" else "-inf"
                value == Math.rint(value) && Math.abs(value) < WHOLE_LIMIT ->
                    value.toLong().toString()
                else -> text
            }

        // descriptor.proto's field numbers, for the parts of it read here.
        private const val SET_FILE = 1
        private const val FILE_PACKAGE = 2
        private const val FILE_MESSAGE = 4
        private const val FILE_ENUM = 5
        private const val FILE_SERVICE = 6
        private const val MESSAGE_NAME = 1
        private const val MESSAGE_FIELD = 2
        private const val MESSAGE_NESTED = 3
        private const val MESSAGE_ENUM = 4
        private const val FIELD_NAME = 1
        private const val FIELD_NUMBER = 3
        private const val FIELD_LABEL = 4
        private const val FIELD_TYPE = 5
        private const val FIELD_TYPE_NAME = 6
        private const val ENUM_NAME = 1
        private const val ENUM_VALUE = 2
        private const val VALUE_NAME = 1
        private const val VALUE_NUMBER = 2
        private const val SERVICE_NAME = 1
        private const val SERVICE_METHOD = 2
        private const val METHOD_NAME = 1
        private const val METHOD_INPUT = 2
        private const val METHOD_OUTPUT = 3

        // FieldDescriptorProto.Type.
        private const val DOUBLE = 1
        private const val FLOAT = 2
        private const val INT64 = 3
        private const val UINT64 = 4
        private const val INT32 = 5
        private const val FIXED64 = 6
        private const val FIXED32 = 7
        private const val BOOL = 8
        private const val STRING = 9
        private const val MESSAGE = 11
        private const val BYTES = 12
        private const val UINT32 = 13
        private const val ENUM = 14
        private const val SFIXED32 = 15
        private const val SFIXED64 = 16
        private const val SINT32 = 17
        private const val SINT64 = 18
        private val DELIMITED = setOf(STRING, BYTES, MESSAGE)

        private const val ANY = "google.protobuf.Any"
        private const val ANY_URL = 1
        private const val ANY_VALUE = 2

        // FieldDescriptorProto.Label.
        private const val REPEATED = 3

        private const val LONG_BYTES = 8
        private const val INT_BYTES = 4
        private const val UINT32_MASK = 0xffffffffL
        private const val WHOLE_LIMIT = 1e15
        private const val MAX_DEPTH = 64
    }

    /** One pass over a descriptor set's files, collecting what each declares by its full name. */
    private class Reading {
        val messages = HashMap<String, MessageType>()
        val enums = HashMap<String, Map<Int, String>>()
        val methods = HashMap<String, Method>()

        /** Null when [bytes] aren't a `FileDescriptorProto`. */
        fun file(bytes: ByteArray): Unit? {
            val fields = ProtoWire.fields(bytes) ?: return null
            val pack = fields.text(FILE_PACKAGE).orEmpty()
            val scope = if (pack.isEmpty()) "" else "$pack."
            for (field in fields) {
                val inner = field.bytes ?: continue
                when (field.number) {
                    FILE_MESSAGE -> message(inner, scope) ?: return null
                    FILE_ENUM -> enum(inner, scope) ?: return null
                    FILE_SERVICE -> service(inner, scope) ?: return null
                }
            }
            return Unit
        }

        private fun message(bytes: ByteArray, scope: String): Unit? {
            val fields = ProtoWire.fields(bytes) ?: return null
            val name = scope + (fields.text(MESSAGE_NAME) ?: return null)
            val declared = HashMap<Int, FieldType>()
            for (field in fields) {
                val inner = field.bytes ?: continue
                when (field.number) {
                    MESSAGE_FIELD -> {
                        val described = ProtoWire.fields(inner) ?: return null
                        val number = described.varint(FIELD_NUMBER)?.toInt() ?: return null
                        declared[number] =
                            FieldType(
                                described.text(FIELD_NAME) ?: return null,
                                described.varint(FIELD_TYPE)?.toInt() ?: return null,
                                described.text(FIELD_TYPE_NAME)?.removePrefix("."),
                                described.varint(FIELD_LABEL)?.toInt() == REPEATED,
                            )
                    }
                    MESSAGE_NESTED -> message(inner, "$name.") ?: return null
                    MESSAGE_ENUM -> enum(inner, "$name.") ?: return null
                }
            }
            messages[name] = MessageType(declared)
            return Unit
        }

        private fun enum(bytes: ByteArray, scope: String): Unit? {
            val fields = ProtoWire.fields(bytes) ?: return null
            val name = scope + (fields.text(ENUM_NAME) ?: return null)
            val values = HashMap<Int, String>()
            for (field in fields.filter { it.number == ENUM_VALUE }) {
                val value = ProtoWire.fields(field.bytes ?: return null) ?: return null
                val name = value.text(VALUE_NAME) ?: return null
                // A number left off is the default, 0.
                values[value.varint(VALUE_NUMBER)?.toInt() ?: 0] = name
            }
            enums[name] = values
            return Unit
        }

        private fun service(bytes: ByteArray, scope: String): Unit? {
            val fields = ProtoWire.fields(bytes) ?: return null
            val name = scope + (fields.text(SERVICE_NAME) ?: return null)
            for (field in fields.filter { it.number == SERVICE_METHOD }) {
                val method = ProtoWire.fields(field.bytes ?: return null) ?: return null
                val input = method.text(METHOD_INPUT)?.removePrefix(".") ?: return null
                val output = method.text(METHOD_OUTPUT)?.removePrefix(".") ?: return null
                methods["/$name/${method.text(METHOD_NAME) ?: return null}"] = Method(input, output)
            }
            return Unit
        }

        private fun List<WireField>.text(number: Int): String? = lastOrNull {
            it.number == number
        }
            ?.bytes
            ?.let { String(it, Charsets.UTF_8) }

        private fun List<WireField>.varint(number: Int): Long? = lastOrNull {
            it.number == number && it.wireType == ProtoWire.VARINT
        }
            ?.bits
    }
}
