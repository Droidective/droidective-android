package com.droidective.core.net

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkCheckTest {
    @Test
    fun readsWhatPeopleTypeAsATarget() {
        assertEquals(CheckTarget("example.com", 443, true, "/"), CheckTarget.parse("example.com"))
        assertEquals(
            CheckTarget("example.com", 443, true, "/health?full=1"),
            CheckTarget.parse("https://example.com/health?full=1"),
        )
        assertEquals(
            CheckTarget("10.0.2.2", 8080, false, "/"),
            CheckTarget.parse("http://10.0.2.2:8080"),
        )
        assertEquals(
            CheckTarget("example.com", 80, false, "/"),
            CheckTarget.parse("example.com:80"),
        )
        assertEquals(
            CheckTarget("example.com", 8443, true, "/"),
            CheckTarget.parse(" example.com:8443 "),
        )
        assertEquals(CheckTarget("::1", 443, true, "/"), CheckTarget.parse("[::1]:443"))
        listOf("", "two words", "ftp://example.com", "example.com:99999", "http://").forEach {
            assertNull(CheckTarget.parse(it), it)
        }
    }

    @Test
    fun pinsAKeyTheWayOkHttpDoes() {
        val pem = File(System.getProperty("fixtures.dir"), "apks/test-debug-cert.pem").readBytes()
        val cert =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem))
                as X509Certificate
        val described = TlsCertificates.describe(cert)
        // openssl x509 -pubkey | openssl pkey -pubin -outform der | openssl dgst -sha256 | base64
        assertEquals("sha256/LbSBDLUyUHtcLa1l36x6dkMS6JwDwynaQwMl5a8+KBw=", described.pin)
        assertTrue(described.sha256.startsWith("DB:01:6D:44"))
        assertEquals(emptyList(), described.names)
        assertTrue(described.daysLeft(cert.notBefore.time) > 10_000)
    }

    @Test
    fun readsAnAnswersHeadAndNotItsBody() {
        val answer =
            HttpReplies.parse(
                ByteArrayInputStream(
                    "HTTP/1.1 301 Moved Permanently\r\nLocation: https://x/\r\nServer: nginx\r\n\r\nbody"
                        .toByteArray()
                )
            )
        assertEquals(301, answer.status)
        assertEquals("Moved Permanently", answer.reason)
        assertEquals(listOf("Location" to "https://x/", "Server" to "nginx"), answer.headers)
        assertFailsWith<IOException> {
            HttpReplies.parse(ByteArrayInputStream("SSH-2.0-x\r\n".toByteArray()))
        }
        assertFailsWith<IOException> { HttpReplies.parse(ByteArrayInputStream(ByteArray(0))) }
    }

    @Test
    fun checksALocalServerStepByStep() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            var asked = ""
            val serving = thread {
                server.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    asked =
                        generateSequence { reader.readLine() }
                            .takeWhile { it.isNotEmpty() }
                            .joinToString("\n")
                    client
                        .getOutputStream()
                        .write("HTTP/1.1 204 No Content\r\nServer: test\r\n\r\n".toByteArray())
                }
            }
            val report =
                NetworkProbe.check(CheckTarget("127.0.0.1", server.localPort, false, "/health"))
            serving.join(5_000)
            assertIs<Step.Done<List<String>>>(report.dns)
            assertEquals("127.0.0.1", (report.tcp as Step.Done).value)
            assertNull(report.tls)
            val http = (report.http as Step.Done).value
            assertEquals(204, http.status)
            assertEquals(listOf("Server" to "test"), http.headers)
            assertTrue(
                asked.startsWith("GET /health HTTP/1.1\nHost: 127.0.0.1:${server.localPort}"),
                asked,
            )
        }
    }

    @Test
    fun aClosedPortStopsAtTheConnection() {
        val port = Loopback.closedPort()
        val report =
            NetworkProbe.check(CheckTarget("127.0.0.1", port, false, "/"), timeoutMillis = 2_000)
        assertIs<Step.Failed>(report.tcp)
        assertNull(report.http)
    }

    @Test
    fun aNameThatDoesntResolveStopsAtDns() {
        val report = NetworkProbe.check(CheckTarget("droidective-check.invalid", 443, true, "/"))
        assertIs<Step.Failed>(report.dns)
        assertNull(report.tcp)
    }

    @Test
    fun pinsPasteIntoOkHttpAndANetworkSecurityConfig() {
        val pins = listOf("sha256/AAA=", "sha256/BBB=")
        assertEquals(
            "CertificatePinner.Builder()\n" +
                "    .add(\"api.example.com\", \"sha256/AAA=\")\n" +
                "    .add(\"api.example.com\", \"sha256/BBB=\")\n" +
                "    .build()",
            PinSnippets.okHttp("api.example.com", pins),
        )
        assertEquals(
            "<domain-config>\n" +
                "    <domain includeSubdomains=\"false\">api.example.com</domain>\n" +
                "    <pin-set>\n" +
                "        <pin digest=\"SHA-256\">AAA=</pin>\n" +
                "        <pin digest=\"SHA-256\">BBB=</pin>\n" +
                "    </pin-set>\n" +
                "</domain-config>",
            PinSnippets.networkSecurityConfig("api.example.com", pins),
        )
    }
}
