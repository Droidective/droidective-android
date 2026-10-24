package com.droidective.core.apps

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZipDirectoryTest {
    private val dir = createTempDirectory("zip").toFile()

    @AfterTest fun cleanUp() = dir.deleteRecursively().let {}

    @Test
    fun agreesWithJavaZipOnRandomZips() {
        val random = Random(7)
        repeat(40) { round ->
            val files =
                (0 until random.nextInt(1, 30)).associate { index ->
                    val name = "dir${index % 4}/sub${index % 3}/file$index.bin"
                    val bytes =
                        if (random.nextBoolean()) random.nextBytes(random.nextInt(0, 90_000))
                        else ByteArray(random.nextInt(0, 200_000)) { 'a'.code.toByte() }
                    name to (bytes to random.nextBoolean())
                }
            val zip = zip("round$round.zip", files)
            val ours = read(zip)
            val java =
                ZipFile(zip).use { z ->
                    z.entries().toList().map {
                        ZipRecord(it.name, it.size, it.compressedSize, it.method == 0, 0)
                    }
                }
            assertEquals(java, ours.map { it.copy(headerOffset = 0) }, "round $round")
        }
    }

    @Test
    fun eachOffsetIsWhereThatFilesLocalHeaderStarts() {
        val zip =
            zip(
                "offsets.zip",
                mapOf(
                    "a.txt" to ("aaa".toByteArray() to true),
                    "lib/arm64-v8a/libx.so" to (ByteArray(9000) { it.toByte() } to false),
                ),
            )
        RandomAccessFile(zip, "r").use { file ->
            read(zip).forEach { record ->
                val header = ZipDirectory.read(file, record.headerOffset, 30 + record.name.length)
                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(0x04034b50, buffer.int)
                assertEquals(record.name, header.copyOfRange(30, header.size).decodeToString())
            }
        }
    }

    @Test
    fun storedAndCompressedSizes() {
        val zip =
            zip(
                "sizes.zip",
                mapOf(
                    "stored.bin" to (ByteArray(4096) to true),
                    "deflated.txt" to (ByteArray(4096) to false),
                ),
            )
        val (stored, deflated) = read(zip)
        assertEquals(ZipRecord("stored.bin", 4096, 4096, true, stored.headerOffset), stored)
        assertEquals(4096, deflated.size)
        assertTrue(deflated.packed < 100, "4 KB of zeros deflates to almost nothing")
        assertEquals(false, deflated.stored)
    }

    @Test
    fun folderEntriesAreLeftOut() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("res/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("res/a.xml"))
            zip.write(1)
            zip.closeEntry()
        }
        val file = File(dir, "folders.zip").apply { writeBytes(out.toByteArray()) }
        assertEquals(listOf("res/a.xml"), read(file).map { it.name })
    }

    @Test
    fun aFileThatIsntAZipSaysSo() {
        val file = File(dir, "text.apk").apply { writeText("not a zip at all") }
        val error = assertFailsWith<IOException> { read(file) }
        assertEquals("It isn't a zip: there's no central directory.", error.message)
        assertFailsWith<IOException> {
            read(File(dir, "empty.apk").apply { writeBytes(ByteArray(0)) })
        }
    }

    @Test
    fun aCutShortDownloadSaysSo() {
        val whole = zip("whole.zip", mapOf("a" to (ByteArray(3000) to true))).readBytes()
        // Lose the middle: the end record survives and points at what is gone.
        val cut = whole.copyOfRange(0, 1000) + whole.copyOfRange(whole.size - 22, whole.size)
        val error =
            assertFailsWith<IOException> { read(File(dir, "cut.zip").apply { writeBytes(cut) }) }
        assertEquals(
            "Its central directory runs past the end: is the file cut short?",
            error.message,
        )
    }

    @Test
    fun aMalformedDirectoryIsAnErrorNotACrash() {
        val bytes = zip("bad.zip", mapOf("a" to ("x".toByteArray() to true))).readBytes()
        val end = bytes.size - 22
        val directory = ByteBuffer.wrap(bytes, end + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
        // A name length far past the directory's end.
        ByteBuffer.wrap(bytes, directory + 28, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(-1)
        val error =
            assertFailsWith<IOException> { read(File(dir, "bad2.zip").apply { writeBytes(bytes) }) }
        assertEquals("Its central directory is malformed.", error.message)
    }

    @Test
    fun moreEntriesClaimedThanThereAreIsMalformed() {
        val bytes = zip("count.zip", mapOf("a" to ("x".toByteArray() to true))).readBytes()
        ByteBuffer.wrap(bytes, bytes.size - 22 + 10, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(2)
        assertFailsWith<IOException> { read(File(dir, "count2.zip").apply { writeBytes(bytes) }) }
    }

    @Test
    fun zip64IsSaidNotMisread() {
        val bytes = zip("z64.zip", mapOf("a" to ("x".toByteArray() to true))).readBytes()
        val end = bytes.size - 22
        val directory = ByteBuffer.wrap(bytes, end + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
        ByteBuffer.wrap(bytes, directory + 24, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(-1)
        val error =
            assertFailsWith<IOException> { read(File(dir, "z64b.zip").apply { writeBytes(bytes) }) }
        assertEquals("It's a zip64 file, which this doesn't read.", error.message)
    }

    private fun read(file: File) = RandomAccessFile(file, "r").use(ZipDirectory::entries)

    /** A zip of [files]: each name's bytes, and whether it's stored rather than deflated. */
    private fun zip(name: String, files: Map<String, Pair<ByteArray, Boolean>>): File {
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
        return File(dir, name).apply { writeBytes(out.toByteArray()) }
    }
}
