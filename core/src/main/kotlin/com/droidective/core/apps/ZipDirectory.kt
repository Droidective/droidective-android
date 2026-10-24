package com.droidective.core.apps

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One file in a zip, as its central directory records it.
 *
 * @property packed the bytes it takes in the zip: [size] itself when [stored].
 * @property headerOffset where its local file header starts.
 */
data class ZipRecord(
    val name: String,
    val size: Long,
    val packed: Long,
    val stored: Boolean,
    val headerOffset: Long,
)

/**
 * A zip's central directory, read directly: `java.util.zip` gives no offsets, and an APK's signing
 * block and its native code's alignment are both a matter of where things sit in the file.
 */
object ZipDirectory {
    /** Where the central directory starts, how long it is, and how many entries it holds. */
    data class End(val offset: Long, val size: Long, val entries: Int)

    /** The end-of-central-directory record, searched for from the end of the file. */
    fun end(file: RandomAccessFile): End? {
        val length = file.length()
        val tail = minOf(length, MAX_EOCD).toInt()
        if (tail < EOCD_SIZE) return null
        val bytes = read(file, length - tail, tail)
        for (at in tail - EOCD_SIZE downTo 0) {
            val buffer = ByteBuffer.wrap(bytes, at, EOCD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.int != EOCD_SIGNATURE) continue
            buffer.position(at + EOCD_ENTRIES)
            val entries = buffer.short.toInt() and SHORT_MASK
            val size = buffer.int.toLong() and INT_MASK
            val offset = buffer.int.toLong() and INT_MASK
            return End(offset, size, entries)
        }
        return null
    }

    /**
     * Every entry, in the directory's order. Folder entries are left out: an APK's paths are what
     * make its folders.
     *
     * @throws IOException when [file] has no readable central directory.
     */
    fun entries(file: RandomAccessFile): List<ZipRecord> {
        val end = end(file) ?: throw IOException("It isn't a zip: there's no central directory.")
        if (end.offset == INT_MASK) throw IOException(ZIP64)
        if (end.offset + end.size > file.length() || end.size > Int.MAX_VALUE) {
            throw IOException("Its central directory runs past the end: is the file cut short?")
        }
        val buffer = ByteBuffer.wrap(read(file, end.offset, end.size.toInt()))
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val records = ArrayList<ZipRecord>(end.entries)
        try {
            repeat(end.entries) {
                if (buffer.int != CENTRAL_SIGNATURE) throw IOException(MALFORMED)
                record(buffer)?.let(records::add)
            }
        } catch (_: BufferUnderflowException) {
            throw IOException(MALFORMED)
        } catch (_: IllegalArgumentException) {
            throw IOException(MALFORMED)
        }
        return records
    }

    /** One central directory header, read from just past its signature. */
    private fun record(buffer: ByteBuffer): ZipRecord? {
        skip(buffer, VERSIONS_AND_FLAGS)
        val method = buffer.short.toInt() and SHORT_MASK
        skip(buffer, TIME_DATE_CRC)
        val packed = buffer.int.toLong() and INT_MASK
        val size = buffer.int.toLong() and INT_MASK
        val nameLength = buffer.short.toInt() and SHORT_MASK
        val extraLength = buffer.short.toInt() and SHORT_MASK
        val commentLength = buffer.short.toInt() and SHORT_MASK
        skip(buffer, DISK_AND_ATTRIBUTES)
        val offset = buffer.int.toLong() and INT_MASK
        val name = ByteArray(nameLength).also(buffer::get).toString(Charsets.UTF_8)
        skip(buffer, extraLength + commentLength)
        if (INT_MASK in listOf(size, packed, offset)) throw IOException(ZIP64)
        if (name.endsWith('/')) return null
        return ZipRecord(name, size, packed, method == STORED, offset)
    }

    private fun skip(buffer: ByteBuffer, count: Int) {
        buffer.position(buffer.position() + count)
    }

    internal fun read(file: RandomAccessFile, at: Long, size: Int): ByteArray {
        val bytes = ByteArray(size)
        file.seek(at)
        file.readFully(bytes)
        return bytes
    }

    private const val ZIP64 = "It's a zip64 file, which this doesn't read."
    private const val MALFORMED = "Its central directory is malformed."
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val EOCD_SIZE = 22
    private const val EOCD_ENTRIES = 10
    private const val MAX_EOCD = 65_535L + EOCD_SIZE
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val VERSIONS_AND_FLAGS = 6
    private const val TIME_DATE_CRC = 8
    private const val DISK_AND_ATTRIBUTES = 8
    private const val STORED = 0
    private const val SHORT_MASK = 0xFFFF
    private const val INT_MASK = 0xFFFFFFFFL
}
