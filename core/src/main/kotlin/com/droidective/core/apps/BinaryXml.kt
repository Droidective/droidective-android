package com.droidective.core.apps

import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android's compiled XML, the form an APK's AndroidManifest.xml is in, turned back into XML text to
 * read. Values are shown the way `aapt2 dump xmltree` reads them; a reference to a resource is `@`
 * and its name when [names] knows it, its id otherwise.
 */
object BinaryXml {
    /**
     * @param names a resource's name from its id, such as `string/app_name` for the app's own or
     *   `android:style/Theme` for another package's; null when it isn't known.
     * @throws IOException when [bytes] aren't compiled XML.
     */
    fun decode(bytes: ByteArray, names: (Int) -> String? = { null }): String {
        val root =
            try {
                Reader(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), names).read()
            } catch (_: BufferUnderflowException) {
                throw IOException(MALFORMED)
            } catch (_: IndexOutOfBoundsException) {
                throw IOException(MALFORMED)
            } catch (_: IllegalArgumentException) {
                throw IOException(MALFORMED)
            }
        return buildString {
            appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            write(root, 0)
        }
            .trimEnd()
    }

    /** An element as read: its name, its attributes in order, and what it holds. */
    private class Element(val name: String, val attributes: List<Pair<String, String>>) {
        val children = mutableListOf<Element>()
        val text = StringBuilder()
    }

    private class Reader(private val buffer: ByteBuffer, private val names: (Int) -> String?) {
        private var strings: List<String> = emptyList()
        private var resourceIds = IntArray(0)
        private val prefixes = mutableMapOf<String, String>()
        private val declared = mutableListOf<Pair<String, String>>()

        fun read(): Element {
            val type = buffer.short.toInt() and SHORT_MASK
            val headerSize = buffer.short.toInt() and SHORT_MASK
            val size = buffer.int
            if (type != XML || size > buffer.limit() || size < headerSize) {
                throw IOException("It isn't compiled XML.")
            }
            buffer.position(headerSize)
            buffer.limit(size)
            val open = ArrayDeque<Element>()
            var root: Element? = null
            while (buffer.remaining() >= CHUNK_HEADER) {
                val start = buffer.position()
                val chunkType = buffer.short.toInt() and SHORT_MASK
                val chunkHeader = buffer.short.toInt() and SHORT_MASK
                val chunkSize = buffer.int
                if (
                    chunkSize > buffer.limit() - start ||
                        chunkHeader < CHUNK_HEADER ||
                        chunkHeader > chunkSize
                ) {
                    throw IOException(MALFORMED)
                }
                when (chunkType) {
                    STRING_POOL -> strings = stringPool(start, chunkHeader)
                    RESOURCE_MAP -> resourceIds = resourceMap(start, chunkHeader, chunkSize)
                    START_NAMESPACE -> namespace(start, chunkHeader, opening = true)
                    END_NAMESPACE -> namespace(start, chunkHeader, opening = false)
                    START_ELEMENT -> {
                        if (open.size >= MAX_DEPTH) throw IOException("It nests too deep to read.")
                        val element = element(start, chunkHeader)
                        open.lastOrNull()?.children?.add(element)
                        if (open.isEmpty() && root == null) root = element
                        open.addLast(element)
                    }
                    END_ELEMENT -> open.removeLastOrNull() ?: throw IOException(MALFORMED)
                    CDATA -> {
                        buffer.position(start + chunkHeader)
                        string(buffer.int)?.let { open.lastOrNull()?.text?.append(it.trim()) }
                    }
                }
                buffer.position(start + chunkSize)
            }
            return root ?: throw IOException("It has no elements.")
        }

        private fun stringPool(start: Int, headerSize: Int): List<String> {
            buffer.position(start + CHUNK_HEADER)
            val count = buffer.int
            buffer.int // styles
            val utf8 = buffer.int and UTF8_FLAG != 0
            val stringsStart = buffer.int
            if (count < 0 || count > buffer.limit() / 4) throw IOException(MALFORMED)
            buffer.position(start + headerSize)
            val offsets = IntArray(count) { buffer.int }
            return offsets.map { offset ->
                val at = start + stringsStart + offset
                if (utf8) utf8String(at) else utf16String(at)
            }
        }

        private fun utf16String(at: Int): String {
            var length = buffer.getShort(at).toInt() and SHORT_MASK
            var from = at + 2
            if (length and HIGH_BIT_16 != 0) {
                length =
                    (length and LOW_15) shl 16 or (buffer.getShort(from).toInt() and SHORT_MASK)
                from += 2
            }
            if (from + length * 2L > buffer.limit()) throw IOException(MALFORMED)
            val chars = CharArray(length) { buffer.getChar(from + it * 2) }
            return String(chars)
        }

        /** UTF-8 strings give their length in characters, then in bytes; only the bytes count. */
        private fun utf8String(at: Int): String {
            var from = at + utf8LengthSize(at)
            val length = utf8Length(from)
            from += utf8LengthSize(from)
            if (from + length > buffer.limit()) throw IOException(MALFORMED)
            val bytes = ByteArray(length) { buffer.get(from + it) }
            return bytes.toString(Charsets.UTF_8)
        }

        private fun utf8Length(at: Int): Int {
            val first = buffer.get(at).toInt() and BYTE_MASK
            return if (first and HIGH_BIT_8 != 0) {
                (first and LOW_7) shl 8 or (buffer.get(at + 1).toInt() and BYTE_MASK)
            } else {
                first
            }
        }

        private fun utf8LengthSize(at: Int) =
            if (buffer.get(at).toInt() and HIGH_BIT_8 != 0) 2 else 1

        private fun resourceMap(start: Int, headerSize: Int, size: Int): IntArray {
            buffer.position(start + headerSize)
            return IntArray((size - headerSize) / 4) { buffer.int }
        }

        private fun namespace(start: Int, headerSize: Int, opening: Boolean) {
            buffer.position(start + headerSize)
            val prefix = string(buffer.int).orEmpty()
            val uri = string(buffer.int).orEmpty()
            if (opening) {
                prefixes[uri] = prefix
                declared += prefix to uri
            }
        }

        private fun element(start: Int, headerSize: Int): Element {
            buffer.position(start + headerSize)
            buffer.int // namespace
            val name = string(buffer.int).orEmpty()
            val attributeStart = buffer.short.toInt() and SHORT_MASK
            val attributeSize = buffer.short.toInt() and SHORT_MASK
            val attributeCount = buffer.short.toInt() and SHORT_MASK
            val attributes =
                declared.map { (prefix, uri) -> "xmlns:$prefix" to uri } +
                    (0 until attributeCount).map { index ->
                        attribute(start + headerSize + attributeStart + index * attributeSize)
                    }
            declared.clear()
            return Element(name, attributes)
        }

        private fun attribute(at: Int): Pair<String, String> {
            buffer.position(at)
            val namespace = string(buffer.int)
            val nameIndex = buffer.int
            val raw = string(buffer.int)
            buffer.short // size
            buffer.get() // res0
            val type = buffer.get().toInt() and BYTE_MASK
            val data = buffer.int
            val name =
                string(nameIndex)?.takeIf { it.isNotEmpty() }
                    ?: resourceIds.getOrNull(nameIndex)?.let { id ->
                        names(id)?.substringAfterLast('/') ?: "0x%08x".format(id)
                    }
                    ?: "attr$nameIndex"
            val prefix = namespace?.let { prefixes[it] ?: it }
            val named =
                if (namespace == ANDROID && (type == INT_DEC || type == INT_HEX)) {
                    manifestName(name, data)
                } else {
                    null
                }
            return (if (prefix.isNullOrEmpty()) name else "$prefix:$name") to
                (raw ?: named ?: ResValues.show(type, data, ::string, names))
        }

        private fun string(index: Int): String? =
            if (index == NO_ENTRY) null else strings.getOrNull(index)
    }

    /**
     * What [data] stands for in the android: attribute [name], such as `singleTask` or
     * `signature|privileged`; null when the attribute has no names or [data] isn't all named.
     */
    internal fun manifestName(name: String, data: Int): String? {
        val (flags, values) = ManifestAttributes.names[name] ?: return null
        if (!flags || data == 0) return values.firstOrNull { it.second == data }?.first
        var rest = data
        val parts = mutableListOf<String>()
        values
            .filter { it.second != 0 }
            .sortedByDescending { it.second.toUInt() }
            .forEach { (flag, value) ->
                if (rest != 0 && rest and value == value) {
                    parts += flag
                    rest = rest and value.inv()
                }
            }
        return if (rest == 0) parts.reversed().joinToString("|") else null
    }

    private fun StringBuilder.write(element: Element, depth: Int) {
        val indent = "    ".repeat(depth)
        append(indent).append('<').append(element.name)
        val attributes = element.attributes
        if (attributes.size == 1) {
            append(' ').attribute(attributes.single())
        } else {
            attributes.forEach { append('\n').append(indent).append("    ").attribute(it) }
        }
        val text = element.text.toString()
        when {
            element.children.isEmpty() && text.isEmpty() -> appendLine(" />")
            element.children.isEmpty() -> {
                append('>').append(escape(text, attribute = false))
                appendLine("</${element.name}>")
            }
            else -> {
                appendLine(">")
                if (text.isNotEmpty()) appendLine(indent + "    " + escape(text, attribute = false))
                element.children.forEach { write(it, depth + 1) }
                append(indent).appendLine("</${element.name}>")
            }
        }
    }

    private fun StringBuilder.attribute(attribute: Pair<String, String>) {
        append(attribute.first).append("=\"").append(escape(attribute.second, true)).append('"')
    }

    private fun escape(text: String, attribute: Boolean): String = buildString {
        text.forEach { c ->
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' && attribute -> append("&quot;")
                c == '\n' && attribute -> append("&#10;")
                else -> append(c)
            }
        }
    }

    private const val MALFORMED = "It isn't well-formed compiled XML."
    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private const val INT_DEC = 0x10
    private const val INT_HEX = 0x11
    private const val MAX_DEPTH = 256
    private const val XML = 0x0003
    private const val STRING_POOL = 0x0001
    private const val START_NAMESPACE = 0x0100
    private const val END_NAMESPACE = 0x0101
    private const val START_ELEMENT = 0x0102
    private const val END_ELEMENT = 0x0103
    private const val CDATA = 0x0104
    private const val RESOURCE_MAP = 0x0180
    private const val CHUNK_HEADER = 8
    private const val UTF8_FLAG = 0x100
    private const val NO_ENTRY = -1
    private const val SHORT_MASK = 0xFFFF
    private const val BYTE_MASK = 0xFF
    private const val HIGH_BIT_16 = 0x8000
    private const val LOW_15 = 0x7FFF
    private const val HIGH_BIT_8 = 0x80
    private const val LOW_7 = 0x7F
}

/** A compiled resource value as text, close to how `aapt2 dump xmltree` prints one. */
internal object ResValues {
    fun show(type: Int, data: Int, string: (Int) -> String?, names: (Int) -> String?): String =
        when (type) {
            NULL -> if (data == DATA_EMPTY) "" else "@null"
            REFERENCE,
            DYNAMIC_REFERENCE -> if (data == 0) "@null" else "@" + (names(data) ?: hex(data))
            ATTRIBUTE,
            DYNAMIC_ATTRIBUTE -> "?" + (names(data) ?: hex(data))
            STRING -> string(data).orEmpty()
            FLOAT -> number(Float.fromBits(data))
            DIMENSION -> complex(data, DIMENSION_UNITS, 1f)
            FRACTION -> complex(data, FRACTION_UNITS, PERCENT)
            INT_DEC -> data.toString()
            INT_HEX -> "0x%x".format(data)
            INT_BOOLEAN -> (data != 0).toString()
            ARGB8,
            ARGB4 -> "#%08x".format(data)
            RGB8,
            RGB4 -> "#%06x".format(data and RGB_MASK)
            else -> "(type 0x%02x)%s".format(type, hex(data))
        }

    /** A dimension or fraction: a 24-bit mantissa, where its point sits, and its unit. */
    private fun complex(data: Int, units: List<String>, scale: Float): String {
        val value = (data and MANTISSA_MASK) * RADIX[(data shr RADIX_SHIFT) and RADIX_MASK] * scale
        return number(value) + (units.getOrNull(data and UNIT_MASK) ?: "")
    }

    private fun number(value: Float): String = value.toString().removeSuffix(".0")

    private fun hex(id: Int) = "0x%08x".format(id)

    private const val NULL = 0x00
    private const val REFERENCE = 0x01
    private const val ATTRIBUTE = 0x02
    private const val STRING = 0x03
    private const val FLOAT = 0x04
    private const val DIMENSION = 0x05
    private const val FRACTION = 0x06
    private const val DYNAMIC_REFERENCE = 0x07
    private const val DYNAMIC_ATTRIBUTE = 0x08
    private const val INT_DEC = 0x10
    private const val INT_HEX = 0x11
    private const val INT_BOOLEAN = 0x12
    private const val ARGB8 = 0x1c
    private const val RGB8 = 0x1d
    private const val ARGB4 = 0x1e
    private const val RGB4 = 0x1f
    private const val DATA_EMPTY = 1
    private const val RGB_MASK = 0xFFFFFF
    private const val MANTISSA_MASK = -0x100
    private const val RADIX_SHIFT = 4
    private const val RADIX_MASK = 3
    private const val UNIT_MASK = 0xF
    private const val PERCENT = 100f
    private val RADIX =
        floatArrayOf(1f / (1 shl 8), 1f / (1 shl 15), 1f / (1 shl 23), 1f / 2147483648f)
    private val DIMENSION_UNITS = listOf("px", "dp", "sp", "pt", "in", "mm")
    private val FRACTION_UNITS = listOf("%", "%p")
}
