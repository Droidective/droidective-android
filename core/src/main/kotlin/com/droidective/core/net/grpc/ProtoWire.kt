package com.droidective.core.net.grpc

/**
 * One field as the protobuf wire has it: its number, its wire type, and its value — the bits of a
 * number, or the bytes of anything length-delimited, which only a schema (or a guess) can name.
 */
internal class WireField(val number: Int, val wireType: Int, val bits: Long, val bytes: ByteArray?)

/** The protobuf wire format, with nothing guessed: fields, numbers and lengths. */
internal object ProtoWire {
    const val VARINT = 0
    const val FIXED64 = 1
    const val LENGTH = 2
    const val FIXED32 = 5

    /** The fields of [bytes], or null when they don't read as fields through to the end. */
    fun fields(bytes: ByteArray): List<WireField>? {
        val reader = Reader(bytes, 0, bytes.size)
        val fields = ArrayList<WireField>()
        while (reader.more) fields += field(reader) ?: return null
        return fields
    }

    /** The varints a packed repeated field holds, or null when its bytes aren't varints. */
    fun varints(bytes: ByteArray): List<Long>? {
        val reader = Reader(bytes, 0, bytes.size)
        val values = ArrayList<Long>()
        while (reader.more) values += reader.varint() ?: return null
        return values
    }

    /** The fixed-width values a packed repeated field holds, each [width] bytes. */
    fun fixed(bytes: ByteArray, width: Int): List<Long>? {
        if (bytes.size % width != 0) return null
        val reader = Reader(bytes, 0, bytes.size)
        return List(bytes.size / width) { checkNotNull(reader.fixed(width)) }
    }

    private fun field(reader: Reader): WireField? {
        val tag = reader.varint() ?: return null
        val number = tag ushr WIRE_BITS
        if (number < 1 || number > MAX_FIELD) return null
        val wireType = (tag and WIRE_MASK).toInt()
        val bits: Long
        var bytes: ByteArray? = null
        when (wireType) {
            VARINT -> bits = reader.varint() ?: return null
            FIXED64 -> bits = reader.fixed(LONG_BYTES) ?: return null
            FIXED32 -> bits = reader.fixed(INT_BYTES) ?: return null
            LENGTH -> {
                val length = reader.varint() ?: return null
                if (length < 0 || length > reader.left) return null
                bits = length
                bytes = reader.take(length.toInt())
            }
            // Groups (3 and 4) are long gone, and 6 and 7 never existed.
            else -> return null
        }
        return WireField(number.toInt(), wireType, bits, bytes)
    }

    /** Reading through a message's bytes. Every read says null rather than run off the end. */
    private class Reader(private val bytes: ByteArray, from: Int, private val to: Int) {
        private var at = from

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

        /** [count] bytes, least significant first, as protobuf's fixed widths are. */
        fun fixed(count: Int): Long? {
            if (left < count) return null
            var value = 0L
            for (index in 0 until count) {
                value =
                    value or ((bytes[at + index].toLong() and 0xff) shl (index * Byte.SIZE_BITS))
            }
            at += count
            return value
        }

        fun take(count: Int): ByteArray = bytes.copyOfRange(at, at + count).also { at += count }
    }

    private const val WIRE_BITS = 3
    private const val WIRE_MASK = 0x7L
    private const val MAX_FIELD = (1L shl 29) - 1
    private const val LONG_BYTES = 8
    private const val INT_BYTES = 4
    private const val BYTE = 0xff
    private const val SEVEN_BITS = 0x7f
    private const val CONTINUES = 0x80
}
