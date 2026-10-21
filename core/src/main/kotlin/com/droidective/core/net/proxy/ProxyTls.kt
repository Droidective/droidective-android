package com.droidective.core.net.proxy

import com.droidective.core.net.tls.KeyedCertificate
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** The two ends of TLS the proxy stands between: the one it answers with, and the real one. */
internal object ProxyTls {
    /** Answers as the host asked for, with the certificate minted for it. */
    fun serverContext(leaf: KeyedCertificate, authority: X509Certificate): SSLContext {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, PASSWORD)
        store.setKeyEntry(ALIAS, leaf.privateKey, PASSWORD, arrayOf(leaf.certificate, authority))
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(store, PASSWORD)
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
    }

    /**
     * Takes over [client] as the server it dialled.
     *
     * Only HTTP/1.1 is offered: a client that would rather speak HTTP/2 settles for what this can
     * read, instead of opening streams nothing here understands.
     */
    fun accept(context: SSLContext, client: Socket, origin: Origin): SSLSocket =
        (context.socketFactory.createSocket(client, origin.host, origin.port, false) as SSLSocket)
            .apply {
                useClientMode = false
                sslParameters = sslParameters.apply { applicationProtocols = arrayOf(HTTP_1_1) }
            }

    /**
     * The real connection, with the server's certificate checked the way a browser checks it.
     *
     * This is the part that is easy to leave out and must not be. An app that trusts the proxy has
     * handed it the job of deciding whether the server is genuine; a proxy that skips the check has
     * quietly made every connection through it weaker than it was without one.
     */
    fun connect(origin: Origin, timeout: Int): SSLSocket {
        val plain = Dial.connect(origin.host, origin.port, timeout)
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket = factory.createSocket(plain, origin.host, origin.port, true) as SSLSocket
        socket.sslParameters =
            socket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                applicationProtocols = arrayOf(HTTP_1_1)
            }
        socket.startHandshake()
        return socket
    }

    private const val ALIAS = "leaf"
    private const val HTTP_1_1 = "http/1.1"
    private val PASSWORD = "proxy".toCharArray()
}
