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
    fun parse(bytes: ByteArray): List<Field>? = parse(bytes, 0, bytes.size, depth = 0)

    /** [fields] the way `protoc --decode_raw` prints them. */
    fun render(fields: List<Field>): String = buildString { render(fields, indent = 0) }

    private fun parse(bytes: ByteArray, from: Int, to: Int, depth: Int): List<Field>? {
        val reader = Reader(bytes, from, to)
        val fields = ArrayList<Field>()
        while (reader.more) {
            val tag = reader.varint() ?: return null
            val number = (tag ushr WIRE_BITS).toInt()
            if (tag ushr WIRE_BITS > MAX_FIELD || number < 1) return null
            val value =
                when ((tag and WIRE_MASK).toInt()) {
                    VARINT -> Value.Varint(reader.varint() ?: return null)
                    FIXED64 -> Value.Fixed64(reader.fixed(LONG_BYTES) ?: return null)
                    FIXED32 -> Value.Fixed32((reader.fixed(INT_BYTES) ?: return null).toInt())
                    LENGTH -> {
                        val length = reader.varint() ?: return null
                        if (length < 0 || length > reader.left) return null
                        val start = reader.at
                        reader.skip(length.toInt())
                        delimited(bytes, start, start + length.toInt(), depth)
                    }
                    // Groups (3 and 4) are long gone, and 6 and 7 never existed.
                    else -> return null
                }
            fields += Field(number, value)
        }
        return fields
    }

    private fun delimited(bytes: ByteArray, from: Int, to: Int, depth: Int): Value {
        val slice = bytes.copyOfRange(from, to)
        printable(slice)?.let {
            return Value.Text(it)
        }
        if (depth < MAX_DEPTH) {
            parse(bytes, from, to, depth + 1)?.let {
                return Value.Message(it)
            }
        }
        return Value.Bytes(slice)
    }

    /**
     * [bytes] as text when they are UTF-8 with nothing unprintable in them but line breaks and
     * tabs.
     */
    private fun printable(bytes: ByteArray): String? {
        val text =
            try {
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                return null
            }
        return text.takeIf { it.none { char -> char.isISOControl() && char !in "\n\r\t" } }
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
    private fun quoted(bytes: ByteArray): String = buildString {
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

    /** Reading through one message's bytes. Every read says null rather than run off the end. */
    private class Reader(private val bytes: ByteArray, from: Int, private val to: Int) {
        var at = from
            private set

        val more: Boolean
            get() = at < to

        val left: Int
            get() = to - at

        fun varint(): Long? {
            var value = 0L
            var shift = 0
            while (at < to && shift < Long.SIZE_BITS) {
                val byte = bytes[at++].toInt() and BYTE
                value = value or ((byte and SEVEN_BITS).toLong() shl shift)
                if (byte and CONTINUES == 0) return value
                shift += 7
            }
            return null
        }

        fun fixed(count: Int): Long? {
            if (left < count) return null
            var value = 0L
            for (index in 0 until count) {
                value = value or ((bytes[at + index].toLong() and 0xff) shl (index * 8))
            }
            at += count
            return value
        }

        fun skip(count: Int) {
            at += count
        }
    }

    private const val WIRE_BITS = 3
    private const val WIRE_MASK = 0x7L
    private const val VARINT = 0
    private const val FIXED64 = 1
    private const val LENGTH = 2
    private const val FIXED32 = 5
    private const val MAX_FIELD = (1L shl 29) - 1
    private const val LONG_BYTES = 8
    private const val INT_BYTES = 4
    private const val BYTE = 0xff
    private const val SEVEN_BITS = 0x7f
    private const val CONTINUES = 0x80

    /** Past this, a field is shown as bytes: deep enough for any real message, short of a stack. */
    private const val MAX_DEPTH = 32

    private val PRINTABLE = 0x20..0x7e
}
