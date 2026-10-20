package com.droidective.core.net.tls

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** A certificate together with the private key that goes with it. */
data class KeyedCertificate(val certificate: X509Certificate, val privateKey: PrivateKey)

/**
 * The proxy's own certificate authority: one key that signs, and a certificate for each host the
 * proxy is asked to stand in for, minted on demand and kept for as long as the proxy runs.
 *
 * Every leaf shares one key, so standing in for a new host costs a signature rather than a key
 * generation - the difference between a few milliseconds and a few seconds on a phone. Nothing a
 * device trusts follows from this on its own: the certificate in [caPem] has to be installed by
 * hand first, which is the step that makes the decision the person's.
 */
class ProxyCertificates
internal constructor(
    private val authority: KeyedCertificate,
    private val leafKeys: KeyPair,
    private val leafHolder: X509Certificate,
) {
    private val minted = ConcurrentHashMap<String, KeyedCertificate>()

    val caCertificate: X509Certificate
        get() = authority.certificate

    /** The authority's certificate in the text form every certificate installer reads. */
    val caPem: String
        get() = pem(authority.certificate.encoded)

    /** How long the installed certificate stays good for, as a moment in time. */
    val expiresAt: Long
        get() = authority.certificate.notAfter.time

    /**
     * A certificate for [host], signed by this authority. The first call for a host mints one; the
     * rest hand back the same certificate, so a busy connection pays nothing.
     */
    fun certificateFor(host: String, now: Long = System.currentTimeMillis()): KeyedCertificate =
        minted.getOrPut(host) { mint(host, now) }

    /** Everything needed to pick up where this left off, as a keystore's bytes. */
    fun save(password: CharArray): ByteArray {
        val store = KeyStore.getInstance(KEYSTORE_TYPE)
        store.load(null, password)
        store.setKeyEntry(CA_ALIAS, authority.privateKey, password, arrayOf(authority.certificate))
        store.setKeyEntry(LEAF_ALIAS, leafKeys.private, password, arrayOf(leafHolder))
        val out = ByteArrayOutputStream()
        store.store(out, password)
        return out.toByteArray()
    }

    private fun mint(host: String, now: Long): KeyedCertificate {
        val spec =
            CertificateSpec(
                subject = CertName(host, ORGANIZATION),
                serial = serial(),
                notBefore = now - CLOCK_SKEW,
                notAfter = now + LEAF_VALIDITY,
                authority = false,
                hosts = listOf(host),
            )
        return sign(spec, leafKeys.public.encoded)
    }

    private fun sign(spec: CertificateSpec, subjectKey: ByteArray): KeyedCertificate {
        val issuerName = authority.certificate.subjectX500Principal.encoded
        val issuerKey = authority.certificate.publicKey.encoded
        val body = X509.toBeSigned(spec, issuerName, subjectKey, issuerKey)
        val certificate = assemble(X509.certificate(body, signature(body, authority.privateKey)))
        return KeyedCertificate(certificate, leafKeys.private)
    }

    companion object {
        /**
         * A new authority named [name], which is the name a person sees in their device's list of
         * trusted certificates. Generating the keys takes a second or two on a phone.
         */
        fun create(name: String, now: Long = System.currentTimeMillis()): ProxyCertificates {
            val caKeys = keyPair()
            val leafKeys = keyPair()
            val subject = CertName(name, ORGANIZATION)
            val spec =
                CertificateSpec(
                    subject = subject,
                    serial = serial(),
                    notBefore = now - CLOCK_SKEW,
                    notAfter = now + CA_VALIDITY,
                    authority = true,
                )
            val key = caKeys.public.encoded
            val body = X509.toBeSigned(spec, X509.name(subject), key, key)
            val certificate = assemble(X509.certificate(body, signature(body, caKeys.private)))
            val keyed = KeyedCertificate(certificate, caKeys.private)
            // The leaf key needs a certificate of its own to live in a keystore; this is it, and
            // it doubles as the certificate for the proxy's own address.
            val holder =
                ProxyCertificates(keyed, leafKeys, certificate).mint(PROXY_HOST, now).certificate
            return ProxyCertificates(keyed, leafKeys, holder)
        }

        /**
         * The authority saved by [save], or null when the bytes are not a keystore this wrote or
         * the password does not open it - in which case the caller makes a new one, and whoever
         * installed the old certificate installs this one too.
         */
        fun load(bytes: ByteArray, password: CharArray): ProxyCertificates? =
            try {
                read(bytes, password)
            } catch (_: GeneralSecurityException) {
                null
            } catch (_: IOException) {
                null
            }

        private fun read(bytes: ByteArray, password: CharArray): ProxyCertificates? {
            val store = KeyStore.getInstance(KEYSTORE_TYPE)
            store.load(ByteArrayInputStream(bytes), password)
            val caCertificate = store.getCertificate(CA_ALIAS) as? X509Certificate ?: return null
            val caKey = store.getKey(CA_ALIAS, password) as? PrivateKey ?: return null
            val holder = store.getCertificate(LEAF_ALIAS) as? X509Certificate ?: return null
            val leafKey = store.getKey(LEAF_ALIAS, password) as? PrivateKey ?: return null
            return ProxyCertificates(
                KeyedCertificate(caCertificate, caKey),
                KeyPair(holder.publicKey, leafKey),
                holder,
            )
        }

        private fun keyPair(): KeyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()

        private fun serial(): BigInteger = BigInteger(SERIAL_BITS, random).max(BigInteger.ONE)

        private fun signature(body: ByteArray, key: PrivateKey): ByteArray =
            Signature.getInstance(X509.SIGNATURE_ALGORITHM)
                .apply {
                    initSign(key)
                    update(body)
                }
                .sign()

        private fun assemble(der: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
                as X509Certificate

        private fun pem(der: ByteArray): String {
            val body = Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray()).encodeToString(der)
            return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n"
        }

        private val random = SecureRandom()

        private const val KEYSTORE_TYPE = "PKCS12"
        private const val CA_ALIAS = "ca"
        private const val LEAF_ALIAS = "leaf"
        private const val ORGANIZATION = "Droidective"
        private const val PROXY_HOST = "proxy.droidective.local"
        private const val KEY_BITS = 2048
        private const val SERIAL_BITS = 128
        private const val PEM_LINE = 64
        private const val CLOCK_SKEW = 3_600_000L
        private const val CA_VALIDITY = 3L * 365 * 24 * 3_600_000L
        // Under the 398 days clients cap a server certificate at.
        private const val LEAF_VALIDITY = 390L * 24 * 3_600_000L
    }
}
