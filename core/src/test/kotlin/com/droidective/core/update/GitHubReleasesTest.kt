package com.droidective.core.update

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitHubReleasesTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val endpoint = "http://127.0.0.1:${server.address.port}/latest"
    private var userAgent: String? = null

    @AfterTest fun stop() = server.stop(0)

    private fun answer(code: Int, body: String) {
        server.createContext("/latest") { exchange ->
            userAgent = exchange.requestHeaders.getFirst("User-Agent")
            val bytes = body.encodeToByteArray()
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun latest() = GitHubReleases("0.1.0", endpoint).latest()

    @Test
    fun findsTheLatestReleaseAndSaysOnlyItsVersion() {
        answer(200, ReleaseTest.reply())
        val found = assertIs<Latest.Found>(latest())
        assertEquals("v0.2.0", found.release.tag)
        assertEquals("Droidective-Android/0.1.0", userAgent)
    }

    @Test
    fun noReleaseYetIsNotAFailure() {
        answer(404, """{"message": "Not Found"}""")
        assertEquals(Latest.None, latest())
    }

    @Test
    fun aRateLimitSaysWhenToTryAgain() {
        answer(403, """{"message": "API rate limit exceeded"}""")
        val failed = assertIs<Latest.Failed>(latest())
        assertTrue("60 checks an hour" in failed.reason, failed.reason)
    }

    @Test
    fun aServerErrorNamesItsCode() {
        answer(502, "")
        assertEquals(Latest.Failed("GitHub answered HTTP 502."), latest())
    }

    @Test
    fun aReplyThatIsNotAReleaseFails() {
        answer(200, "<html>captive portal</html>")
        assertIs<Latest.Failed>(latest())
    }

    @Test
    fun anOversizedReplyIsCutOff() {
        answer(200, "x".repeat((1 shl 20) + 10))
        assertEquals(Latest.Failed("GitHub’s reply was too large."), latest())
    }

    @Test
    fun nobodyListeningIsAFailureNotACrash() {
        val port = ServerSocket(0).use { it.localPort }
        val failed =
            assertIs<Latest.Failed>(GitHubReleases("0.1.0", "http://127.0.0.1:$port/").latest())
        assertTrue(failed.reason.startsWith("Couldn’t reach GitHub"), failed.reason)
    }
}
