package com.droidective.core.apps

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * What an APK file holds that PackageManager doesn't say: the native code it ships for each ABI,
 * what each of its dex files holds, and which signature schemes signed it.
 *
 * @property dex the dex files ART loads, in the order it loads them.
 * @property schemes the APK Signature Scheme versions found: 1 (JAR signing), 2, 3 and 31 (v3.1).
 */
data class ApkAnatomy(
    val bytes: Long,
    val entries: Int,
    val dex: List<DexHeader>,
    val abis: List<String>,
    val schemes: List<Int>,
)

object ApkAnatomies {
    /** @throws java.io.IOException when [file] isn't a readable zip. */
    fun read(file: File): ApkAnatomy =
        RandomAccessFile(file, "r").use { apk ->
            val records = ZipDirectory.entries(apk)
            val names = records.map { it.name }
            val v1 = names.any { name ->
                name.startsWith("META-INF/") &&
                    SIGNATURE_FILES.any { name.endsWith(it, ignoreCase = true) }
            }
            ApkAnatomy(
                bytes = file.length(),
                entries = names.size,
                dex =
                    records
                        .filter { DEX.matches(it.name) }
                        .sortedBy { dexIndex(it.name) }
                        .mapNotNull { record ->
                            val head = ZipDirectory.head(apk, record, DexHeaders.SIZE)
                            DexHeaders.parse(record.name, head)
                        },
                abis = names.mapNotNull { LIB.find(it)?.groupValues?.get(1) }.distinct().sorted(),
                schemes =
                    (listOfNotNull(1.takeIf { v1 }) + signingBlockIds(apk).mapNotNull(SCHEMES::get))
                        .distinct()
                        .sorted(),
            )
        }

    /** classes.dex is the first, then classes2.dex, classes3.dex and on. */
    private fun dexIndex(name: String): Int =
        name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 1

    /**
     * The ids in the APK Signing Block, which sits just before the zip's central directory, or none
     * when there's no block. The layout is the one apksigner writes: the block's size, its id-value
     * pairs, the size again, then the magic.
     */
    fun signingBlockIds(file: RandomAccessFile): List<Int> {
        val centralDirectory = ZipDirectory.end(file)?.offset ?: return emptyList()
        if (centralDirectory < FOOTER) return emptyList()
        val footer = ZipDirectory.read(file, centralDirectory - FOOTER, FOOTER.toInt())
        if (!footer.copyOfRange(8, FOOTER.toInt()).contentEquals(MAGIC)) return emptyList()
        val blockSize = ByteBuffer.wrap(footer, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val start = centralDirectory - blockSize - 8
        if (blockSize < FOOTER || start < 0) return emptyList()
        val pairs = ZipDirectory.read(file, start + 8, (blockSize - FOOTER).toInt())
        val buffer = ByteBuffer.wrap(pairs).order(ByteOrder.LITTLE_ENDIAN)
        val ids = mutableListOf<Int>()
        while (buffer.remaining() >= PAIR_HEADER) {
            val length = buffer.long
            if (length < 4 || length > buffer.remaining()) break
            ids += buffer.int
            buffer.position(buffer.position() + (length - 4).toInt())
        }
        return ids
    }

    private val DEX = Regex("""^classes\d*\.dex$""")
    private val LIB = Regex("""^lib/([^/]+)/[^/]+\.so$""")
    private val SIGNATURE_FILES = listOf(".RSA", ".DSA", ".EC")
    private val MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
    private val SCHEMES = mapOf(0x7109871a to 2, 0xf05368c0.toInt() to 3, 0x1b93ad61 to 31)
    private const val FOOTER = 24L
    private const val PAIR_HEADER = 12
}

/** One certificate an app is signed with, as people compare them: by SHA-256. */
data class SigningCertificate(
    val subject: String,
    val issuer: String,
    val sha256: String,
    val sha1: String,
    val notBefore: Long,
    val notAfter: Long,
) {
    /** Android Studio's debug key, which no store accepts. */
    val isDebug: Boolean
        get() = subject.split(',').map { it.trim() }.contains("CN=Android Debug")
}

object SigningCertificates {
    /** @throws java.security.cert.CertificateException when [der] isn't an X.509 certificate. */
    fun describe(der: ByteArray): SigningCertificate {
        val cert =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
                as X509Certificate
        return SigningCertificate(
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            sha256 = fingerprint("SHA-256", der),
            sha1 = fingerprint("SHA-1", der),
            notBefore = cert.notBefore.time,
            notAfter = cert.notAfter.time,
        )
    }

    /** `AB:CD:…`, the way keytool and the Play Console print a fingerprint. */
    fun fingerprint(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString(":") { "%02X".format(it) }
}
