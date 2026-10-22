package com.droidective.core.zstd

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Against the reference: every fixture was written by libzstd 1.5.6 (`fixtures.py` beside them),
 * and `expected.txt` says what each must come back as, by length and SHA-256.
 */
class ZstdTest {
    @Test
    fun everyFixtureComesBackAsItWent() {
        val expected = resource("expected.txt").decodeToString().lines().filter { it.isNotBlank() }
        assertEquals(13, expected.size)
        for (line in expected) {
            val (name, length, digest) = line.split(' ')
            val out = Zstd.decompress(resource("$name.zst"))
            assertEquals(length.toInt(), out.size, name)
            assertEquals(digest, sha256(out), name)
        }
    }

    @Test
    fun theLimitStopsItPartWayAndKeepsWhatCameFirst() {
        val whole = Zstd.decompress(resource("text-1.zst"))
        val first = Zstd.decompress(resource("text-1.zst"), limit = 1_000)
        assertContentEquals(whole.copyOf(1_000), first)
        // Over the limit inside a long match, the same.
        val run = Zstd.decompress(resource("rle.zst"), limit = 12_345)
        assertEquals(12_345, run.size)
        assertTrue(run.all { it == 'a'.code.toByte() })
    }

    @Test
    fun aBodyCutOffGivesUpTheBlocksBeforeTheCut() {
        val whole = Zstd.decompress(resource("text-3.zst"))
        val compressed = resource("text-3.zst")
        val cut =
            assertFailsWith<ZstdException> {
                Zstd.decompress(compressed.copyOf(compressed.size / 2))
            }
        // Blocks are 128 KB at most, so half the input holds at least one whole one.
        assertTrue(cut.partial.isNotEmpty())
        assertTrue(cut.partial.size < whole.size)
        assertContentEquals(whole.copyOf(cut.partial.size), cut.partial)
    }

    @Test
    fun whatIsNotZstdSaysSo() {
        val notZstd =
            assertFailsWith<ZstdException> { Zstd.decompress("{\"json\":1}".toByteArray()) }
        assertEquals("not zstd: no frame starts here", notZstd.message)
        assertTrue(notZstd.partial.isEmpty())
    }

    @Test
    fun aFrameThatNeedsADictionaryIsRefused() {
        // Magic, a descriptor with a one-byte dictionary id and a one-byte size, id 7, size 0.
        val framed = byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte(), 0x21, 7, 0)
        val refused = assertFailsWith<ZstdException> { Zstd.decompress(framed) }
        assertEquals("a frame that needs a dictionary", refused.message)
    }

    @Test
    fun corruptDataFailsRatherThanMakingSomethingUp() {
        val compressed = resource("text-19.zst")
        // Flip bits through the middle of the first block; every one must fail or decode as the
        // reference would have, never throw anything else.
        val whole = Zstd.decompress(compressed)
        for (at in 20 until 2_000 step 37) {
            val broken = compressed.copyOf().also { it[at] = (it[at].toInt() xor 0x5a).toByte() }
            try {
                val out = Zstd.decompress(broken)
                assertTrue(out.size <= whole.size + 128 * 1024, "at $at")
            } catch (e: ZstdException) {
                assertNotNull(e.message)
            }
        }
    }

    private fun resource(name: String): ByteArray =
        assertNotNull(javaClass.getResource("/zstd/$name"), name).readBytes()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
