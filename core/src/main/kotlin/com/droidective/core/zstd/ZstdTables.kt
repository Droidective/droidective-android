package com.droidective.core.zstd

/**
 * A zstd bitstream read from its end back to its start, as every FSE and Huffman stream is (RFC
 * 8878, section 4.1): the encoder wrote forward and finished with a 1 bit, so the last byte's
 * highest set bit marks where reading begins. Bits are counted from the stream's first byte, least
 * significant first; [position] is how many are left. A read past the start gives zeros and drives
 * [position] below zero, which is how the end of a Huffman weight stream is noticed.
 */
internal class BackwardBits(private val bytes: ByteArray, private val from: Int, to: Int) {
    var position: Int
        private set

    init {
        if (to <= from) throw ZstdException("an empty bitstream")
        val last = bytes[to - 1].toInt() and BYTE
        if (last == 0) throw ZstdException("a bitstream with no end marker")
        position =
            (to - from - 1) * Byte.SIZE_BITS +
                (Int.SIZE_BITS - 1 - Integer.numberOfLeadingZeros(last))
    }

    /** The next [count] bits, at most 32, taken off the stream. */
    fun read(count: Int): Long {
        position -= count
        return bitsAt(position, count)
    }

    /** The next [count] bits, left on the stream; past its start they read as zeros. */
    fun peek(count: Int): Int = bitsAt(position - count, count).toInt()

    fun skip(count: Int) {
        position -= count
    }

    /** Whether every bit was read and none past the start. */
    val finished: Boolean
        get() = position == 0

    private fun bitsAt(start: Int, count: Int): Long {
        if (count == 0) return 0
        if (start < 0) {
            if (start + count <= 0) return 0
            return bitsAt(0, start + count) shl -start
        }
        val first = start ushr 3
        val shift = start and 7
        var word = 0L
        for (index in 0 until (shift + count + 7 ushr 3)) {
            word =
                word or
                    ((bytes[from + first + index].toLong() and 0xff) shl (Byte.SIZE_BITS * index))
        }
        return (word ushr shift) and ((1L shl count) - 1)
    }
}

/** Bits read forward, least significant first, as an FSE table description is (section 4.1.1). */
internal class ForwardBits(
    private val bytes: ByteArray,
    private val from: Int,
    private val to: Int,
) {
    private var position = 0

    /** Bytes used so far, a part-used one counting whole. */
    val used: Int
        get() = (position + 7) ushr 3

    fun read(count: Int): Int = peek(count).also { position += count }

    fun peek(count: Int): Int {
        var value = 0
        for (index in 0 until count) {
            val bit = position + index
            val at = from + (bit ushr 3)
            if (at >= to) throw ZstdException("a table description longer than its block")
            value = value or (((bytes[at].toInt() ushr (bit and 7)) and 1) shl index)
        }
        return value
    }

    fun skip(count: Int) {
        position += count
    }
}

/**
 * An FSE decoding table: for each state, the symbol it stands for and how to reach the next state —
 * read [bits] more bits and add them to the [baselines] entry.
 */
internal class FseTable(
    val accuracyLog: Int,
    val symbols: IntArray,
    val bits: IntArray,
    val baselines: IntArray,
) {
    companion object {
        /** A table that only ever says [symbol]: RLE mode. */
        fun rle(symbol: Int) = FseTable(0, intArrayOf(symbol), intArrayOf(0), intArrayOf(0))

        /**
         * The table a distribution makes, [counts] being each symbol's share of the `1 <<
         * accuracyLog` states, -1 for "less than one" (section 4.1.1). Laid out as the reference
         * decoder's `FSE_buildDTable` lays it.
         */
        fun build(counts: IntArray, accuracyLog: Int): FseTable {
            val size = 1 shl accuracyLog
            val symbols = IntArray(size)
            val next = IntArray(counts.size)
            var high = size - 1
            for ((symbol, count) in counts.withIndex()) {
                if (count == -1) {
                    symbols[high--] = symbol
                    next[symbol] = 1
                } else {
                    next[symbol] = count
                }
            }
            val step = (size ushr 1) + (size ushr 3) + 3
            var position = 0
            for ((symbol, count) in counts.withIndex()) {
                repeat(count.coerceAtLeast(0)) {
                    symbols[position] = symbol
                    do position = (position + step) and (size - 1) while (position > high)
                }
            }
            if (position != 0) throw ZstdException("a distribution that doesn't fill its table")
            val bits = IntArray(size)
            val baselines = IntArray(size)
            for (state in 0 until size) {
                val following = next[symbols[state]]++
                bits[state] =
                    accuracyLog - (Int.SIZE_BITS - 1 - Integer.numberOfLeadingZeros(following))
                baselines[state] = (following shl bits[state]) - size
            }
            return FseTable(accuracyLog, symbols, bits, baselines)
        }

        /**
         * A table description at [from] (section 4.1.1), read as the reference `FSE_readNCount`
         * reads it: the table, and how many bytes the description took.
         */
        fun read(
            bytes: ByteArray,
            from: Int,
            to: Int,
            maxSymbol: Int,
            maxLog: Int,
        ): Pair<FseTable, Int> {
            val input = ForwardBits(bytes, from, to)
            val accuracyLog = input.read(4) + MIN_ACCURACY
            if (accuracyLog > maxLog)
                throw ZstdException("an accuracy of $accuracyLog, past $maxLog")
            val counts = IntArray(maxSymbol + 1)
            var remaining = (1 shl accuracyLog) + 1
            var threshold = 1 shl accuracyLog
            var width = accuracyLog + 1
            var symbol = 0
            var previousZero = false
            while (remaining > 1 && symbol <= maxSymbol) {
                if (previousZero) {
                    var repeat = input.read(2)
                    symbol += repeat
                    while (repeat == 3) {
                        repeat = input.read(2)
                        symbol += repeat
                    }
                    if (symbol > maxSymbol) throw ZstdException("zero counts past the last symbol")
                }
                val max = (2 * threshold - 1) - remaining
                var count: Int
                if (input.peek(width - 1) < max) {
                    count = input.read(width - 1)
                } else {
                    count = input.read(width)
                    if (count >= threshold) count -= max
                }
                count--
                remaining -= if (count < 0) -count else count
                counts[symbol++] = count
                previousZero = count == 0
                while (remaining < threshold) {
                    width--
                    threshold = threshold ushr 1
                }
            }
            if (remaining != 1) throw ZstdException("a distribution that doesn't add up")
            return build(counts.copyOf(symbol), accuracyLog) to input.used
        }

        private const val MIN_ACCURACY = 5
    }
}

/** One FSE state walking its table. */
internal class FseState(private val table: FseTable) {
    private var state = 0

    val symbol: Int
        get() = table.symbols[state]

    fun start(bits: BackwardBits) {
        state = bits.read(table.accuracyLog).toInt()
    }

    fun next(bits: BackwardBits) {
        state = table.baselines[state] + bits.read(table.bits[state]).toInt()
    }
}

/**
 * A Huffman decoding table for literals (section 4.2): `1 << maxBits` entries, each symbol filling
 * as many as its code's length leaves room for, so peeking [maxBits] bits finds it at once.
 */
internal class HuffmanTable(
    private val maxBits: Int,
    private val symbols: ByteArray,
    private val lengths: ByteArray,
) {
    fun decode(bits: BackwardBits): Byte {
        val index = bits.peek(maxBits)
        bits.skip(lengths[index].toInt())
        return symbols[index]
    }

    companion object {
        /** A tree description at [from] (section 4.2.1): the table, and the bytes it took. */
        fun read(bytes: ByteArray, from: Int, to: Int): Pair<HuffmanTable, Int> {
            if (from >= to) throw ZstdException("a literals block with no tree")
            val header = bytes[from].toInt() and BYTE
            val (weights, used) =
                if (header >= DIRECT) direct(bytes, from + 1, to, header - (DIRECT - 1))
                else compressed(bytes, from + 1, to, header)
            return fromWeights(weights) to used + 1
        }

        private fun direct(bytes: ByteArray, from: Int, to: Int, count: Int): Pair<IntArray, Int> {
            val size = (count + 1) / 2
            if (from + size > to) throw ZstdException("a tree longer than its block")
            val weights =
                IntArray(count) { index ->
                    val byte = bytes[from + index / 2].toInt() and BYTE
                    if (index % 2 == 0) byte ushr 4 else byte and 0xf
                }
            return weights to size
        }

        /**
         * Weights compressed with FSE: two states taking turns, over one table (section 4.2.1.2).
         */
        private fun compressed(
            bytes: ByteArray,
            from: Int,
            to: Int,
            size: Int,
        ): Pair<IntArray, Int> {
            if (size == 0 || from + size > to) throw ZstdException("a tree longer than its block")
            val (table, used) = FseTable.read(bytes, from, from + size, MAX_WEIGHT, MAX_WEIGHT_LOG)
            val bits = BackwardBits(bytes, from + used, from + size)
            val first = FseState(table).apply { start(bits) }
            val second = FseState(table).apply { start(bits) }
            val weights = ArrayList<Int>()
            while (weights.size < MAX_SYMBOLS - 1) {
                weights += first.symbol
                first.next(bits)
                if (bits.position < 0) {
                    weights += second.symbol
                    break
                }
                weights += second.symbol
                second.next(bits)
                if (bits.position < 0) {
                    weights += first.symbol
                    break
                }
            }
            return weights.toIntArray() to size
        }

        /**
         * The table [weights] describe, the last symbol's weight being whatever brings the total to
         * the next power of two (section 4.2.1), laid out as `HUF_readDTableX1` lays it.
         */
        private fun fromWeights(given: IntArray): HuffmanTable {
            var total = 0
            for (weight in given) {
                if (weight > MAX_BITS) throw ZstdException("a Huffman weight of $weight")
                if (weight > 0) total += 1 shl (weight - 1)
            }
            if (total == 0) throw ZstdException("a Huffman tree with nothing in it")
            val maxBits = Int.SIZE_BITS - Integer.numberOfLeadingZeros(total)
            if (maxBits > MAX_BITS) throw ZstdException("a Huffman code $maxBits bits long")
            val rest = (1 shl maxBits) - total
            if (Integer.bitCount(rest) != 1) throw ZstdException("Huffman weights that don't close")
            val weights = given + (Integer.numberOfTrailingZeros(rest) + 1)
            if (weights.count { it == 1 }.let { it < 2 || it % 2 != 0 }) {
                throw ZstdException("a Huffman tree that can't be built")
            }
            val size = 1 shl maxBits
            val symbols = ByteArray(size)
            val lengths = ByteArray(size)
            var position = 0
            for (weight in 1..maxBits) {
                for ((symbol, given) in weights.withIndex()) {
                    if (given != weight) continue
                    val span = 1 shl (weight - 1)
                    symbols.fill(symbol.toByte(), position, position + span)
                    lengths.fill((maxBits + 1 - weight).toByte(), position, position + span)
                    position += span
                }
            }
            return HuffmanTable(maxBits, symbols, lengths)
        }

        private const val DIRECT = 128
        private const val MAX_WEIGHT = 255
        private const val MAX_WEIGHT_LOG = 6
        private const val MAX_SYMBOLS = 256
        private const val MAX_BITS = 11
    }
}

private const val BYTE = 0xff
