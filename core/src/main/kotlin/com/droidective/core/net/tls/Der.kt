package com.droidective.core.net.tls

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * DER, the encoding a certificate is written in: every value is a tag byte, a length, then the
 * content, and a structure is a value whose content is more values.
 *
 * Only the handful of shapes a certificate needs are here. Writing them out is what lets the proxy
 * mint its own certificates with nothing but the JDK — it generates the keys and signs, and this
 * turns the result into bytes a device will parse — instead of carrying a crypto library for it.
 */
internal object Der {
    const val BOOLEAN = 0x01
    const val INTEGER = 0x02
    const val BIT_STRING = 0x03
    const val OCTET_STRING = 0x04
    const val NULL = 0x05
    const val OBJECT_IDENTIFIER = 0x06
    const val UTF8_STRING = 0x0C
    const val UTC_TIME = 0x17
    const val GENERALIZED_TIME = 0x18
    const val SEQUENCE = 0x30
    const val SET = 0x31

    /** A tag, the length of the content, then the content itself. */
    fun encode(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + LENGTH_HEADROOM)
        out.write(tag)
        writeLength(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    fun sequence(vararg parts: ByteArray): ByteArray = encode(SEQUENCE, join(parts))

    fun set(vararg parts: ByteArray): ByteArray = encode(SET, join(parts))

    fun integer(number: BigInteger): ByteArray = encode(INTEGER, number.toByteArray())

    fun integer(number: Int): ByteArray = integer(BigInteger.valueOf(number.toLong()))

    fun boolean(flag: Boolean): ByteArray = encode(BOOLEAN, byteArrayOf(if (flag) TRUE_BYTE else 0))

    fun octetString(content: ByteArray): ByteArray = encode(OCTET_STRING, content)

    /** A key or a signature. The first content byte counts unused bits: whole bytes have none. */
    fun bitString(content: ByteArray): ByteArray = encode(BIT_STRING, byteArrayOf(0) + content)

    fun nullValue(): ByteArray = encode(NULL, ByteArray(0))

    fun utf8String(text: String): ByteArray =
        encode(UTF8_STRING, text.toByteArray(StandardCharsets.UTF_8))

    /** A tagged value holding another value, written `[n]` in the specification. */
    fun explicit(number: Int, content: ByteArray): ByteArray =
        encode(CONSTRUCTED or number, content)

    /** A tagged value holding raw bytes, such as one name in a subjectAltName. */
    fun context(number: Int, content: ByteArray): ByteArray = encode(CONTEXT or number, content)

    /**
     * An object identifier from its dotted form. The first two arcs share a byte; the rest are base
     * 128, with every byte but the last carrying a continuation bit.
     */
    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        require(arcs.size >= 2) { "\"$dotted\" isn't an object identifier" }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * FIRST_ARC_SPAN + arcs[1]).toInt())
        arcs.drop(2).forEach { writeBase128(out, it) }
        return encode(OBJECT_IDENTIFIER, out.toByteArray())
    }

    /**
     * A moment, in the form X.509 asks for: two-digit years up to 2049, four digits beyond, which
     * is the rule readers apply when they decide which century a certificate means.
     */
    fun time(epochMillis: Long): ByteArray {
        val instant = Instant.ofEpochMilli(epochMillis)
        val year = instant.atZone(ZoneOffset.UTC).year
        return if (year in UTC_TIME_YEARS) {
            encode(UTC_TIME, utcTime.format(instant).toByteArray(StandardCharsets.US_ASCII))
        } else {
            encode(
                GENERALIZED_TIME,
                generalizedTime.format(instant).toByteArray(StandardCharsets.US_ASCII),
            )
        }
    }

    /**
     * The key bits out of a SubjectPublicKeyInfo — what [java.security.PublicKey.getEncoded]
     * returns — with the algorithm it names dropped. A certificate identifies a key by a digest of
     * exactly these bytes.
     */
    fun publicKeyBits(info: ByteArray): ByteArray {
        val cursor = Cursor(info)
        require(cursor.tag() == SEQUENCE) { "not a SubjectPublicKeyInfo" }
        cursor.length()
        cursor.skipValue()
        require(cursor.tag() == BIT_STRING) { "the SubjectPublicKeyInfo holds no key" }
        val size = cursor.length()
        // The first content byte counts unused bits at the end; a key never has any.
        return info.copyOfRange(cursor.at + 1, cursor.at + size)
    }

    private fun writeLength(out: ByteArrayOutputStream, length: Int) {
        if (length < LONG_FORM) {
            out.write(length)
            return
        }
        val bytes = BigInteger.valueOf(length.toLong()).toByteArray().dropWhile { it == ZERO_BYTE }
        out.write(LONG_FORM or bytes.size)
        bytes.forEach { out.write(it.toInt()) }
    }

    private fun writeBase128(out: ByteArrayOutputStream, arc: Long) {
        val digits = ArrayDeque<Int>()
        var rest = arc
        do {
            digits.addFirst((rest and SEVEN_BITS).toInt())
            rest = rest ushr BASE_128_BITS
        } while (rest != 0L)
        digits.forEachIndexed { index, digit ->
            out.write(if (index == digits.size - 1) digit else digit or CONTINUES)
        }
    }

    private fun join(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach(out::write)
        return out.toByteArray()
    }

    /** Walks a value's header far enough to find the key in a SubjectPublicKeyInfo. */
    private class Cursor(private val bytes: ByteArray) {
        var at = 0

        fun tag(): Int = bytes[at++].toInt() and BYTE_MASK

        fun length(): Int {
            val first = bytes[at++].toInt() and BYTE_MASK
            if (first < LONG_FORM) return first
            var size = 0
            repeat(first and SEVEN_BITS.toInt()) {
                size = (size shl Byte.SIZE_BITS) or (bytes[at++].toInt() and BYTE_MASK)
            }
            return size
        }

        fun skipValue() {
            tag()
            // Read the length first: it moves the cursor past its own bytes, and `at +=` would
            // take the position from before that and swallow the move.
            val size = length()
            at += size
        }
    }

    private val utcTime = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)
    private val generalizedTime =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)

    private val UTC_TIME_YEARS = 1950..2049
    private const val LENGTH_HEADROOM = 8
    private const val LONG_FORM = 0x80
    private const val CONTINUES = 0x80
    private const val CONTEXT = 0x80
    private const val CONSTRUCTED = 0xA0
    private const val SEVEN_BITS = 0x7FL
    private const val BASE_128_BITS = 7
    private const val BYTE_MASK = 0xFF
    private const val FIRST_ARC_SPAN = 40
    private const val TRUE_BYTE = 0xFF.toByte()
    private const val ZERO_BYTE = 0.toByte()
}
