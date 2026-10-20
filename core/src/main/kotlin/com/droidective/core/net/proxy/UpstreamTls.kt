package com.droidective.core.net.proxy

import javax.net.ssl.SSLSocket

/**
 * How the proxy opens the real connection to a server.
 *
 * It is a seam for one reason: a test needs to stand up a server of its own, and a server of its
 * own is not in anybody's trust store. On a device this is always [SYSTEM], which checks the
 * server's certificate the way a browser would — see [ProxyTls.connect] for why that matters.
 */
fun interface UpstreamTls {
    fun connect(origin: Origin, timeoutMillis: Int): SSLSocket

    companion object {
        val SYSTEM = UpstreamTls { origin, timeout -> ProxyTls.connect(origin, timeout) }
    }
}
