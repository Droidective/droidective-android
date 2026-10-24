package com.droidective.core.apps

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A 64-bit native library in an APK, and whether a phone with 16 KB memory pages can load it as it
 * is: its loadable segments aligned to 16 KB, and, when it's stored uncompressed for the loader to
 * map from the APK, its bytes starting on a 16 KB boundary there too.
 *
 * @property loadAlignment the smallest alignment of its loadable segments; null when it couldn't be
 *   read as a 64-bit little-endian ELF file.
 * @property dataOffset where its bytes start in the APK when it's stored uncompressed; null when
 *   it's compressed, so extracted at install rather than mapped.
 */
data class NativeLib(val path: String, val loadAlignment: Long?, val dataOffset: Long?) {
    val segmentsReady: Boolean
        get() = loadAlignment != null && loadAlignment >= NativeLibs.PAGE_16K

    val zipReady: Boolean
        get() = dataOffset == null || dataOffset % NativeLibs.PAGE_16K == 0L

    val ready: Boolean
        get() = segmentsReady && zipReady

    /** Why it isn't [ready], a sentence each; empty when it is. */
    val problems: List<String>
        get() =
            listOfNotNull(
                when {
                    loadAlignment == null -> "It can't be read as a 64-bit ELF library."
                    !segmentsReady -> "Its segments are aligned to ${bytes(loadAlignment)}."
                    else -> null
                },
                if (zipReady) null
                else "It's stored uncompressed at byte $dataOffset, off a 16 KB boundary.",
            )

    private fun bytes(count: Long): String =
        when {
            count <= 1 -> "nothing"
            count % KB == 0L -> "${count / KB} KB"
            else -> "$count bytes"
        }

    private companion object {
        const val KB = 1024L
    }
}

object NativeLibs {
    const val PAGE_16K = 16_384L

    /** The ABIs a phone with 16 KB pages runs: 16 KB pages are 64-bit only. */
    val ABIS = setOf("arm64-v8a", "x86_64")

    /** The bytes [loadAlignment] usually needs: the ELF header and a typical set of segments. */
    const val HEAD = 4096

    /**
     * How many bytes from the start of the file [loadAlignment] needs to read every program header,
     * or null when [head] doesn't start a 64-bit little-endian ELF file.
     */
    fun headerEnd(head: ByteArray): Long? {
        val elf = elf(head) ?: return null
        val count = elf.getShort(PHNUM).toLong() and SHORT_MASK
        val size = elf.getShort(PHENTSIZE).toLong() and SHORT_MASK
        return elf.getLong(PHOFF) + count * size
    }

    /**
     * The smallest `p_align` among the loadable segments in [head], the start of a 64-bit
     * little-endian ELF file, or null when it isn't one, its program headers lie past [head], or it
     * has no loadable segment. An alignment of 0 or 1 means none.
     */
    fun loadAlignment(head: ByteArray): Long? {
        val elf = elf(head) ?: return null
        val end = headerEnd(head) ?: return null
        val offset = elf.getLong(PHOFF)
        val size = elf.getShort(PHENTSIZE).toInt() and 0xFFFF
        if (offset < 0 || end > head.size || size < PROGRAM_HEADER) return null
        val count = elf.getShort(PHNUM).toInt() and 0xFFFF
        return (0 until count)
            .map { offset.toInt() + it * size }
            .filter { elf.getInt(it) == PT_LOAD }
            .minOfOrNull { elf.getLong(it + P_ALIGN) }
    }

    private fun elf(head: ByteArray): ByteBuffer? {
        if (head.size < ELF_HEADER || !head.copyOf(4).contentEquals(MAGIC)) return null
        if (head[EI_CLASS] != ELFCLASS64 || head[EI_DATA] != ELFDATA2LSB) return null
        return ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
    }

    private val MAGIC = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    private const val EI_CLASS = 4
    private const val EI_DATA = 5
    private const val ELFCLASS64: Byte = 2
    private const val ELFDATA2LSB: Byte = 1
    private const val ELF_HEADER = 64
    private const val PHOFF = 0x20
    private const val PHENTSIZE = 0x36
    private const val PHNUM = 0x38
    private const val PROGRAM_HEADER = 56
    private const val PT_LOAD = 1
    private const val P_ALIGN = 48
    private const val SHORT_MASK = 0xFFFFL
}
