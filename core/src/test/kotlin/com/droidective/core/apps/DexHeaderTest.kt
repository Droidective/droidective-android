package com.droidective.core.apps

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DexHeaderTest {
    private val dir = createTempDirectory("dex").toFile()
    private val real =
        File(System.getProperty("fixtures.dir"), "apks/droidective-release-classes.dex.head")
            .readBytes()

    @AfterTest fun cleanUp() = dir.deleteRecursively().let {}

    @Test
    fun readsARealHeaderTheWayDexdumpDoes() {
        assertEquals(
            DexHeader(
                name = "classes.dex",
                version = 39,
                bytes = 5_062_000,
                strings = 21_519,
                types = 6_575,
                fields = 19_118,
                methods = 26_387,
                classes = 5_408,
            ),
            DexHeaders.parse("classes.dex", real),
        )
    }

    @Test
    fun whatIsntADexHeaderIsNull() {
        assertNull(DexHeaders.parse("classes.dex", real.copyOf(DexHeaders.SIZE - 1)), "too short")
        assertNull(DexHeaders.parse("classes.dex", ByteArray(DexHeaders.SIZE)), "no magic")
        val wrongEnd =
            real.copyOf().also {
                it[0x28] = 0x12
                it[0x29] = 0x34
                it[0x2a] = 0x56
                it[0x2b] = 0x78
            }
        assertNull(DexHeaders.parse("classes.dex", wrongEnd), "byte-swapped")
        val badVersion = real.copyOf().also { it[4] = 'x'.code.toByte() }
        assertNull(DexHeaders.parse("classes.dex", badVersion))
        val noNul = real.copyOf().also { it[7] = 1 }
        assertNull(DexHeaders.parse("classes.dex", noNul))
    }

    @Test
    fun countsAreUnsigned() {
        val big = real.copyOf().also { for (i in 0x58 until 0x5c) it[i] = 0xff.toByte() }
        assertEquals(4_294_967_295L, DexHeaders.parse("classes.dex", big)?.methods)
    }

    @Test
    fun eachVersionsFirstAndroid() {
        assertNull(DexHeaders.firstAndroid(35))
        assertEquals("7.0", DexHeaders.firstAndroid(37))
        assertEquals("8.0", DexHeaders.firstAndroid(38))
        assertEquals("9", DexHeaders.firstAndroid(39))
        assertEquals("10", DexHeaders.firstAndroid(40))
        assertEquals("16", DexHeaders.firstAndroid(41))
        assertNull(DexHeaders.firstAndroid(99))
    }

    @Test
    fun anApksDexFilesInTheOrderArtLoadsThem() {
        // Stored and compressed alike; a dex under assets/ isn't code, and one that isn't dex is
        // left out.
        val apk =
            zip(
                "classes10.dex" to header(methods = 10, padding = 4000, stored = false),
                "classes.dex" to header(methods = 1, padding = 50_000, stored = true),
                "classes2.dex" to header(methods = 2, padding = 0, stored = false),
                "classes3.dex" to (ByteArray(500) to true),
                "assets/classes4.dex" to header(methods = 4, padding = 0, stored = true),
            )
        assertEquals(
            listOf("classes.dex" to 1L, "classes2.dex" to 2L, "classes10.dex" to 10L),
            ApkAnatomies.read(apk).dex.map { it.name to it.methods },
        )
    }

    /** The real header with [methods] put in, followed by [padding] bytes. */
    private fun header(methods: Int, padding: Int, stored: Boolean): Pair<ByteArray, Boolean> {
        val bytes = real.copyOf() + ByteArray(padding) { (it % 7).toByte() }
        bytes[0x58] = methods.toByte()
        for (i in 0x59 until 0x5c) bytes[i] = 0
        return bytes to stored
    }

    private fun zip(vararg files: Pair<String, Pair<ByteArray, Boolean>>): File {
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
}
