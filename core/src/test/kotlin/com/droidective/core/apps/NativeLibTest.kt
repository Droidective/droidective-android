package com.droidective.core.apps

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeLibTest {
    private val dir = createTempDirectory("so").toFile()
    private val real =
        File(System.getProperty("fixtures.dir"), "apks/libandroidx.graphics.path-arm64.so.head")
            .readBytes()

    @AfterTest fun cleanUp() = dir.deleteRecursively().let {}

    @Test
    fun readsARealLibrarysSegmentsTheWayObjdumpDoes() {
        assertEquals(64L + 9 * 56, NativeLibs.headerEnd(real))
        assertEquals(16_384L, NativeLibs.loadAlignment(real))
    }

    @Test
    fun theSmallestLoadableSegmentDecides() {
        // The second of its three LOAD segments, made 4 KB; PHDR and STACK aren't loadable.
        assertEquals(4_096L, NativeLibs.loadAlignment(withLoadAlignment(real, 1, 4_096)))
        assertEquals(16_384L, NativeLibs.loadAlignment(withAlignment(real, 0, 4_096)), "PHDR")
        assertEquals(0L, NativeLibs.loadAlignment(withLoadAlignment(real, 0, 0)))
    }

    @Test
    fun whatIsntA64BitLittleEndianElfIsNull() {
        assertNull(NativeLibs.loadAlignment(ByteArray(4096)))
        assertNull(NativeLibs.loadAlignment(real.copyOf(63)))
        assertNull(NativeLibs.loadAlignment(real.copyOf().also { it[4] = 1 }), "32-bit")
        assertNull(NativeLibs.loadAlignment(real.copyOf().also { it[5] = 2 }), "big-endian")
        assertNull(NativeLibs.loadAlignment(real.copyOf(500)), "program headers cut off")
        val noLoads =
            real.copyOf().also { bytes ->
                for (i in 0 until 9) ByteBuffer.wrap(bytes, 64 + i * 56, 4).order(LE).putInt(6)
            }
        assertNull(NativeLibs.loadAlignment(noLoads))
        val farOff = real.copyOf().also { ByteBuffer.wrap(it, 0x20, 8).order(LE).putLong(-1) }
        assertNull(NativeLibs.loadAlignment(farOff))
    }

    @Test
    fun readiness() {
        assertTrue(NativeLib("a.so", 16_384, null).ready, "compressed: extracted at install")
        assertTrue(NativeLib("a.so", 65_536, 3 * 16_384L).ready)
        assertFalse(NativeLib("a.so", 4_096, null).ready)
        assertFalse(NativeLib("a.so", 16_384, 4_096).ready, "stored off a 16 KB boundary")
        assertFalse(NativeLib("a.so", null, 0).ready, "unreadable")
    }

    @Test
    fun eachProblemIsASentence() {
        assertEquals(emptyList(), NativeLib("a.so", 16_384, 16_384).problems)
        assertEquals(
            listOf(
                "Its segments are aligned to 4 KB.",
                "It's stored uncompressed at byte 8192, off a 16 KB boundary.",
            ),
            NativeLib("a.so", 4_096, 8_192).problems,
        )
        assertEquals(
            listOf("Its segments are aligned to nothing."),
            NativeLib("a.so", 0, null).problems,
        )
        assertEquals(
            listOf("Its segments are aligned to 100 bytes."),
            NativeLib("a.so", 100, null).problems,
        )
        assertEquals(
            listOf("It can't be read as a 64-bit ELF library."),
            NativeLib("a.so", null, null).problems,
        )
    }

    @Test
    fun anApksSixtyFourBitLibrariesWithWhereTheirBytesStart() {
        val library = real + ByteArray(8000)
        val apk =
            zip(
                listOf(
                    "AndroidManifest.xml" to ("x".toByteArray() to false),
                    "lib/arm64-v8a/libstored.so" to (library to true),
                    "lib/x86_64/libdeflated.so" to
                        (withLoadAlignment(real, 1, 4_096) + ByteArray(8000) to false),
                    "lib/armeabi-v7a/libold32.so" to (library to true),
                    "lib/arm64-v8a/notes.txt" to ("not code".toByteArray() to true),
                    "assets/lib/arm64-v8a/libasset.so" to (library to true),
                    "lib/arm64-v8a/libplaceholder.so" to (ByteArray(0) to true),
                )
            )
        val libs = ApkAnatomies.read(apk).nativeLibs
        assertEquals(
            listOf("lib/arm64-v8a/libstored.so", "lib/x86_64/libdeflated.so"),
            libs.map { it.path },
        )
        val (stored, deflated) = libs
        assertEquals(16_384L, stored.loadAlignment)
        assertTrue((stored.dataOffset ?: 0) > 0)
        assertFalse(stored.zipReady, "ZipOutputStream doesn't align")
        assertEquals(NativeLib("lib/x86_64/libdeflated.so", 4_096, null), deflated)
    }

    @Test
    fun aLibraryStoredOnA16KbBoundaryIsReady() {
        // The padding zipalign -P 16 adds: an extra field that pushes the bytes to 16384.
        val name = "lib/arm64-v8a/libaligned.so"
        val padding = 16_384 - 30 - name.length
        val extra = ByteBuffer.allocate(padding).order(LE)
        extra.putShort(0xd935.toShort()).putShort((padding - 4).toShort())
        val library = real + ByteArray(100)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(
                ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = library.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(library) }.value
                    this.extra = extra.array()
                }
            )
            zip.write(library)
            zip.closeEntry()
        }
        val apk = File(dir, "aligned.apk").apply { writeBytes(out.toByteArray()) }
        val lib = ApkAnatomies.read(apk).nativeLibs.single()
        assertEquals(NativeLib(name, 16_384, 16_384), lib)
        assertTrue(lib.ready)
    }

    @Test
    fun programHeadersPastTheFirstBytesAreStillRead() {
        // Program headers moved 6000 bytes in, past the 4 KB read first.
        val moved = ByteArray(6000 + 9 * 56)
        real.copyInto(moved, 0, 0, 64)
        real.copyInto(moved, 6000, 64, 64 + 9 * 56)
        ByteBuffer.wrap(moved, 0x20, 8).order(LE).putLong(6000)
        val apk = zip(listOf("lib/arm64-v8a/libfar.so" to (moved to false)))
        assertEquals(16_384L, ApkAnatomies.read(apk).nativeLibs.single().loadAlignment)
    }

    /** [bytes] with the [index]th LOAD segment's alignment set to [align]. */
    private fun withLoadAlignment(bytes: ByteArray, index: Int, align: Long): ByteArray {
        val loads =
            (0 until 9).filter { ByteBuffer.wrap(bytes, 64 + it * 56, 4).order(LE).int == 1 }
        return withAlignment(bytes, loads[index], align)
    }

    private fun withAlignment(bytes: ByteArray, header: Int, align: Long) =
        bytes.copyOf().also {
            ByteBuffer.wrap(it, 64 + header * 56 + 48, 8).order(LE).putLong(align)
        }

    private fun zip(files: List<Pair<String, Pair<ByteArray, Boolean>>>): File {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (path, content) ->
                val (bytes, stored) = content
                val entry = ZipEntry(path)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return File(dir, "app.apk").apply { writeBytes(out.toByteArray()) }
    }

    private companion object {
        val LE: ByteOrder = ByteOrder.LITTLE_ENDIAN
    }
}
