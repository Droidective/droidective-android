package com.droidective.core.net

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** What Network Check talks to: a host and port, over TLS or not, and the path it asks for. */
data class CheckTarget(val host: String, val port: Int, val tls: Boolean, val path: String) {
    companion object {
        /**
         * `example.com`, `example.com:8443`, `https://example.com/health`, `http://10.0.2.2:8080`
         * or `[::1]:443`. Without a scheme it's TLS unless the port is 80. Null when [text] isn't
         * an address.
         */
        fun parse(text: String): CheckTarget? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val withScheme = if ("://" in trimmed) trimmed else "check://$trimmed"
            val uri =
                try {
                    URI(withScheme)
                } catch (_: URISyntaxException) {
                    return null
                }
            val host = uri.host?.removeSurrounding("[", "]") ?: return null
            val scheme = uri.scheme.lowercase()
            if (scheme !in listOf("http", "https", "check")) return null
            val tls = scheme == "https" || (scheme == "check" && uri.port != HTTP_PORT)
            val port = uri.port.takeIf { it > 0 } ?: if (tls) HTTPS_PORT else HTTP_PORT
            if (port > MAX_PORT) return null
            val path = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            return CheckTarget(host, port, tls, path)
        }

        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443
        private const val MAX_PORT = 65_535
    }
}

/** One certificate a server sent, with what pinning and renewals care about. */
data class TlsCertificate(
    val subject: String,
    val issuer: String,
    val names: List<String>,
    val notBefore: Long,
    val notAfter: Long,
    val sha256: String,
    val pin: String,
) {
    fun daysLeft(now: Long): Long = (notAfter - now) / DAY

    private companion object {
        const val DAY = 86_400_000L
    }
}

object TlsCertificates {
    fun describe(cert: X509Certificate): TlsCertificate =
        TlsCertificate(
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            names =
                try {
                    cert.subjectAlternativeNames
                        ?.filter { it.firstOrNull() == DNS_NAME || it.firstOrNull() == IP_ADDRESS }
                        ?.mapNotNull { it.getOrNull(1) as? String }
                        .orEmpty()
                } catch (_: CertificateException) {
                    emptyList()
                },
            notBefore = cert.notBefore.time,
            notAfter = cert.notAfter.time,
            sha256 =
                MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") {
                    "%02X".format(it)
                },
            pin = pin(cert.publicKey.encoded),
        )

    /** The SPKI pin OkHttp's CertificatePinner and network security configs take. */
    fun pin(subjectPublicKeyInfo: ByteArray): String =
        "sha256/" +
            Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(subjectPublicKeyInfo))

    private const val DNS_NAME = 2
    private const val IP_ADDRESS = 7
}

/** How one step went: how long it took, and either what it found or why it stopped. */
sealed interface Step<out T> {
    val millis: Long

    data class Done<T>(val value: T, override val millis: Long) : Step<T>

    data class Failed(val reason: String, override val millis: Long) : Step<Nothing>
}

data class TlsAnswer(
    val protocol: String?,
    val cipher: String?,
    val chain: List<TlsCertificate>,
    val trusted: Boolean,
    val nameMatches: Boolean,
    val problem: String?,
)

data class HttpAnswer(val status: Int, val reason: String, val headers: List<Pair<String, String>>)

data class CheckReport(
    val target: CheckTarget,
    val dns: Step<List<String>>,
    val tcp: Step<String>?,
    val tls: Step<TlsAnswer>?,
    val http: Step<HttpAnswer>?,
)

/**
 * Checks a host the way an app's request would meet it: the name, the connection, the TLS handshake
 * and the first answer. It speaks HTTP itself over the socket it opened, so it reports what the
 * host says, not what an HTTP stack hides, and it never follows a redirect. Blocking: call it off
 * the main thread.
 */
object NetworkProbe {
    fun check(target: CheckTarget, timeoutMillis: Int = TIMEOUT): CheckReport {
        val dns = timed { InetAddress.getAllByName(target.host).map { it.hostAddress.orEmpty() } }
        val address =
            (dns as? Step.Done)?.value?.firstOrNull()
                ?: return CheckReport(target, dns, null, null, null)
        val socket = Socket()
        var secure: SSLSocket? = null
        try {
            val tcp = timed {
                socket.connect(InetSocketAddress(address, target.port), timeoutMillis)
                socket.soTimeout = timeoutMillis
                address
            }
            if (tcp is Step.Failed) return CheckReport(target, dns, tcp, null, null)
            if (!target.tls)
                return CheckReport(target, dns, tcp, null, timed { request(socket, target) })
            val (tls, opened) = handshake(socket, target)
            secure = opened
            val trusted = (tls as? Step.Done)?.value?.trusted == true
            val http = if (trusted && opened != null) timed { request(opened, target) } else null
            return CheckReport(target, dns, tcp, tls, http)
        } finally {
            runCatching { secure?.close() }
            runCatching { socket.close() }
        }
    }

    /** The handshake's result, and the TLS socket to speak HTTP over when it worked. */
    private fun handshake(socket: Socket, target: CheckTarget): Pair<Step<TlsAnswer>, SSLSocket?> {
        val recorder = RecordingTrust(platformTrust())
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(recorder), null) }
        val start = System.nanoTime()
        val secure =
            context.socketFactory.createSocket(socket, target.host, target.port, true) as SSLSocket
        val problem =
            try {
                secure.startHandshake()
                null
            } catch (e: IOException) {
                e.message ?: e.javaClass.simpleName
            }
        val millis = (System.nanoTime() - start) / NANOS
        val chain = recorder.chain.map(TlsCertificates::describe)
        if (problem != null && chain.isEmpty()) return Step.Failed(problem, millis) to secure
        val session = secure.session.takeIf { problem == null }
        val nameMatches =
            session != null &&
                HttpsURLConnection.getDefaultHostnameVerifier().verify(target.host, session)
        val answer =
            TlsAnswer(
                protocol = session?.protocol,
                cipher = session?.cipherSuite,
                chain = chain,
                trusted = problem == null && nameMatches,
                nameMatches = nameMatches,
                problem =
                    problem
                        ?: "The certificate isn't for ${target.host}.".takeUnless { nameMatches },
            )
        return Step.Done(answer, millis) to secure.takeIf { answer.trusted }
    }

    /** The answer's status line and headers; the body isn't read. */
    private fun request(socket: Socket, target: CheckTarget): HttpAnswer {
        val host = if (':' in target.host) "[${target.host}]" else target.host
        val defaultPort = if (target.tls) 443 else 80
        val hostHeader = if (target.port == defaultPort) host else "$host:${target.port}"
        val request =
            "GET ${target.path} HTTP/1.1\r\nHost: $hostHeader\r\nUser-Agent: Droidective\r\n" +
                "Accept: */*\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(request.toByteArray(Charsets.US_ASCII))
            flush()
        }
        return HttpReplies.parse(BufferedInputStream(socket.getInputStream()))
    }

    private fun platformTrust(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private inline fun <T> timed(block: () -> T): Step<T> {
        val start = System.nanoTime()
        return try {
            val value = block()
            Step.Done(value, (System.nanoTime() - start) / NANOS)
        } catch (e: IOException) {
            Step.Failed(e.message ?: e.javaClass.simpleName, (System.nanoTime() - start) / NANOS)
        }
    }

    private const val TIMEOUT = 10_000
    private const val NANOS = 1_000_000L
}

/**
 * Keeps the chain a server sent, then leaves the verdict to the platform's own trust manager, so a
 * chain Android wouldn't trust can still be shown. It never trusts anything Android doesn't.
 */
@Suppress("CustomX509TrustManager")
private class RecordingTrust(private val platform: X509TrustManager) : X509TrustManager {
    @Volatile var chain: List<X509Certificate> = emptyList()

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        this.chain = chain.toList()
        platform.checkServerTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        platform.checkClientTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
}

/** An HTTP/1.x answer's head, read line by line up to the blank line. */
object HttpReplies {
    /** @throws IOException when it isn't an HTTP answer, or the head is too long. */
    fun parse(input: InputStream): HttpAnswer {
        val status = line(input) ?: throw IOException("The connection closed without an answer.")
        val parts = status.split(' ', limit = 3)
        val code =
            parts.getOrNull(1)?.toIntOrNull()?.takeIf { parts[0].startsWith("HTTP/") }
                ?: throw IOException("That isn't an HTTP answer: \"${status.take(80)}\"")
        val headers = mutableListOf<Pair<String, String>>()
        while (true) {
            val header = line(input) ?: break
            if (header.isEmpty()) break
            if (headers.size >= MAX_HEADERS) throw IOException("The answer has too many headers.")
            headers += header.substringBefore(':').trim() to header.substringAfter(':', "").trim()
        }
        return HttpAnswer(code, parts.getOrNull(2).orEmpty(), headers)
    }

    private fun line(input: InputStream): String? {
        val bytes = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) return bytes.toString().ifEmpty { null }
            if (next == '\n'.code) return bytes.toString().trimEnd('\r')
            if (bytes.length >= MAX_LINE) throw IOException("A header line is too long.")
            bytes.append(next.toChar())
        }
    }

    private const val MAX_LINE = 16_384
    private const val MAX_HEADERS = 200
}

/** Certificate pins as the two places an Android app puts them. */
object PinSnippets {
    /** For OkHttp's `CertificatePinner`. */
    fun okHttp(host: String, pins: List<String>): String =
        "CertificatePinner.Builder()\n" +
            pins.joinToString("") { "    .add(\"$host\", \"$it\")\n" } +
            "    .build()"

    /** For `res/xml/network_security_config.xml`. */
    fun networkSecurityConfig(host: String, pins: List<String>): String =
        "<domain-config>\n" +
            "    <domain includeSubdomains=\"false\">$host</domain>\n" +
            "    <pin-set>\n" +
            pins.joinToString("") {
                "        <pin digest=\"SHA-256\">${it.removePrefix("sha256/")}</pin>\n"
            } +
            "    </pin-set>\n" +
            "</domain-config>"
}
