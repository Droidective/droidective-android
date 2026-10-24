package com.droidective.core.apps

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What a dex file's header says it holds. The counts are of its ID tables, so [methods] and
 * [fields] are the references the file makes, its own and the ones it calls, which is what the 64K
 * limit counts per file.
 *
 * @property version the format: 35, 37, 38, 39, 40 or 41.
 */
data class DexHeader(
    val name: String,
    val version: Int,
    val bytes: Long,
    val strings: Long,
    val types: Long,
    val fields: Long,
    val methods: Long,
    val classes: Long,
)

object DexHeaders {
    /** The bytes [parse] reads. */
    const val SIZE = 0x70

    /** The most method references one dex file can make. */
    const val METHOD_LIMIT = 65_536

    /**
     * The header at the start of [bytes], or null when they don't start one. A version 41 container
     * holds several dex files one after another; this is the first of them.
     */
    fun parse(name: String, bytes: ByteArray): DexHeader? {
        if (bytes.size < SIZE) return null
        if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC) || bytes[MAGIC_END] != 0.toByte()) {
            return null
        }
        val version = String(bytes, 4, 3, Charsets.US_ASCII).toIntOrNull() ?: return null
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (header.getInt(ENDIAN_TAG) != ENDIAN_CONSTANT) return null
        fun count(at: Int) = header.getInt(at).toLong() and 0xFFFFFFFFL
        return DexHeader(
            name = name,
            version = version,
            bytes = count(FILE_SIZE),
            strings = count(STRING_IDS),
            types = count(TYPE_IDS),
            fields = count(FIELD_IDS),
            methods = count(METHOD_IDS),
            classes = count(CLASS_DEFS),
        )
    }

    /**
     * The first Android release that reads dex [version], as source.android.com's dex format page
     * gives it, or null for 035, which every release reads. 041 is experimental in Android 16.
     */
    fun firstAndroid(version: Int): String? =
        when (version) {
            37 -> "7.0"
            38 -> "8.0"
            39 -> "9"
            40 -> "10"
            41 -> "16"
            else -> null
        }

    private val MAGIC = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), 0x0a)
    private const val MAGIC_END = 7
    private const val FILE_SIZE = 0x20
    private const val ENDIAN_TAG = 0x28
    private const val ENDIAN_CONSTANT = 0x12345678
    private const val STRING_IDS = 0x38
    private const val TYPE_IDS = 0x40
    private const val FIELD_IDS = 0x50
    private const val METHOD_IDS = 0x58
    private const val CLASS_DEFS = 0x60
}
