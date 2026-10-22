package com.droidective.core.zstd

import java.io.IOException

/**
 * Zstandard data that couldn't be decompressed, and what had come out of it before that: a body cut
 * off by what was kept still gives up every block before the cut.
 */
class ZstdException(message: String, val partial: ByteArray = ByteArray(0)) : IOException(message)

/**
 * Zstandard decompression (RFC 8878): what Chrome and its WebView are now often answered in.
 * Frames, skippable frames, every block and literals type, and the three sequence tables in all
 * four modes; no dictionaries, which an HTTP answer doesn't use. The checksum is not checked, as
 * the RFC allows: the bytes came off a TLS connection, which already did.
 */
object Zstd {
    /** What [input] decompresses to, stopping once [limit] bytes have come out. */
    fun decompress(input: ByteArray, limit: Int = Int.MAX_VALUE): ByteArray {
        val out = Output(limit)
        try {
            var at = 0
            while (at < input.size && !out.full) at = frame(input, at, out)
        } catch (e: ZstdException) {
            throw ZstdException(e.message.orEmpty(), out.committed())
        } catch (e: IndexOutOfBoundsException) {
            // Every read is checked against its block; one that isn't is corrupt data, not a bug.
            throw ZstdException("data that runs past its end (${e.message})", out.committed())
        }
        return out.committed()
    }

    private fun frame(input: ByteArray, from: Int, out: Output): Int {
        if (input.size - from < MAGIC_BYTES) throw ZstdException("a frame cut off in its header")
        val magic = littleEndian(input, from, MAGIC_BYTES)
        if (magic and SKIPPABLE_MASK == SKIPPABLE) {
            val size = littleEndian(input, from + MAGIC_BYTES, MAGIC_BYTES)
            return checkedEnd(input, from + 2 * MAGIC_BYTES, size)
        }
        if (magic != MAGIC) throw ZstdException("not zstd: no frame starts here")
        val (header, blocksAt) = FrameHeader.read(input, from + MAGIC_BYTES)
        val frame = Frame(out)
        var at = blocksAt
        while (true) {
            if (input.size - at < BLOCK_HEADER) throw ZstdException("a block cut off in its header")
            val block = littleEndian(input, at, BLOCK_HEADER).toInt()
            val size = block ushr 3
            val contentAt = at + BLOCK_HEADER
            if (size > MAX_BLOCK) throw ZstdException("a block of $size bytes, past 128 KB")
            at =
                when ((block ushr 1) and 3) {
                    RAW ->
                        checkedEnd(input, contentAt, size.toLong()).also {
                            out.write(input, contentAt, size)
                        }
                    RLE ->
                        checkedEnd(input, contentAt, 1).also { out.repeat(input[contentAt], size) }
                    COMPRESSED ->
                        checkedEnd(input, contentAt, size.toLong()).also {
                            frame.block(input, contentAt, it)
                        }
                    else -> throw ZstdException("a block of the reserved type")
                }
            out.commit()
            if (out.full || block and 1 == 1) break
        }
        if (header.checksum) at += CHECKSUM_BYTES
        return at
    }

    private fun checkedEnd(input: ByteArray, from: Int, size: Long): Int {
        if (size > input.size - from) throw ZstdException("a block cut off part-way")
        return from + size.toInt()
    }

    internal fun littleEndian(bytes: ByteArray, from: Int, count: Int): Long {
        var value = 0L
        for (index in 0 until count) {
            value = value or ((bytes[from + index].toLong() and 0xff) shl (Byte.SIZE_BITS * index))
        }
        return value
    }

    private const val MAGIC = 0xFD2FB528L
    private const val SKIPPABLE = 0x184D2A50L
    private const val SKIPPABLE_MASK = 0xFFFFFFF0L
    private const val MAGIC_BYTES = 4
    private const val BLOCK_HEADER = 3
    private const val CHECKSUM_BYTES = 4
    private const val MAX_BLOCK = 128 * 1024
    private const val RAW = 0
    private const val RLE = 1
    private const val COMPRESSED = 2
}

/** What a frame header says that decompression needs (section 3.1.1.1). */
private class FrameHeader(val checksum: Boolean) {
    companion object {
        fun read(input: ByteArray, from: Int): Pair<FrameHeader, Int> {
            if (from >= input.size) throw ZstdException("a frame with no header")
            val descriptor = input[from].toInt() and 0xff
            if (descriptor and RESERVED != 0)
                throw ZstdException("a frame header's reserved bit set")
            val single = descriptor and SINGLE_SEGMENT != 0
            val contentSizeFlag = descriptor ushr 6
            val dictionaryBytes = DICTIONARY_BYTES[descriptor and 3]
            val contentSizeBytes =
                if (contentSizeFlag == 0) (if (single) 1 else 0) else 1 shl contentSizeFlag
            var at = from + 1 + (if (single) 0 else 1)
            if (input.size - at < dictionaryBytes + contentSizeBytes) {
                throw ZstdException("a frame cut off in its header")
            }
            if (Zstd.littleEndian(input, at, dictionaryBytes) != 0L) {
                throw ZstdException("a frame that needs a dictionary")
            }
            at += dictionaryBytes + contentSizeBytes
            return FrameHeader(descriptor and CHECKSUM != 0) to at
        }

        private const val RESERVED = 0x08
        private const val SINGLE_SEGMENT = 0x20
        private const val CHECKSUM = 0x04
        private val DICTIONARY_BYTES = intArrayOf(0, 1, 2, 4)
    }
}

/** What carries from one compressed block of a frame to the next. */
private class Frame(private val out: Output) {
    private var huffman: HuffmanTable? = null
    private var literalLengths: FseTable? = null
    private var offsets: FseTable? = null
    private var matchLengths: FseTable? = null
    private val repeats = longArrayOf(1, 4, 8)
    private val start = out.size

    /** A compressed block, [from] to [to] (section 3.1.1.3). */
    fun block(input: ByteArray, from: Int, to: Int) {
        val (literals, sequencesAt) = literals(input, from, to)
        sequences(input, sequencesAt, to, literals)
    }

    private fun literals(input: ByteArray, from: Int, to: Int): Pair<ByteArray, Int> {
        if (from >= to) throw ZstdException("a compressed block with nothing in it")
        val first = input[from].toInt() and 0xff
        val type = first and 3
        val format = (first ushr 2) and 3
        if (type == RAW_LITERALS || type == RLE_LITERALS) {
            // Sizes of 5, 12 and 20 bits: one header byte, two, or three.
            val headerBytes =
                when (format) {
                    1 -> 2
                    3 -> 3
                    else -> 1
                }
            if (to - from < headerBytes) throw ZstdException("a literals header cut off")
            val header = Zstd.littleEndian(input, from, headerBytes).toInt()
            val size = if (format and 1 == 0) header ushr 3 else header ushr 4
            val at = from + headerBytes
            return if (type == RAW_LITERALS) {
                if (to - at < size) throw ZstdException("literals longer than their block")
                input.copyOfRange(at, at + size) to at + size
            } else {
                if (at >= to) throw ZstdException("an RLE literal missing")
                ByteArray(size) { input[at] } to at + 1
            }
        }
        val headerBytes = COMPRESSED_HEADER_BYTES[format]
        if (to - from < headerBytes) throw ZstdException("a literals header cut off")
        val header = Zstd.littleEndian(input, from, headerBytes)
        val sizeBits = COMPRESSED_SIZE_BITS[format]
        val mask = (1L shl sizeBits) - 1
        val regenerated = ((header ushr 4) and mask).toInt()
        val compressed = ((header ushr (4 + sizeBits)) and mask).toInt()
        var at = from + headerBytes
        val end = at + compressed
        if (end > to) throw ZstdException("literals longer than their block")
        if (type == COMPRESSED_LITERALS) {
            val (table, used) = HuffmanTable.read(input, at, end)
            huffman = table
            at += used
        }
        val table = huffman ?: throw ZstdException("treeless literals with no tree before them")
        val literals = ByteArray(regenerated)
        if (format == 0) {
            stream(input, at, end, table, literals, 0, regenerated)
        } else {
            fourStreams(input, at, end, table, literals)
        }
        return literals to end
    }

    private fun fourStreams(
        input: ByteArray,
        from: Int,
        to: Int,
        table: HuffmanTable,
        literals: ByteArray,
    ) {
        if (to - from < JUMP_TABLE) throw ZstdException("a jump table cut off")
        val sizes = IntArray(3) { Zstd.littleEndian(input, from + 2 * it, 2).toInt() }
        val segment = (literals.size + 3) / 4
        var at = from + JUMP_TABLE
        for (index in 0 until 4) {
            val end = if (index < 3) at + sizes[index] else to
            if (end > to || end <= at) throw ZstdException("a literals stream out of bounds")
            val outFrom = minOf(index * segment, literals.size)
            val outTo = if (index < 3) minOf(outFrom + segment, literals.size) else literals.size
            stream(input, at, end, table, literals, outFrom, outTo)
            at = end
        }
    }

    private fun stream(
        input: ByteArray,
        from: Int,
        to: Int,
        table: HuffmanTable,
        literals: ByteArray,
        outFrom: Int,
        outTo: Int,
    ) {
        val bits = BackwardBits(input, from, to)
        for (index in outFrom until outTo) literals[index] = table.decode(bits)
        if (!bits.finished) throw ZstdException("a literals stream with bits left over")
    }

    private fun sequences(input: ByteArray, from: Int, to: Int, literals: ByteArray) {
        if (from >= to) throw ZstdException("a block with no sequences section")
        val first = input[from].toInt() and 0xff
        var at = from + 1
        val count =
            when {
                first < 128 -> first
                first < 255 -> ((first - 128) shl 8) + byteAt(input, at++, to)
                else -> byteAt(input, at++, to) + (byteAt(input, at++, to) shl 8) + LONG_COUNT
            }
        if (count == 0) {
            out.write(literals, 0, literals.size)
            return
        }
        val modes = byteAt(input, at++, to)
        if (modes and 3 != 0) throw ZstdException("a sequences header's reserved bits set")
        val (ll, afterLl) = table(input, at, to, modes ushr 6, literalLengths, LITERAL_LENGTHS)
        val (of, afterOf) = table(input, afterLl, to, (modes ushr 4) and 3, offsets, OFFSETS)
        val (ml, afterMl) = table(input, afterOf, to, (modes ushr 2) and 3, matchLengths, MATCHES)
        literalLengths = ll
        offsets = of
        matchLengths = ml
        execute(BackwardBits(input, afterMl, to), count, ll, of, ml, literals)
    }

    private fun execute(
        bits: BackwardBits,
        count: Int,
        ll: FseTable,
        of: FseTable,
        ml: FseTable,
        literals: ByteArray,
    ) {
        val literalState = FseState(ll).apply { start(bits) }
        val offsetState = FseState(of).apply { start(bits) }
        val matchState = FseState(ml).apply { start(bits) }
        var literal = 0
        for (index in 0 until count) {
            val offsetCode = offsetState.symbol
            if (offsetCode > MAX_OFFSET_CODE) throw ZstdException("an offset code of $offsetCode")
            val offsetValue = (1L shl offsetCode) + bits.read(offsetCode)
            val matchCode = matchState.symbol
            val match = MATCH_BASE[matchCode] + bits.read(MATCH_BITS[matchCode]).toInt()
            val literalCode = literalState.symbol
            val length = LITERAL_BASE[literalCode] + bits.read(LITERAL_BITS[literalCode]).toInt()
            if (index < count - 1) {
                literalState.next(bits)
                matchState.next(bits)
                offsetState.next(bits)
            }
            if (length > literals.size - literal)
                throw ZstdException("more literals than there are")
            out.write(literals, literal, length)
            literal += length
            val offset = offset(offsetValue, length == 0)
            if (offset > out.size - start) throw ZstdException("an offset before the frame began")
            out.copy(offset.toInt(), match)
            // Past the limit nothing more is kept, and the rest of the block needn't be read.
            if (out.full) return
        }
        if (!bits.finished) throw ZstdException("a sequences stream with bits left over")
        out.write(literals, literal, literals.size - literal)
    }

    /** The offset a sequence's offset value names, keeping the three most recent up to date. */
    private fun offset(value: Long, noLiterals: Boolean): Long {
        if (value > 3) {
            val offset = value - 3
            repeats[2] = repeats[1]
            repeats[1] = repeats[0]
            repeats[0] = offset
            return offset
        }
        // With no literals before it, each repeat code means the next one along.
        val index = (value - 1).toInt() + if (noLiterals) 1 else 0
        if (index == 0) return repeats[0]
        val offset = if (index == 3) repeats[0] - 1 else repeats[index]
        if (offset <= 0) throw ZstdException("a repeated offset of nothing")
        if (index != 1) repeats[2] = repeats[1]
        repeats[1] = repeats[0]
        repeats[0] = offset
        return offset
    }

    /** One symbol type's table, by its mode (section 3.1.1.3.2.1): and where the next begins. */
    private fun table(
        input: ByteArray,
        from: Int,
        to: Int,
        mode: Int,
        previous: FseTable?,
        kind: Kind,
    ): Pair<FseTable, Int> =
        when (mode) {
            PREDEFINED -> kind.predefined to from
            RLE_MODE -> FseTable.rle(byteAt(input, from, to)) to from + 1
            COMPRESSED_MODE -> {
                val (table, used) = FseTable.read(input, from, to, kind.maxSymbol, kind.maxLog)
                table to from + used
            }
            else -> (previous ?: throw ZstdException("a repeated table with none before")) to from
        }

    private fun byteAt(input: ByteArray, at: Int, to: Int): Int {
        if (at >= to) throw ZstdException("a sequences header cut off")
        return input[at].toInt() and 0xff
    }

    /** A symbol type: its largest symbol, its largest accuracy, its predefined table. */
    private class Kind(val maxSymbol: Int, val maxLog: Int, counts: IntArray, log: Int) {
        val predefined: FseTable by lazy { FseTable.build(counts, log) }
    }

    private companion object {
        const val RAW_LITERALS = 0
        const val RLE_LITERALS = 1
        const val COMPRESSED_LITERALS = 2
        val COMPRESSED_HEADER_BYTES = intArrayOf(3, 3, 4, 5)
        val COMPRESSED_SIZE_BITS = intArrayOf(10, 10, 14, 18)
        const val JUMP_TABLE = 6
        const val LONG_COUNT = 0x7F00
        const val PREDEFINED = 0
        const val RLE_MODE = 1
        const val COMPRESSED_MODE = 2
        const val MAX_OFFSET_CODE = 31

        // Section 3.1.1.3.2.2, the default distributions.
        val LITERAL_LENGTHS =
            Kind(
                35,
                9,
                intArrayOf(4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1) +
                    intArrayOf(2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1) +
                    intArrayOf(-1, -1, -1, -1),
                6,
            )
        val MATCHES =
            Kind(
                52,
                9,
                intArrayOf(1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1) +
                    intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) +
                    intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1) +
                    intArrayOf(-1, -1, -1, -1, -1),
                6,
            )
        val OFFSETS =
            Kind(
                31,
                8,
                intArrayOf(1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1) +
                    intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1),
                5,
            )

        // Tables 16 and 17: each code's baseline and the bits added to it.
        val LITERAL_BASE =
            IntArray(16) { it } +
                intArrayOf(16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048) +
                intArrayOf(4096, 8192, 16384, 32768, 65536)
        val LITERAL_BITS =
            IntArray(16) +
                intArrayOf(1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val MATCH_BASE =
            IntArray(32) { it + 3 } +
                intArrayOf(35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027) +
                intArrayOf(2051, 4099, 8195, 16387, 32771, 65539)
        val MATCH_BITS =
            IntArray(32) +
                intArrayOf(1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
    }
}

/**
 * What has been decompressed, growing as it goes and stopping at [limit]. Only what whole blocks
 * produced is [committed]: a block that turns out corrupt part-way leaves nothing of itself.
 */
private class Output(private val limit: Int) {
    private var bytes = ByteArray(INITIAL)
    var size = 0
        private set

    private var kept = 0

    val full: Boolean
        get() = size >= limit

    fun write(source: ByteArray, from: Int, count: Int) {
        val taking = room(count)
        System.arraycopy(source, from, bytes, size, taking)
        size += taking
    }

    fun repeat(byte: Byte, count: Int) {
        val taking = room(count)
        bytes.fill(byte, size, size + taking)
        size += taking
    }

    /** [count] bytes from [distance] back, which may overlap what they write. */
    fun copy(distance: Int, count: Int) {
        val taking = room(count)
        var from = size - distance
        for (index in 0 until taking) bytes[size++] = bytes[from++]
    }

    fun commit() {
        kept = size
    }

    fun committed(): ByteArray = bytes.copyOf(kept)

    /** How much of [count] fits under the limit, the buffer grown to take it. */
    private fun room(count: Int): Int {
        val taking = minOf(count, limit - size).coerceAtLeast(0)
        if (size + taking > bytes.size) {
            bytes = bytes.copyOf(maxOf(size + taking, minOf(bytes.size * 2, limit)))
        }
        return taking
    }

    private companion object {
        const val INITIAL = 64 * 1024
    }
}
