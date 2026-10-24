package com.droidective.core.apps

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApkAnatomyTest {
    private val dir = createTempDirectory("apk").toFile()

    @AfterTest fun cleanUp() = dir.deleteRecursively().let {}

    @Test
    fun readsNativeCodeAndEverySignatureScheme() {
        val apk =
            apk(
                listOf(
                    "AndroidManifest.xml",
                    "classes.dex",
                    "classes2.dex",
                    "lib/arm64-v8a/libapp.so",
                    "lib/arm64-v8a/libcore.so",
                    "lib/x86_64/libapp.so",
                    "assets/classes3.dex",
                    "META-INF/CERT.SF",
                    "META-INF/CERT.RSA",
                ),
                block = listOf(V2, V3, VERITY_PADDING),
            )
        val anatomy = ApkAnatomies.read(apk)
        assertEquals(listOf("arm64-v8a", "x86_64"), anatomy.abis)
        assertEquals(9, anatomy.entries)
        assertEquals(listOf(1, 2, 3), anatomy.schemes)
        assertEquals(apk.length(), anatomy.bytes)
    }

    @Test
    fun aV31BlockWithoutJarSigning() {
        assertEquals(
            listOf(2, 3, 31),
            ApkAnatomies.read(apk(listOf("classes.dex"), block = listOf(V2, V3, V31))).schemes,
        )
    }

    @Test
    fun anUnsignedZipHasNoSchemes() {
        val anatomy = ApkAnatomies.read(apk(listOf("classes.dex", "res/a.xml"), block = null))
        assertEquals(emptyList(), anatomy.schemes)
        assertEquals(emptyList(), anatomy.abis)
    }

    @Test
    fun aBrokenBlockIsNoBlockNotACrash() {
        val apk = apk(listOf("classes.dex"), block = listOf(V2), corruptLength = true)
        assertEquals(emptyList(), ApkAnatomies.read(apk).schemes)
    }

    @Test
    fun describesACertificateTheWayKeytoolDoes() {
        val pem = File(System.getProperty("fixtures.dir"), "apks/test-debug-cert.pem").readText()
        val der =
            Base64.getMimeDecoder()
                .decode(
                    pem.substringAfter("-----BEGIN CERTIFICATE-----")
                        .substringBefore("-----END CERTIFICATE-----")
                )
        val cert = SigningCertificates.describe(der)
        assertEquals(
            "DB:01:6D:44:C6:BF:73:1A:DB:43:17:B7:21:E3:82:19:EB:BB:66:A0:68:DB:36:15:C9:A2:16:65:72:" +
                "94:A3:04",
            cert.sha256,
        )
        assertEquals("CN=Android Debug,O=Android,C=US", cert.subject)
        assertTrue(cert.isDebug)
        assertTrue(cert.notAfter > cert.notBefore)
        assertFalse(cert.copy(subject = "CN=Android Debugger,O=Example").isDebug)
    }

    /**
     * A zip with [names], and an APK Signing Block holding [block]'s ids spliced in before its
     * central directory, the way apksigner places one.
     */
    private fun apk(names: List<String>, block: List<Int>?, corruptLength: Boolean = false): File {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            names.forEach { name ->
                out.putNextEntry(ZipEntry(name))
                out.write(name.toByteArray())
                out.closeEntry()
            }
        }
        var bytes = zip.toByteArray()
        if (block != null) bytes = withBlock(bytes, signingBlock(block, corruptLength))
        return File(dir, "app-${names.size}-${block?.size}.apk").apply { writeBytes(bytes) }
    }

    private fun signingBlock(ids: List<Int>, corruptLength: Boolean): ByteArray {
        val pairs = ByteArrayOutputStream()
        ids.forEach { id ->
            val value = ByteArray(16) { it.toByte() }
            val length = if (corruptLength) 1_000_000L else 4L + value.size
            pairs.write(le(8) { putLong(length) })
            pairs.write(le(4) { putInt(id) })
            pairs.write(value)
        }
        val size = pairs.size() + 8L + 16
        return le(8) { putLong(size) } +
            pairs.toByteArray() +
            le(8) { putLong(size) } +
            "APK Sig Block 42".toByteArray()
    }

    private fun withBlock(zip: ByteArray, block: ByteArray): ByteArray {
        val eocd =
            (zip.size - 22 downTo 0).first { at ->
                ByteBuffer.wrap(zip, at, 4).order(ByteOrder.LITTLE_ENDIAN).int == 0x06054b50
            }
        val centralDirectory = ByteBuffer.wrap(zip, eocd + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val joined =
            zip.copyOfRange(0, centralDirectory) +
                block +
                zip.copyOfRange(centralDirectory, zip.size)
        ByteBuffer.wrap(joined, eocd + block.size + 16, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(centralDirectory + block.size)
        return joined
    }

    private fun le(size: Int, write: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(write).array()

    private companion object {
        const val V2 = 0x7109871a
        const val V3 = 0xf05368c0.toInt()
        const val V31 = 0x1b93ad61
        const val VERITY_PADDING = 0x42726577
    }
}
