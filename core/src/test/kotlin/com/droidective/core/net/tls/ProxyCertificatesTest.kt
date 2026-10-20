package com.droidective.core.net.tls

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProxyCertificatesTest {
    @Test
    fun aMintedCertificateChainsBackToTheAuthority() {
        val leaf = certificates.certificateFor("example.com").certificate
        // A real validator: it checks the signature, the dates, and that the signer may sign.
        trusting(certificates.caCertificate)
            .checkServerTrusted(arrayOf(leaf, certificates.caCertificate), "RSA")
    }

    @Test
    fun aCertificateSpeaksForTheHostItWasAskedAbout() {
        val leaf = certificates.certificateFor("api.example.com").certificate
        assertContains(names(leaf), DNS_NAME to "api.example.com")
        assertEquals("CN=api.example.com,O=Droidective", leaf.subjectX500Principal.name)
    }

    @Test
    fun anAddressGoesInAsAnAddressRatherThanAName() {
        assertContains(
            names(certificates.certificateFor("10.0.2.2").certificate),
            ADDRESS to "10.0.2.2",
        )
        assertContains(
            names(certificates.certificateFor("::1").certificate),
            ADDRESS to "0:0:0:0:0:0:0:1",
        )
    }

    @Test
    fun theAuthoritySignsAndTheLeavesDoNot() {
        assertTrue(certificates.caCertificate.basicConstraints >= 0)
        assertTrue(certificates.caCertificate.keyUsage[SIGN_CERTIFICATES])
        val leaf = certificates.certificateFor("example.com").certificate
        assertEquals(-1, leaf.basicConstraints)
        assertFalse(leaf.keyUsage[SIGN_CERTIFICATES])
        assertTrue(leaf.keyUsage[DIGITAL_SIGNATURE])
        assertContains(leaf.extendedKeyUsage, SERVER_AUTH)
    }

    @Test
    fun aLeafStaysInsideTheYearClientsAllow() {
        val leaf = certificates.certificateFor("example.com").certificate
        val days = (leaf.notAfter.time - leaf.notBefore.time) / DAY
        assertTrue(days < 398, "a leaf good for $days days would be refused")
    }

    @Test
    fun theSameHostIsMintedOnlyOnce() {
        assertSame(
            certificates.certificateFor("repeat.example.com"),
            certificates.certificateFor("repeat.example.com"),
        )
    }

    @Test
    fun theInstallableTextIsTheSameCertificate() {
        val parsed =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificates.caPem.toByteArray()))
        assertEquals(certificates.caCertificate, parsed)
    }

    @Test
    fun savedAndReopenedItKeepsSigningForTheSameAuthority() {
        val saved = certificates.save(PASSWORD)
        val reopened = assertNotNull(ProxyCertificates.load(saved, PASSWORD))
        assertEquals(certificates.caCertificate, reopened.caCertificate)
        // Minted after the reopen, so it proves the private key came back too.
        val leaf = reopened.certificateFor("later.example.com").certificate
        trusting(certificates.caCertificate).checkServerTrusted(arrayOf(leaf), "RSA")
    }

    @Test
    fun aKeystoreItCannotOpenAsksForANewAuthority() {
        assertNull(ProxyCertificates.load(certificates.save(PASSWORD), "wrong".toCharArray()))
        assertNull(ProxyCertificates.load(byteArrayOf(1, 2, 3), PASSWORD))
    }

    private fun names(certificate: X509Certificate): List<Pair<Int, String>> =
        certificate.subjectAlternativeNames.orEmpty().map { (it[0] as Int) to (it[1] as String) }

    private fun trusting(authority: X509Certificate): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null)
        store.setCertificateEntry("ca", authority)
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private companion object {
        // Generating keys is the slow part, so every test shares one authority.
        val certificates: ProxyCertificates by lazy { ProxyCertificates.create("Droidective Test") }
        val PASSWORD = "droidective".toCharArray()
        const val DNS_NAME = 2
        const val ADDRESS = 7
        const val DIGITAL_SIGNATURE = 0
        const val SIGN_CERTIFICATES = 5
        const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
        const val DAY = 86_400_000L
    }
}
