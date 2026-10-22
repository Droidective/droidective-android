package com.droidective.core.net.grpc

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets

/**
 * Protocol buffers read without the `.proto` that describes them, as `protoc --decode_raw` reads
 * them: field numbers and what each holds, nested messages opened up.
 *
 * Without the schema a length-delimited field is a string, a message, or bytes, and only a guess
 * can tell which. Text that is all printable is taken for a string; anything else that parses
 * through to its last byte as fields is taken for a message; the rest is bytes.
 */
object ProtoRaw {
    /** One field as it was on the wire. */
    data class Field(val number: Int, val value: Value)

    sealed interface Value {
        /** A varint: an int, a bool, an enum — the wire doesn't say which. */
        data class Varint(val value: Long) : Value

        /** Eight bytes: a double, a fixed64, an sfixed64. */
        data class Fixed64(val value: Long) : Value

        /** Four bytes: a float, a fixed32, an sfixed32. */
        data class Fixed32(val value: Int) : Value

        data class Text(val text: String) : Value

        data class Message(val fields: List<Field>) : Value

        class Bytes(val bytes: ByteArray) : Value
    }

    /** The fields of [bytes], or null when they don't read as a message through to the end. */
    fun parse(bytes: ByteArray): List<Field>? = parse(bytes, depth = 0)

    /** [fields] the way `protoc --decode_raw` prints them. */
    fun render(fields: List<Field>): String = render(fields, indent = 0)

    /** [fields] as [render] writes them, [indent] levels in. */
    internal fun render(fields: List<Field>, indent: Int): String = buildString {
        render(fields, indent)
    }

    /** [bytes] quoted as text when they are UTF-8, and with protoc's escapes when they aren't. */
    internal fun text(bytes: ByteArray): String = utf8(bytes)?.let(::quoted) ?: quoted(bytes)

    /** One field read off the wire, its bytes guessed at when they are length-delimited. */
    internal fun field(wire: WireField, depth: Int = 0): Field {
        val value =
            when (wire.wireType) {
                ProtoWire.VARINT -> Value.Varint(wire.bits)
                ProtoWire.FIXED64 -> Value.Fixed64(wire.bits)
                ProtoWire.FIXED32 -> Value.Fixed32(wire.bits.toInt())
                else -> delimited(checkNotNull(wire.bytes), depth)
            }
        return Field(wire.number, value)
    }

    private fun parse(bytes: ByteArray, depth: Int): List<Field>? =
        ProtoWire.fields(bytes)?.map { field(it, depth) }

    private fun delimited(bytes: ByteArray, depth: Int): Value {
        printable(bytes)?.let {
            return Value.Text(it)
        }
        if (depth < MAX_DEPTH) {
            parse(bytes, depth + 1)?.let {
                return Value.Message(it)
            }
        }
        return Value.Bytes(bytes)
    }

    /**
     * [bytes] as text when they are UTF-8 with nothing unprintable in them but line breaks and
     * tabs.
     */
    private fun printable(bytes: ByteArray): String? =
        utf8(bytes)?.takeIf { it.none { char -> char.isISOControl() && char !in "\n\r\t" } }

    private fun utf8(bytes: ByteArray): String? =
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }

    private fun StringBuilder.render(fields: List<Field>, indent: Int) {
        for ((number, value) in fields) {
            append("  ".repeat(indent)).append(number)
            when (value) {
                is Value.Varint -> append(": ").append(java.lang.Long.toUnsignedString(value.value))
                is Value.Fixed64 -> append(": 0x").append(hex(value.value, LONG_BYTES))
                is Value.Fixed32 -> append(": 0x").append(hex(value.value.toLong(), INT_BYTES))
                is Value.Text -> append(": ").append(quoted(value.text))
                is Value.Bytes -> append(": ").append(quoted(value.bytes))
                is Value.Message -> {
                    append(" {\n")
                    render(value.fields, indent + 1)
                    append("  ".repeat(indent)).append("}")
                }
            }
            append('\n')
        }
    }

    private fun hex(value: Long, bytes: Int): String =
        java.lang.Long.toHexString(value).takeLast(2 * bytes).padStart(2 * bytes, '0')

    /** Text in double quotes, its line breaks, tabs and quotes escaped and the rest as it is. */
    private fun quoted(text: String): String = buildString {
        append('"')
        for (char in text) {
            when (char) {
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(char)
            }
        }
        append('"')
    }

    /**
     * Bytes in double quotes, with C's escapes for what isn't printable ASCII, as protoc writes.
     */
    internal fun quoted(bytes: ByteArray): String = buildString {
        append('"')
        for (byte in bytes) {
            val code = byte.toInt() and BYTE
            when {
                code == '\n'.code -> append("\\n")
                code == '\r'.code -> append("\\r")
                code == '\t'.code -> append("\\t")
                code == '"'.code -> append("\\\"")
                code == '\\'.code -> append("\\\\")
                code in PRINTABLE -> append(code.toChar())
                else -> append('\\').append(Integer.toOctalString(code).padStart(3, '0'))
            }
        }
        append('"')
    }

    private const val LONG_BYTES = 8
    private const val INT_BYTES = 4
    private const val BYTE = 0xff

    /** Past this, a field is shown as bytes: deep enough for any real message, short of a stack. */
    private const val MAX_DEPTH = 32

    private val PRINTABLE = 0x20..0x7e
}
