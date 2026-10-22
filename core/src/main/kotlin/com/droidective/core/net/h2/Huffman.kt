package com.droidective.core.net.h2

import java.io.ByteArrayOutputStream

/**
 * HPACK's Huffman code (RFC 7541, Appendix B), which a peer may use for any header string.
 *
 * The code is canonical, so the lengths alone fix it: taken in order of length and then of symbol,
 * each symbol's code is one more than the last, shifted left by however much longer it is. The
 * tests hold that against the RFC's own table.
 */
internal object Huffman {
    /**
     * How many bits each symbol's code takes, sixteen symbols a row: the 256 octets, then
     * end-of-string on a row of its own.
     */
    private val LENGTHS =
        intArrayOf(13, 23, 28, 28, 28, 28, 28, 28, 28, 24, 30, 28, 28, 30, 28, 28) +
            intArrayOf(28, 28, 28, 28, 28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 28) +
            intArrayOf(6, 10, 10, 12, 13, 6, 8, 11, 10, 10, 8, 11, 8, 6, 6, 6) +
            intArrayOf(5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 7, 8, 15, 6, 12, 10) +
            intArrayOf(13, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7) +
            intArrayOf(7, 7, 7, 7, 7, 7, 7, 7, 8, 7, 8, 13, 19, 13, 14, 6) +
            intArrayOf(15, 5, 6, 5, 6, 5, 6, 6, 6, 5, 7, 7, 6, 6, 6, 5) +
            intArrayOf(6, 7, 6, 5, 5, 6, 7, 7, 7, 7, 7, 15, 11, 14, 13, 28) +
            intArrayOf(20, 22, 20, 20, 22, 22, 22, 23, 22, 23, 23, 23, 23, 23, 24, 23) +
            intArrayOf(24, 24, 22, 23, 24, 23, 23, 23, 23, 21, 22, 23, 22, 23, 23, 24) +
            intArrayOf(22, 21, 20, 22, 22, 23, 23, 21, 23, 22, 22, 24, 21, 22, 23, 23) +
            intArrayOf(21, 21, 22, 21, 23, 22, 23, 23, 20, 22, 22, 22, 23, 22, 22, 23) +
            intArrayOf(26, 26, 20, 19, 22, 23, 22, 25, 26, 26, 26, 27, 27, 26, 24, 25) +
            intArrayOf(19, 21, 26, 27, 27, 26, 27, 24, 21, 21, 26, 26, 28, 27, 27, 27) +
            intArrayOf(20, 24, 20, 21, 22, 21, 21, 23, 22, 22, 25, 25, 24, 24, 26, 23) +
            intArrayOf(26, 27, 26, 26, 27, 27, 27, 27, 27, 28, 27, 27, 27, 27, 27, 26) +
            intArrayOf(30)

    private const val SYMBOLS = 257
    private const val EOS = 256
    private const val MAX_PADDING = 7

    /** Each symbol's code, aligned to the least significant bit. */
    private val codes: IntArray = canonical()

    /**
     * The code as a tree, two slots a node: a node's child for bit b is at `2 * node + b`. A
     * positive entry is the next node, a negative one the symbol `-entry - 1` at a leaf, and zero —
     * which the root, node 0, can never be as somebody's child — is no such code.
     */
    private val tree: IntArray = build()

    /** The octets [bytes] from [from] to [to] encode. */
    fun decode(bytes: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArrayOutputStream(2 * (to - from))
        var node = 0
        var sinceSymbol = 0
        var allOnes = true
        for (at in from until to) {
            val octet = bytes[at].toInt()
            for (shift in 7 downTo 0) {
                val bit = (octet shr shift) and 1
                val next = tree[2 * node + bit]
                if (next == 0) throw Http2Exception("a Huffman code that isn't one")
                sinceSymbol++
                if (bit == 0) allOnes = false
                if (next > 0) {
                    node = next
                    continue
                }
                val symbol = -next - 1
                if (symbol == EOS) throw Http2Exception("end-of-string inside a string")
                out.write(symbol)
                node = 0
                sinceSymbol = 0
                allOnes = true
            }
        }
        // What is left over is padding: the start of end-of-string, so ones, and under an octet.
        if (sinceSymbol > MAX_PADDING || !allOnes) throw Http2Exception("bad Huffman padding")
        return out.toByteArray()
    }

    private fun canonical(): IntArray {
        val order = (0 until SYMBOLS).sortedWith(compareBy({ LENGTHS[it] }, { it }))
        val codes = IntArray(SYMBOLS)
        var code = 0
        var previous = LENGTHS[order.first()]
        for ((index, symbol) in order.withIndex()) {
            val length = LENGTHS[symbol]
            if (index > 0) code = (code + 1) shl (length - previous)
            previous = length
            codes[symbol] = code
        }
        return codes
    }

    private fun build(): IntArray {
        // A complete code over 257 symbols has 256 inner nodes, each with two slots.
        val tree = IntArray(2 * (SYMBOLS - 1))
        var nodes = 1
        for (symbol in 0 until SYMBOLS) {
            var node = 0
            for (shift in LENGTHS[symbol] - 1 downTo 0) {
                val slot = 2 * node + ((codes[symbol] shr shift) and 1)
                if (shift == 0) {
                    tree[slot] = -symbol - 1
                } else {
                    if (tree[slot] == 0) tree[slot] = nodes++
                    node = tree[slot]
                }
            }
        }
        return tree
    }
}
