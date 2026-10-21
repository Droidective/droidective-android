package com.droidective.core.net.proxy

import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.RequestHead
import com.droidective.core.net.http.ResponseHead

/**
 * One exchange the proxy saw: a request going out and whatever came back, or the reason nothing
 * did.
 *
 * [uid] and [app] are left for the Android layer to fill in: `core` can read which uid holds a
 * socket but has no way to turn that into an app's name.
 */
data class Flow(
    val id: Long,
    val startedAt: Long,
    val secure: Boolean,
    val host: String,
    val port: Int,
    val request: RequestHead,
    val requestBody: CapturedBody = CapturedBody.NONE,
    val response: ResponseHead? = null,
    val responseBody: CapturedBody? = null,
    val finishedAt: Long? = null,
    val failure: String? = null,
    val tunnelled: Boolean = false,
    /** Why the connection was passed through, when it was; null for one that was read. */
    val passing: Passing? = null,
    val uid: Int? = null,
    val app: String? = null,
) {
    val scheme: String
        get() = if (secure) "https" else "http"

    /** The host, with its port when that is not the one the scheme implies. */
    val authority: String
        get() = if (port == defaultPort) host else "$host:$port"

    val url: String
        get() = if (opening) "$scheme://$authority" else "$scheme://$authority${request.path}"

    /**
     * Whether this records the `CONNECT` itself rather than a request inside it: a host passed
     * through, or one whose app refused the certificate before asking anything. Its target names
     * the host and port, not a path.
     */
    val opening: Boolean
        get() = tunnelled || request.method.equals(CONNECT, ignoreCase = true)

    val status: Int?
        get() = response?.status

    val durationMillis: Long?
        get() = finishedAt?.let { it - startedAt }

    /** How many bytes the exchange moved, framing included, as far as it got. */
    val wireLength: Long
        get() = requestBody.wireLength + (responseBody?.wireLength ?: 0)

    private val defaultPort: Int
        get() = if (secure) HTTPS_PORT else HTTP_PORT

    private companion object {
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443
        const val CONNECT = "CONNECT"
    }
}

/**
 * Where exchanges go as the proxy sees them.
 *
 * Neither call may block: a sink that waits is a sink that slows down the app being watched. They
 * come from more than one thread — the connection's own, and whichever learned which app a
 * connection belongs to — and a flow may be finished more than once, each record replacing the
 * last.
 */
interface FlowSink {
    /** The request has gone out and the answer has not arrived yet. */
    fun started(flow: Flow) = Unit

    /** The exchange is over, one way or another. */
    fun finished(flow: Flow)

    companion object {
        /** Keeps nothing, for a proxy being run only to forward. */
        val NONE: FlowSink =
            object : FlowSink {
                override fun finished(flow: Flow) = Unit
            }
    }
}
