package com.droidective.core.net.proxy

/**
 * A TLS failure in words somebody reading a list of requests can use.
 *
 * What the TLS library says is written for whoever maintains it. On Android a client turning the
 * certificate down reads "Read error: ssl=0xb4000079: Failure in SSL library, usually a protocol
 * error ... SSLV3_ALERT_CERTIFICATE_UNKNOWN (external/boringssl/...)", and the only part of that
 * worth a row is the alert at the end.
 */
object TlsFailure {
    /** The app on the phone turned the proxy's certificate down. */
    const val REFUSED = "the app refused the certificate"

    /** What a client's failed handshake with the proxy says, given the exception's message. */
    fun refused(message: String?): String = alert(message)?.let { "$REFUSED ($it)" } ?: REFUSED

    /** What the real server's failed handshake says: its certificate didn't check out, usually. */
    fun upstream(message: String?): String {
        val why =
            alert(message)
                ?: message?.lineSequence()?.firstOrNull()?.replace(CAUSE_NAMES, "")?.trim()
        return if (why.isNullOrEmpty()) "the server's TLS failed"
        else "the server's TLS failed: $why"
    }

    /**
     * The alert a message names, as words: "certificate unknown", "unknown CA". BoringSSL writes
     * `SSLV3_ALERT_CERTIFICATE_UNKNOWN` or `TLSV1_ALERT_UNKNOWN_CA`; the JDK writes `Received fatal
     * alert: certificate_unknown`.
     */
    fun alert(message: String?): String? {
        if (message == null) return null
        val named =
            BORINGSSL.find(message)?.groupValues?.get(1)
                ?: JDK.find(message)?.groupValues?.get(1)
                ?: return null
        return named.lowercase().split('_').joinToString(" ") { if (it == "ca") "CA" else it }
    }

    private val BORINGSSL = Regex("""(?:SSLV3|TLSV1(?:_[0-9])?)_ALERT_([A-Z_]+)""")
    private val JDK = Regex("""Received fatal alert: ([a-z_]+)""")

    /** Android leads with the cause's class: "java.security.cert.CertPathValidatorException: …". */
    private val CAUSE_NAMES = Regex("""^(?:[\w.$]+(?:Exception|Error): )+""")
}
