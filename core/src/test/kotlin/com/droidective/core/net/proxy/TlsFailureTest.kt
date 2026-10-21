package com.droidective.core.net.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TlsFailureTest {
    /** What Chrome turning the certificate down looked like on an API 36 emulator. */
    private val boringSsl =
        "Read error: ssl=0xb400007a2f6f0a18: Failure in SSL library, usually a protocol error\n" +
            "error:10000416:SSL routines:OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_UNKNOWN " +
            "(external/boringssl/src/ssl/tls_record.cc:592 0xb4000079ff6bd9c8:0x00000001)"

    @Test
    fun aRefusalNamesTheAlertAndNothingElse() {
        assertEquals(
            "the app refused the certificate (certificate unknown)",
            TlsFailure.refused(boringSsl),
        )
        assertEquals(
            "the app refused the certificate (unknown CA)",
            TlsFailure.refused("... TLSV1_ALERT_UNKNOWN_CA (external/boringssl/...)"),
        )
        assertEquals(
            "the app refused the certificate (bad certificate)",
            TlsFailure.refused("Received fatal alert: bad_certificate"),
        )
    }

    @Test
    fun aRefusalWithNoAlertIsStillARefusal() {
        // An app that pins can simply hang up.
        assertEquals("the app refused the certificate", TlsFailure.refused("Connection reset"))
        assertEquals("the app refused the certificate", TlsFailure.refused(null))
    }

    @Test
    fun theServersFailureKeepsItsFirstLine() {
        assertEquals(
            "the server's TLS failed: Trust anchor for certification path not found.",
            TlsFailure.upstream(
                "java.security.cert.CertPathValidatorException: Trust anchor for certification " +
                    "path not found."
            ),
        )
        assertEquals(
            "the server's TLS failed: handshake failure",
            TlsFailure.upstream("Received fatal alert: handshake_failure"),
        )
        assertEquals("the server's TLS failed", TlsFailure.upstream(null))
    }

    @Test
    fun aMessageWithNoAlertHasNone() {
        assertNull(TlsFailure.alert("Connection reset by peer"))
        assertNull(TlsFailure.alert(null))
    }
}
