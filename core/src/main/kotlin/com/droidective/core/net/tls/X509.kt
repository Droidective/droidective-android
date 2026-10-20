package com.droidective.core.net.tls

import java.math.BigInteger
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Who a certificate is for, as much of a name as a debugging proxy needs. */
data class CertName(val commonName: String, val organization: String? = null)

/**
 * What one certificate says: who it is for, how long it is good for, and whether it may sign other
 * certificates. [hosts] become the names a client matches the server against; a certificate
 * authority has none.
 */
data class CertificateSpec(
    val subject: CertName,
    val serial: BigInteger,
    val notBefore: Long,
    val notAfter: Long,
    val authority: Boolean,
    val hosts: List<String> = emptyList(),
)

/**
 * The body of an X.509 certificate, assembled from a [CertificateSpec].
 *
 * The caller signs [toBeSigned] with the issuer's key and hands both back to [certificate]; this
 * never touches a key itself, which is what keeps the whole of it testable as bytes.
 */
internal object X509 {
    /** Everything here is signed with SHA-256 and RSA, which every Android release accepts. */
    const val SIGNATURE_ALGORITHM = "SHA256withRSA"

    /** A name, in the nested form a certificate writes it: a sequence of sets of one attribute. */
    fun name(name: CertName): ByteArray {
        val parts =
            listOfNotNull(
                name.organization?.let { attribute(ORGANIZATION, it) },
                attribute(COMMON_NAME, name.commonName),
            )
        return Der.sequence(*parts.toTypedArray())
    }

    /**
     * The part of a certificate that gets signed.
     *
     * @param issuerName the issuer's name exactly as that certificate writes its own subject, so a
     *   client matching the two byte for byte can build the chain.
     * @param subjectKey the subject's public key, as [java.security.PublicKey.getEncoded] gives it.
     * @param issuerKey the issuer's public key, named so a client knows which key signed this.
     */
    fun toBeSigned(
        spec: CertificateSpec,
        issuerName: ByteArray,
        subjectKey: ByteArray,
        issuerKey: ByteArray,
    ): ByteArray =
        Der.sequence(
            Der.explicit(VERSION_TAG, Der.integer(VERSION_3)),
            Der.integer(spec.serial),
            signatureAlgorithm(),
            issuerName,
            Der.sequence(Der.time(spec.notBefore), Der.time(spec.notAfter)),
            name(spec.subject),
            subjectKey,
            Der.explicit(EXTENSIONS_TAG, extensions(spec, subjectKey, issuerKey)),
        )

    /** A signed certificate: its body, the algorithm that signed it, and the signature. */
    fun certificate(toBeSigned: ByteArray, signature: ByteArray): ByteArray =
        Der.sequence(toBeSigned, signatureAlgorithm(), Der.bitString(signature))

    fun signatureAlgorithm(): ByteArray = Der.sequence(Der.oid(SHA256_WITH_RSA), Der.nullValue())

    /** How a certificate names a key: a digest of the key's own bits, as RFC 5280 suggests. */
    fun keyIdentifier(publicKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(Der.publicKeyBits(publicKey))

    private fun extensions(
        spec: CertificateSpec,
        subjectKey: ByteArray,
        issuerKey: ByteArray,
    ): ByteArray {
        val parts =
            listOfNotNull(
                basicConstraints(spec.authority),
                usage(spec.authority),
                extendedUsage().takeUnless { spec.authority },
                spec.hosts.takeIf { it.isNotEmpty() }?.let(::subjectAltName),
                extension(SUBJECT_KEY_ID, Der.octetString(keyIdentifier(subjectKey))),
                extension(
                    AUTHORITY_KEY_ID,
                    Der.sequence(Der.context(KEY_ID_TAG, keyIdentifier(issuerKey))),
                ),
            )
        return Der.sequence(*parts.toTypedArray())
    }

    /** Whether this may sign other certificates. A proxy's authority signs leaves and no deeper. */
    private fun basicConstraints(authority: Boolean): ByteArray =
        critical(
            BASIC_CONSTRAINTS,
            if (authority) Der.sequence(Der.boolean(true), Der.integer(0)) else Der.sequence(),
        )

    private fun usage(authority: Boolean): ByteArray =
        critical(
            KEY_USAGE,
            if (authority) keyUsage(SIGN_CERTIFICATES, SIGN_REVOCATIONS)
            else keyUsage(DIGITAL_SIGNATURE, KEY_ENCIPHERMENT),
        )

    private fun extendedUsage(): ByteArray =
        extension(EXTENDED_KEY_USAGE, Der.sequence(Der.oid(SERVER_AUTH)))

    /**
     * The names this certificate speaks for. An address goes in as four or sixteen raw bytes and a
     * hostname as text: a client checks the one that matches what it dialled.
     */
    private fun subjectAltName(hosts: List<String>): ByteArray {
        val names = hosts.map { host ->
            addressBytes(host)?.let { Der.context(IP_ADDRESS, it) }
                ?: Der.context(DNS_NAME, host.toByteArray(StandardCharsets.US_ASCII))
        }
        return extension(SUBJECT_ALT_NAME, Der.sequence(*names.toTypedArray()))
    }

    /** [host] as an address, or null when it is a name that would have to be looked up. */
    private fun addressBytes(host: String): ByteArray? {
        val bare = host.removeSurrounding("[", "]")
        if (':' in bare) {
            return try {
                InetAddress.getByName(bare).address
            } catch (_: UnknownHostException) {
                null
            }
        }
        val parts = bare.split('.')
        if (parts.size != IPV4_PARTS) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..MAX_OCTET }) return null
        return ByteArray(IPV4_PARTS) { octets[it].toByte() }
    }

    /** The named bits, counted from the top of the first byte, with the spare ones declared. */
    private fun keyUsage(vararg bits: Int): ByteArray {
        val highest = bits.max()
        val bytes = ByteArray(highest / Byte.SIZE_BITS + 1)
        bits.forEach { bit ->
            val index = bit / Byte.SIZE_BITS
            val mask = TOP_BIT ushr (bit % Byte.SIZE_BITS)
            bytes[index] = (bytes[index].toInt() or mask).toByte()
        }
        val unused = Byte.SIZE_BITS - 1 - (highest % Byte.SIZE_BITS)
        return Der.encode(Der.BIT_STRING, byteArrayOf(unused.toByte()) + bytes)
    }

    private fun attribute(oid: String, text: String): ByteArray =
        Der.set(Der.sequence(Der.oid(oid), Der.utf8String(text)))

    private fun extension(oid: String, content: ByteArray): ByteArray =
        Der.sequence(Der.oid(oid), Der.octetString(content))

    /** An extension a client must understand or refuse the certificate. */
    private fun critical(oid: String, content: ByteArray): ByteArray =
        Der.sequence(Der.oid(oid), Der.boolean(true), Der.octetString(content))

    private const val COMMON_NAME = "2.5.4.3"
    private const val ORGANIZATION = "2.5.4.10"
    private const val SUBJECT_KEY_ID = "2.5.29.14"
    private const val KEY_USAGE = "2.5.29.15"
    private const val SUBJECT_ALT_NAME = "2.5.29.17"
    private const val BASIC_CONSTRAINTS = "2.5.29.19"
    private const val AUTHORITY_KEY_ID = "2.5.29.35"
    private const val EXTENDED_KEY_USAGE = "2.5.29.37"
    private const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    private const val SHA256_WITH_RSA = "1.2.840.113549.1.1.11"

    private const val VERSION_TAG = 0
    private const val EXTENSIONS_TAG = 3
    private const val KEY_ID_TAG = 0
    private const val DNS_NAME = 2
    private const val IP_ADDRESS = 7
    private const val VERSION_3 = 2

    private const val DIGITAL_SIGNATURE = 0
    private const val KEY_ENCIPHERMENT = 2
    private const val SIGN_CERTIFICATES = 5
    private const val SIGN_REVOCATIONS = 6

    private const val TOP_BIT = 0x80
    private const val IPV4_PARTS = 4
    private const val MAX_OCTET = 255
}
