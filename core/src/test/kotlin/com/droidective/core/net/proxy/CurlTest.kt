package com.droidective.core.net.proxy

import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.HTTP_2
import com.droidective.core.net.http.Header
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A copied command as a shell runs it, with `curl` standing in to say what it was given. */
class CurlTest {
    @Test
    fun aGrpcCallIsOneCommandThatSendsItsBytesOnHttp2() {
        // A message with every byte value in it, and the three a shell or printf would act on.
        val message = ByteArray(256) { it.toByte() } + "'\\%".toByteArray()
        val body = framed(message)
        val (args, sent) = run(Curl.of(grpc(body)))
        assertEquals(
            listOf(
                "--http2",
                "-X",
                "POST",
                "https://grpcb.in:9001/hello.HelloService/SayHello",
                "-H",
                "content-type: application/grpc",
                "-H",
                "te: trailers",
                "-H",
                "user-agent: grpc-java-okhttp/1.70.0",
                "--data-binary",
                "@-",
            ),
            args,
        )
        assertContentEquals(body, sent)
    }

    @Test
    fun anyBytesComeThroughPrintfAsTheyWere() {
        val random = Random(7)
        repeat(20) {
            // Led by a byte UTF-8 never starts with, so it is never taken for text.
            val body = byteArrayOf(0xff.toByte()) + random.nextBytes(random.nextInt(0, 2_000))
            assertContentEquals(body, run(Curl.of(grpc(body))).second)
        }
    }

    @Test
    fun textWithANulInItGoesAsBytesSinceNoArgumentCanHoldOne() {
        val body = "a\u0000b".toByteArray()
        val (args, sent) = run(Curl.of(http11(CapturedBody(body, 3, truncated = false))))
        assertEquals("--data-binary", args[args.size - 2])
        assertContentEquals(body, sent)
    }

    @Test
    fun aHalfKeptBodyIsSaidAheadOfACommandThatStillRuns() {
        val part = CapturedBody("{\"a\":".toByteArray(), 9_000, truncated = true)
        val command = Curl.of(http11(part))
        assertEquals(
            "# only the first 5 bytes were kept, so only they are sent",
            command.lines()[0],
        )
        // Once, the note sat inside the command, and the shell ended it there.
        val (args, _) = run(command)
        assertEquals(listOf("-X", "POST", URL, "--data-raw", "{\"a\":"), args)
    }

    @Test
    fun aBinaryBodyTooLongToWriteOutIsSaidAndLeftOut() {
        val big = CapturedBody(ByteArray(70_000), 70_000, truncated = false)
        val command = Curl.of(http11(big))
        assertEquals(
            "# the body was 70000 bytes of binary, too long to write out here",
            command.lines()[0],
        )
        assertEquals(listOf("-X", "POST", URL), run(command).first)
    }

    @Test
    fun http2InTheClearIsSpokenFromTheFirstByte() {
        val cleartext = grpc(framed(ByteArray(0))).copy(secure = false, port = 50051)
        assertEquals("--http2-prior-knowledge", run(Curl.of(cleartext)).first.first())
        // HTTP/1.1 keeps leaving TE out: there it names the hop, not the request.
        val old =
            http11(CapturedBody.NONE)
                .copy(
                    request =
                        RequestHead(
                            "GET",
                            "/",
                            "HTTP/1.1",
                            Headers().add("TE", "trailers"),
                        )
                )
        assertTrue("TE" !in Curl.of(old))
    }

    private fun flowWith(
        method: String = "GET",
        headers: Headers = Headers(),
        requestBody: CapturedBody = CapturedBody.NONE,
    ) =
        Flow(
            id = 1,
            startedAt = 0,
            secure = true,
            host = "api.example.com",
            port = 443,
            request = RequestHead(method, "/orders?page=2", "HTTP/1.1", headers),
            requestBody = requestBody,
        )

    @Test
    fun aPlainGetIsOneLine() {
        assertEquals("curl 'https://api.example.com/orders?page=2'", Curl.of(flowWith()))
    }

    @Test
    fun anotherMethodIsNamed() {
        assertContains(Curl.of(flowWith(method = "DELETE")), "curl -X DELETE")
    }

    @Test
    fun headersAndBodyComeThrough() {
        val command =
            Curl.of(
                flowWith(
                    method = "POST",
                    headers =
                        Headers(
                            listOf(
                                Header("Content-Type", "application/json"),
                                Header("Authorization", "Bearer abc123"),
                            )
                        ),
                    requestBody = CapturedBody("""{"a":1}""".toByteArray(), 7, false),
                )
            )
        assertContains(command, "-H 'Content-Type: application/json'")
        assertContains(command, "-H 'Authorization: Bearer abc123'")
        assertContains(command, """--data-raw '{"a":1}'""")
    }

    @Test
    fun whatBelongsToTheHopIsLeftOut() {
        val command =
            Curl.of(
                flowWith(
                    headers =
                        Headers(
                            listOf(
                                Header("Content-Length", "7"),
                                Header("Connection", "keep-alive"),
                                Header("Proxy-Connection", "keep-alive"),
                                Header("Accept", "*/*"),
                            )
                        )
                )
            )
        assertContains(command, "Accept")
        assertTrue("Content-Length" !in command)
        assertTrue("Connection" !in command)
    }

    @Test
    fun aQuoteInAValueCannotEndTheQuoting() {
        val command =
            Curl.of(
                flowWith(
                    method = "POST",
                    headers = Headers(listOf(Header("X-Odd", "it's; rm -rf /"))),
                    requestBody = CapturedBody("it's".toByteArray(), 4, false),
                )
            )
        assertContains(command, """'X-Odd: it'\''s; rm -rf /'""")
        assertContains(command, """--data-raw 'it'\''s'""")
    }

    @Test
    fun aBinaryBodyIsPipedInByPrintf() {
        val binary = CapturedBody(byteArrayOf(0, -1, -2), 3, false)
        val command = Curl.of(flowWith(method = "POST", requestBody = binary))
        assertContains(command, "printf '\\000\\377\\376' | curl -X POST")
        assertContains(command, "--data-binary @-")
        assertTrue("--data-raw" !in command)
    }

    @Test
    fun aHalfKeptBodySaysHowMuchOfItThisIs() {
        val part = CapturedBody("{\"a\":".toByteArray(), 9_000, true)
        assertContains(
            Curl.of(flowWith(method = "POST", requestBody = part)).lines().first(),
            "# only the first 5 bytes",
        )
    }

    /** [command] run by `/bin/sh` with `curl` a function: the arguments it got, and its stdin. */
    private fun run(command: String): Pair<List<String>, ByteArray> {
        val stand = "curl() { for a in \"\$@\"; do printf '%s\\n' \"\$a\" >&2; done; cat; }"
        val process = ProcessBuilder("/bin/sh", "-c", "$stand\n$command").start()
        process.outputStream.close()
        val sent = process.inputStream.readBytes()
        val args = process.errorStream.readBytes().decodeToString()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "sh did not exit")
        assertEquals(0, process.exitValue(), "sh said: $args")
        return args.lines().dropLast(1) to sent
    }

    private fun grpc(body: ByteArray) =
        Flow(
            id = 1,
            startedAt = 0,
            secure = true,
            host = "grpcb.in",
            port = 9001,
            request =
                RequestHead(
                    "POST",
                    "/hello.HelloService/SayHello",
                    HTTP_2,
                    Headers(
                        listOf(
                            Header("content-type", "application/grpc"),
                            Header("te", "trailers"),
                            Header("user-agent", "grpc-java-okhttp/1.70.0"),
                        )
                    ),
                ),
            requestBody = CapturedBody(body, body.size.toLong(), truncated = false),
        )

    private fun http11(body: CapturedBody) =
        Flow(
            id = 2,
            startedAt = 0,
            secure = true,
            host = "api.example.com",
            port = 443,
            request = RequestHead("POST", "/orders", "HTTP/1.1", Headers()),
            requestBody = body,
        )

    private fun framed(message: ByteArray): ByteArray {
        val size = message.size
        return byteArrayOf(0, 0, 0, (size ushr 8).toByte(), size.toByte()) + message
    }

    private companion object {
        const val URL = "https://api.example.com/orders"
    }
}
