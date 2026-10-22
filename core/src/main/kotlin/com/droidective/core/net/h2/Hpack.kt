package com.droidective.core.net.h2

import com.droidective.core.net.http.Header

/**
 * Reads the header blocks one end of a connection sends (RFC 7541).
 *
 * It must be shown every block that end sends, in order: each may add fields to the table, and the
 * next may name them by their place in it. Miss one and every block after it reads wrong, which is
 * why a block that makes no sense ends the reading rather than being skipped.
 *
 * @param limit the largest table it will keep, whatever the sender asks for. A peer may agree to
 *   more than the 4 KB everyone starts with; this is what a sender would have to ask for before
 *   this stopped believing it.
 */
class HpackDecoder(private val limit: Int = MAX_TABLE) {
    /** The newest field first, which is index 62. */
    private val table = ArrayDeque<Header>()
    private var size = 0
    private var capacity = INITIAL_TABLE

    /** The fields in [block], in the order they were sent. */
    fun decode(block: ByteArray): List<Header> {
        val fields = ArrayList<Header>()
        val cursor = Cursor(block)
        while (cursor.more) {
            val first = cursor.peek()
            when {
                first and INDEXED != 0 -> fields += field(cursor.integer(INDEX_BITS))
                first and INCREMENTAL != 0 -> fields += literal(cursor, NAME_BITS).also(::remember)
                first and SIZE_UPDATE != 0 -> resize(cursor.integer(SIZE_BITS))
                // Without indexing, or never indexed: the same to a reader.
                else -> fields += literal(cursor, LITERAL_BITS)
            }
        }
        return fields
    }

    private fun literal(cursor: Cursor, prefix: Int): Header {
        val index = cursor.integer(prefix)
        val name = if (index == 0) cursor.string() else field(index).name
        return Header(name, cursor.string())
    }

    private fun field(index: Int): Header =
        when {
            index == 0 -> throw Http2Exception("field 0, which there is no such thing as")
            index <= STATIC.size -> STATIC[index - 1]
            else ->
                table.getOrNull(index - STATIC.size - 1)
                    ?: throw Http2Exception("field $index, past the end of the table")
        }

    private fun remember(field: Header) {
        val cost = cost(field)
        // A field bigger than the whole table empties it and is not kept.
        if (cost > capacity) {
            table.clear()
            size = 0
            return
        }
        table.addFirst(field)
        size += cost
        evict()
    }

    private fun resize(wanted: Int) {
        if (wanted > limit) throw Http2Exception("a table of $wanted bytes, past $limit")
        capacity = wanted
        evict()
    }

    private fun evict() {
        while (size > capacity) size -= cost(table.removeLast())
    }

    /** What a field takes up in the table, by RFC 7541's count: its octets, and 32 more. */
    private fun cost(field: Header) = field.name.length + field.value.length + ENTRY_OVERHEAD

    /** Reading through one block: its integers and strings, in their packed forms. */
    private class Cursor(private val bytes: ByteArray) {
        private var at = 0

        val more: Boolean
            get() = at < bytes.size

        fun peek(): Int = bytes[at].toInt() and BYTE

        /** An integer in the low [prefix] bits of this octet, carried on into more when full. */
        fun integer(prefix: Int): Int {
            val mask = (1 shl prefix) - 1
            var value = next() and mask
            if (value < mask) return value
            var shift = 0
            while (true) {
                val octet = next()
                if (shift > MAX_SHIFT) throw Http2Exception("an integer too long to be one")
                value += (octet and SEVEN_BITS) shl shift
                if (octet and CONTINUES == 0) return value
                shift += 7
            }
        }

        /** A string: its length, and its octets, Huffman-coded when the top bit says so. */
        fun string(): String {
            val huffman = peek() and HUFFMAN != 0
            val length = integer(LENGTH_BITS)
            if (length > bytes.size - at) throw Http2Exception("a string longer than its block")
            val octets =
                if (huffman) Huffman.decode(bytes, at, at + length)
                else bytes.copyOfRange(at, at + length)
            at += length
            // Header octets are Latin-1, as HTTP/1.1's are read here: every octet comes back.
            return String(octets, Charsets.ISO_8859_1)
        }

        private fun next(): Int {
            if (at >= bytes.size) throw Http2Exception("a block that stops part-way")
            return bytes[at++].toInt() and BYTE
        }
    }

    companion object {
        /** What both ends start with, until the sender says otherwise. */
        const val INITIAL_TABLE = 4096

        /** A megabyte: far past what anyone asks for, and short of what would hurt. */
        const val MAX_TABLE = 1 shl 20

        private const val INDEXED = 0x80
        private const val INCREMENTAL = 0x40
        private const val SIZE_UPDATE = 0x20
        private const val HUFFMAN = 0x80
        private const val CONTINUES = 0x80
        private const val SEVEN_BITS = 0x7f
        private const val BYTE = 0xff
        private const val INDEX_BITS = 7
        private const val NAME_BITS = 6
        private const val SIZE_BITS = 5
        private const val LITERAL_BITS = 4
        private const val LENGTH_BITS = 7
        private const val MAX_SHIFT = 21
        private const val ENTRY_OVERHEAD = 32

        /** RFC 7541, Appendix A: index 1 to 61. */
        private val STATIC =
            listOf(
                Header(":authority", ""),
                Header(":method", "GET"),
                Header(":method", "POST"),
                Header(":path", "/"),
                Header(":path", "/index.html"),
                Header(":scheme", "http"),
                Header(":scheme", "https"),
                Header(":status", "200"),
                Header(":status", "204"),
                Header(":status", "206"),
                Header(":status", "304"),
                Header(":status", "400"),
                Header(":status", "404"),
                Header(":status", "500"),
                Header("accept-charset", ""),
                Header("accept-encoding", "gzip, deflate"),
                Header("accept-language", ""),
                Header("accept-ranges", ""),
                Header("accept", ""),
                Header("access-control-allow-origin", ""),
                Header("age", ""),
                Header("allow", ""),
                Header("authorization", ""),
                Header("cache-control", ""),
                Header("content-disposition", ""),
                Header("content-encoding", ""),
                Header("content-language", ""),
                Header("content-length", ""),
                Header("content-location", ""),
                Header("content-range", ""),
                Header("content-type", ""),
                Header("cookie", ""),
                Header("date", ""),
                Header("etag", ""),
                Header("expect", ""),
                Header("expires", ""),
                Header("from", ""),
                Header("host", ""),
                Header("if-match", ""),
                Header("if-modified-since", ""),
                Header("if-none-match", ""),
                Header("if-range", ""),
                Header("if-unmodified-since", ""),
                Header("last-modified", ""),
                Header("link", ""),
                Header("location", ""),
                Header("max-forwards", ""),
                Header("proxy-authenticate", ""),
                Header("proxy-authorization", ""),
                Header("range", ""),
                Header("referer", ""),
                Header("refresh", ""),
                Header("retry-after", ""),
                Header("server", ""),
                Header("set-cookie", ""),
                Header("strict-transport-security", ""),
                Header("transfer-encoding", ""),
                Header("user-agent", ""),
                Header("vary", ""),
                Header("via", ""),
                Header("www-authenticate", ""),
            )
    }
}
