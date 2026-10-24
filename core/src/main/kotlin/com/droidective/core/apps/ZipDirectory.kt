package com.droidective.core.apps

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

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

    /** Where [record]'s bytes start: past its local header, whose extra field can differ. */
    fun dataOffset(file: RandomAccessFile, record: ZipRecord): Long {
        val header = ByteBuffer.wrap(read(file, record.headerOffset, LOCAL_HEADER))
        header.order(ByteOrder.LITTLE_ENDIAN)
        if (header.int != LOCAL_SIGNATURE) {
            throw IOException("${record.name} has no local header where the directory says.")
        }
        val nameLength = header.getShort(LOCAL_NAME_LENGTH).toInt() and SHORT_MASK
        val extraLength = header.getShort(LOCAL_EXTRA_LENGTH).toInt() and SHORT_MASK
        return record.headerOffset + LOCAL_HEADER + nameLength + extraLength
    }

    /**
     * Up to [count] bytes from the start of [record]'s contents, inflated when it's compressed.
     *
     * @throws IOException when they can't be read or inflated.
     */
    fun head(file: RandomAccessFile, record: ZipRecord, count: Int): ByteArray {
        val wanted = minOf(count.toLong(), record.size).toInt()
        val start = dataOffset(file, record)
        if (start + record.packed > file.length()) {
            throw IOException("${record.name} runs past the end of the file.")
        }
        if (record.stored) return read(file, start, wanted)
        val inflater = Inflater(true)
        try {
            return inflate(file, start until start + record.packed, inflater, wanted)
        } catch (e: DataFormatException) {
            throw IOException("${record.name} won't inflate: ${e.message}")
        } finally {
            inflater.end()
        }
    }

    /** Inflates [wanted] bytes, reading the compressed [region] a chunk at a time as needed. */
    private fun inflate(
        file: RandomAccessFile,
        region: LongRange,
        inflater: Inflater,
        wanted: Int,
    ): ByteArray {
        val out = ByteArray(wanted)
        val chunk = ByteArray(INFLATE_CHUNK)
        var filled = 0
        var at = region.first
        var padded = false
        while (filled < wanted && !inflater.finished()) {
            if (inflater.needsInput()) {
                val count = minOf(INFLATE_CHUNK.toLong(), region.last + 1 - at).toInt()
                when {
                    count > 0 -> {
                        file.seek(at)
                        file.readFully(chunk, 0, count)
                        inflater.setInput(chunk, 0, count)
                        at += count
                    }
                    // A raw deflate stream can want one byte past its end before it finishes.
                    !padded -> {
                        inflater.setInput(ByteArray(1))
                        padded = true
                    }
                    else -> break
                }
            }
            if (inflater.needsDictionary()) throw DataFormatException("it needs a dictionary")
            filled += inflater.inflate(out, filled, wanted - filled)
        }
        return if (filled == wanted) out else out.copyOf(filled)
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
    private const val LOCAL_SIGNATURE = 0x04034b50
    private const val LOCAL_HEADER = 30
    private const val LOCAL_NAME_LENGTH = 26
    private const val LOCAL_EXTRA_LENGTH = 28
    private const val INFLATE_CHUNK = 64 * 1024
    private const val SHORT_MASK = 0xFFFF
    private const val INT_MASK = 0xFFFFFFFFL
}
