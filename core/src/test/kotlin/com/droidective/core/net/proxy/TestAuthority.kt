package com.droidective.core.net.proxy

import com.droidective.core.net.tls.ProxyCertificates
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/** The certificate authority the proxy's tests share, since generating keys is slow. */
internal object TestAuthority {
    val certificates: ProxyCertificates by lazy { ProxyCertificates.create("Droidective Test") }

    /** A client, and the proxy's own upstream, that trust the test authority and nothing else. */
    val trust: SSLContext by lazy {
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null)
        store.setCertificateEntry("ca", certificates.caCertificate)
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
    }

    /** The proxy's way to a server of the test's own, checked as [UpstreamTls.SYSTEM] checks. */
    val upstream = UpstreamTls { origin, timeout, protocol ->
        val plain = Socket()
        plain.connect(InetSocketAddress(origin.host, origin.port), timeout)
        val socket =
            trust.socketFactory.createSocket(plain, origin.host, origin.port, true) as SSLSocket
        socket.sslParameters =
            socket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                applicationProtocols = arrayOf(protocol)
            }
        socket.startHandshake()
        socket
    }

    /** What a server of the test's own answers `localhost` with. */
    fun serverContext(): SSLContext =
        ProxyTls.serverContext(certificates.certificateFor("localhost"), certificates.caCertificate)
}
