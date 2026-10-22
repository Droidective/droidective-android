package com.droidective.core.net.h2

import java.io.ByteArrayOutputStream

/** HTTP/2 as the tests write it: frames, and header blocks spelled out field by field. */
internal object H2Wire {
    const val DATA = 0x0
    const val HEADERS = 0x1
    const val RST_STREAM = 0x3
    const val SETTINGS = 0x4
    const val PUSH_PROMISE = 0x5
    const val CONTINUATION = 0x9

    const val END_STREAM = 0x1
    const val END_HEADERS = 0x4
    const val PADDED = 0x8
    const val PRIORITY = 0x20

    val PREFACE: ByteArray = FrameCutter.PREFACE

    fun frame(type: Int, flags: Int, stream: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(payload.size ushr 16)
        out.write(payload.size ushr 8)
        out.write(payload.size)
        out.write(type)
        out.write(flags)
        out.write(stream ushr 24)
        out.write(stream ushr 16)
        out.write(stream ushr 8)
        out.write(stream)
        out.write(payload)
        return out.toByteArray()
    }

    /** Literals the table never hears of. */
    fun block(vararg fields: Pair<String, String>): ByteArray = literals(0x00, 4, fields)

    /** Literals each added to the table as it goes, for a later block to name by index. */
    fun indexing(vararg fields: Pair<String, String>): ByteArray = literals(0x40, 6, fields)

    /** A field named by its place: 1–61 the static table, 62 on the newest added. */
    fun indexed(index: Int): ByteArray = integer(index, 7, 0x80)

    /** A request or answer, all in one HEADERS frame, ending the stream unless [end] is false. */
    fun headers(stream: Int, vararg fields: Pair<String, String>, end: Boolean = true): ByteArray =
        frame(HEADERS, END_HEADERS or (if (end) END_STREAM else 0), stream, block(*fields))

    fun data(stream: Int, text: String, end: Boolean = true): ByteArray =
        frame(DATA, if (end) END_STREAM else 0, stream, text.toByteArray())

    fun reset(stream: Int, code: Int): ByteArray =
        frame(RST_STREAM, 0, stream, byteArrayOf(0, 0, 0, code.toByte()))

    /** The app's opening: the preface, then its settings. */
    fun opening(): ByteArray = PREFACE + frame(SETTINGS, 0, 0)

    fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun literals(kind: Int, prefix: Int, fields: Array<out Pair<String, String>>) =
        fields.fold(ByteArray(0)) { block, (name, value) ->
            block + integer(0, prefix, kind) + string(name) + string(value)
        }

    private fun string(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        return integer(bytes.size, 7, 0) + bytes
    }

    /** RFC 7541's integer: the low [prefix] bits of the first octet, carried on when full. */
    fun integer(value: Int, prefix: Int, flags: Int): ByteArray {
        val mask = (1 shl prefix) - 1
        if (value < mask) return byteArrayOf((flags or value).toByte())
        val out = ByteArrayOutputStream()
        out.write(flags or mask)
        var rest = value - mask
        while (rest >= 0x80) {
            out.write((rest and 0x7f) or 0x80)
            rest = rest ushr 7
        }
        out.write(rest)
        return out.toByteArray()
    }
}
