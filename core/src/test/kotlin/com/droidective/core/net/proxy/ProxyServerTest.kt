package com.droidective.core.net.proxy

import com.droidective.core.net.http.BodyFraming
import com.droidective.core.net.http.HttpBody
import com.droidective.core.net.http.HttpReader
import com.droidective.core.net.tls.ProxyCertificates
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The proxy against real sockets: a server of our own on loopback, a client that goes through the
 * proxy to reach it, and the record that comes out the other side.
 */
class ProxyServerTest {
    private val sink = Collected()
    private val refusals = Refusals()
    private var server: ProxyServer? = null

    @AfterTest
    fun stopTheProxy() {
        server?.stop()
    }

    private fun proxy(
        rules: ProxyRules = ProxyRules(),
        owners: FlowOwners = FlowOwners.UNKNOWN,
        breaker: Breaker = Breaker.GO,
    ): Int {
        val running =
            ProxyServer(certificates, sink, trustingUpstream, owners, refusals, breaker = breaker)
        running.rules = rules
        server = running
        return running.start()
    }

    @Test
    fun itListensOnThePhoneAndNowhereElse() {
        proxy()
        // Anything wider would be an open proxy for everyone else on the Wi-Fi.
        val listening = assertNotNull(server?.address)
        assertTrue(listening.isLoopbackAddress, "the proxy was listening on $listening")
    }

    @Test
    fun itListensWhereThePhoneIsPointed() {
        // The phone's proxy setting says 127.0.0.1. Android's own loopback address is ::1, and a
        // proxy bound there refused every connection the phone made to it.
        proxy()
        assertEquals("127.0.0.1", server?.address?.hostAddress)
    }

    @Test
    fun aPlainRequestIsForwardedAndRecorded() {
        FakeServer().use { origin ->
            origin.reply = { ok("hello") }
            val port = proxy()
            assertEquals("hello", get("http://127.0.0.1:${origin.port}/thing?x=1", port))

            val flow = sink.next()
            assertEquals("GET", flow.request.method)
            assertEquals("127.0.0.1", flow.host)
            assertEquals(origin.port, flow.port)
            assertEquals("/thing?x=1", flow.request.path)
            assertEquals(200, flow.status)
            assertFalse(flow.secure)
            assertContentEquals("hello".toByteArray(), assertNotNull(flow.responseBody).bytes)
            assertNotNull(flow.durationMillis)
            // The server is handed a path, never the whole URL the proxy was given.
            assertEquals("/thing?x=1", origin.received.single().target)
        }
    }

    @Test
    fun aFlowCarriesTheAppThatOpenedTheConnection() {
        FakeServer().use { origin ->
            val port = proxy(owners = FlowOwners { 10144 })
            get("http://127.0.0.1:${origin.port}/thing", port)
            assertEquals(10144, sink.nextOwned().uid)
        }
    }

    @Test
    fun nobodyWaitsToLearnWhichAppItWas() {
        // On a phone the answer is a shell round trip away. The request goes, the answer comes
        // back to the app, and the record learns who asked when it can.
        val answer = CountDownLatch(1)
        FakeServer().use { origin ->
            origin.reply = { ok("hello") }
            val port =
                proxy(
                    owners =
                        FlowOwners {
                            answer.await(10, TimeUnit.SECONDS)
                            10144
                        }
                )
            assertEquals("hello", get("http://127.0.0.1:${origin.port}/thing", port))
            val first = sink.next()
            assertNull(first.uid)
            assertEquals(200, first.status)

            answer.countDown()
            val owned = sink.next()
            assertEquals(first.id, owned.id)
            assertEquals(10144, owned.uid)
            assertEquals(200, owned.status)
        }
    }

    @Test
    fun anAppLearnedWhileItsFlowIsBeingRecordedIsNotLost() {
        val asked = CountDownLatch(1)
        val answered = CountDownLatch(1)
        FakeServer().use { origin ->
            val port =
                proxy(
                    owners =
                        FlowOwners {
                            asked.await(10, TimeUnit.SECONDS)
                            answered.countDown()
                            10144
                        }
                )
            // The answer arrives in the middle of the first record, after it was stamped without
            // one: the moment a check for "already answered?" would have let it slip by.
            sink.whileRecording = { flow ->
                if (flow.uid == null) {
                    asked.countDown()
                    answered.await(10, TimeUnit.SECONDS)
                    Thread.sleep(SETTLE_MILLIS)
                }
            }
            get("http://127.0.0.1:${origin.port}/thing", port)

            assertNull(sink.next().uid)
            assertEquals(10144, sink.next().uid)
        }
    }

    @Test
    fun aSecondRunGoesOnFromTheIdsOfTheFirst() {
        // Stopped and started again with the same store: the second run's first flow was id 1
        // again, and replaced the first run's.
        val ids = AtomicLong()
        FakeServer().use { origin ->
            val first = ProxyServer(certificates, sink, trustingUpstream, ids = ids)
            get("http://127.0.0.1:${origin.port}/one", first.start())
            first.stop()
            val second = ProxyServer(certificates, sink, trustingUpstream, ids = ids)
            server = second
            get("http://127.0.0.1:${origin.port}/two", second.start())
            assertEquals(listOf(1L, 2L), sink.all(2).map { it.id })
        }
    }

    @Test
    fun aFlowSaysNothingAboutTheAppWhenNothingCanBeTold() {
        FakeServer().use { origin ->
            val port = proxy()
            get("http://127.0.0.1:${origin.port}/thing", port)
            assertNull(sink.next().uid)
        }
    }

    @Test
    fun aRequestBodyIsDeliveredAndRecorded() {
        FakeServer().use { origin ->
            val port = proxy()
            val answer = post("http://127.0.0.1:${origin.port}/send", port, "{\"hello\":\"world\"}")
            assertEquals("hi", answer)

            assertEquals("{\"hello\":\"world\"}", origin.received.single().body)
            val flow = sink.next()
            assertContentEquals(
                "{\"hello\":\"world\"}".toByteArray(),
                flow.requestBody.bytes,
            )
        }
    }

    @Test
    fun theProxyPassesOnTheServersOwnHeadersUntouched() {
        FakeServer().use { origin ->
            origin.reply = {
                "HTTP/1.1 418 I am a teapot\r\nX-Brew: tea\r\nContent-Length: 0\r\n\r\n"
            }
            val port = proxy()
            val connection = connect("http://127.0.0.1:${origin.port}/brew", port)
            assertEquals(418, connection.responseCode)
            assertEquals("tea", connection.getHeaderField("X-Brew"))
            assertEquals(418, sink.next().status)
        }
    }

    @Test
    fun aChunkedAnswerIsRecordedAsItsContentRatherThanItsFraming() {
        FakeServer().use { origin ->
            origin.reply = {
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
            }
            val port = proxy()
            assertEquals("hello world", get("http://127.0.0.1:${origin.port}/stream", port))
            assertContentEquals(
                "hello world".toByteArray(),
                assertNotNull(sink.next().responseBody).bytes,
            )
        }
    }

    @Test
    fun twoRequestsCanShareOneConnection() {
        FakeServer().use { origin ->
            val port = proxy()
            Socket("127.0.0.1", port).use { socket ->
                val reader = HttpReader(socket.getInputStream())
                repeat(2) { index ->
                    origin.reply = { ok("body$index") }
                    val target = "http://127.0.0.1:${origin.port}/call$index"
                    socket
                        .getOutputStream()
                        .write(
                            wire("GET $target HTTP/1.1\r\nHost: 127.0.0.1:${origin.port}\r\n\r\n")
                        )
                    socket.getOutputStream().flush()
                    val head = assertNotNull(reader.readResponse())
                    val body =
                        HttpBody.relay(BodyFraming.of(head, "GET"), socket.getInputStream(), null)
                    assertEquals("body$index", String(body.bytes))
                }
            }
            assertEquals(listOf("/call0", "/call1"), origin.received.map { it.target })
            // One connection to the proxy, and one on from it.
            assertEquals(1, origin.connections.get())
            assertEquals(2, sink.all(count = 2).size)
        }
    }

    @Test
    fun aHeldRequestGoesOutOnAFreshConnection() {
        FakeServer().use { origin ->
            val port = proxy(breaker = Scripted())
            // Held for minutes, the server could have closed the one it had been sharing.
            server?.breakpoints = stopAt("http://127.0.0.1:${origin.port}/call1")
            Socket("127.0.0.1", port).use { socket ->
                val reader = HttpReader(socket.getInputStream())
                repeat(2) { index ->
                    origin.reply = { ok("body$index") }
                    val target = "http://127.0.0.1:${origin.port}/call$index"
                    socket
                        .getOutputStream()
                        .write(
                            wire("GET $target HTTP/1.1\r\nHost: 127.0.0.1:${origin.port}\r\n\r\n")
                        )
                    socket.getOutputStream().flush()
                    val head = assertNotNull(reader.readResponse())
                    val body =
                        HttpBody.relay(BodyFraming.of(head, "GET"), socket.getInputStream(), null)
                    assertEquals("body$index", String(body.bytes))
                }
            }
            assertEquals(2, origin.connections.get())
        }
    }

    @Test
    fun aServerThatCannotBeReachedIsAnswered502AndStillRecorded() {
        val closed = ServerSocket(0).use { it.localPort }
        val port = proxy()
        Socket("127.0.0.1", port).use { socket ->
            socket
                .getOutputStream()
                .write(
                    wire(
                        "GET http://127.0.0.1:$closed/gone HTTP/1.1\r\nHost: 127.0.0.1:$closed\r\n\r\n"
                    )
                )
            socket.getOutputStream().flush()
            assertEquals(
                502,
                assertNotNull(HttpReader(socket.getInputStream()).readResponse()).status,
            )
        }
        val flow = sink.next()
        assertNull(flow.status)
        assertNotNull(flow.failure)
        assertEquals("/gone", flow.request.path)
    }

    @Test
    fun anOpenedTlsExchangeIsReadInTheClear() {
        secureServer().use { origin ->
            origin.reply = { ok("secret") }
            val port = proxy()
            assertEquals("secret", throughTunnel(port, origin.port, "/private"))

            val flow = sink.next()
            assertTrue(flow.secure)
            assertFalse(flow.tunnelled)
            assertEquals("/private", flow.request.path)
            assertEquals("https://localhost:${origin.port}/private", flow.url)
            assertEquals(200, flow.status)
            assertContentEquals("secret".toByteArray(), assertNotNull(flow.responseBody).bytes)
            // It really was read: the server saw a request, and the proxy wrote down its content.
            assertEquals("/private", origin.received.single().target)
        }
    }

    @Test
    fun aHostLeftAloneIsPassedThroughWithoutBeingRead() {
        secureServer().use { origin ->
            origin.reply = { ok("secret") }
            val port = proxy(ProxyRules(except = listOf("localhost")))
            assertEquals("secret", throughTunnel(port, origin.port, "/private"))

            val flow = sink.next()
            assertTrue(flow.tunnelled)
            assertNull(flow.response)
            assertNull(flow.status)
            // Nothing of what crossed was kept, which is the whole point of leaving it alone.
            assertTrue(flow.requestBody.isEmpty)
            assertEquals("https://localhost:${origin.port}", flow.url)
        }
    }

    @Test
    fun aMockAnswersWithoutTheServerBeingAsked() {
        FakeServer().use { origin ->
            val port = proxy()
            server?.mocks =
                listOf(Mock("m1", "http://127.0.0.1:${origin.port}/menu", body = "mocked"))
            assertEquals("mocked", get("http://127.0.0.1:${origin.port}/menu?store=7", port))

            val flow = sink.next()
            assertEquals("m1", flow.mocked)
            assertEquals(200, flow.status)
            assertEquals(0, origin.connections.get())
        }
    }

    @Test
    fun aMockedHttpsAnswerIsReadInsideTheTunnel() {
        secureServer().use { origin ->
            origin.reply = { ok("real") }
            val port = proxy()
            server?.mocks = listOf(Mock("m1", "https://localhost:*/private", body = "mocked"))
            assertEquals("mocked", throughTunnel(port, origin.port, "/private"))
            assertEquals("m1", sink.next().mocked)
        }
    }

    @Test
    fun whatNoMockAnswersStillGoesToTheServer() {
        FakeServer().use { origin ->
            origin.reply = { ok("real") }
            val port = proxy()
            server?.mocks =
                listOf(Mock("m1", "http://127.0.0.1:${origin.port}/menu", enabled = false))
            assertEquals("real", get("http://127.0.0.1:${origin.port}/menu", port))
            assertNull(sink.next().mocked)
        }
    }

    @Test
    fun aSlowerNetworkIsSlower() {
        FakeServer().use { origin ->
            val body = "x".repeat(10_000)
            origin.reply = { ok(body) }
            val port = proxy()
            // Ten thousand bytes at twenty thousand a second, behind 300 ms: at least 800 ms.
            server?.throttle = Throttle(300, 20_000, 0)
            val started = System.nanoTime()
            assertEquals(body, get("http://127.0.0.1:${origin.port}/big", port))
            val took = (System.nanoTime() - started) / 1_000_000
            assertTrue(took >= 750, "took $took ms")
        }
    }

    @Test
    fun aMockIsAsSlowAsTheNetwork() {
        FakeServer().use { origin ->
            val port = proxy()
            server?.mocks = listOf(Mock("m", "http://127.0.0.1:${origin.port}/m", body = "mocked"))
            server?.throttle = Throttle(400, 0, 0)
            val started = System.nanoTime()
            assertEquals("mocked", get("http://127.0.0.1:${origin.port}/m", port))
            assertTrue((System.nanoTime() - started) / 1_000_000 >= 380)
        }
    }

    /** A breaker that answers as it is told and keeps what it was shown. */
    private class Scripted(
        val onRequest: (HeldRequest) -> RequestVerdict = { RequestVerdict.Go },
        val onResponse: (HeldResponse) -> ResponseVerdict = { ResponseVerdict.Go },
    ) : Breaker {
        val requests = CopyOnWriteArrayList<HeldRequest>()
        val responses = CopyOnWriteArrayList<HeldResponse>()

        override fun request(held: HeldRequest) = onRequest(held).also { requests += held }

        override fun response(held: HeldResponse) = onResponse(held).also { responses += held }
    }

    private fun stopAt(url: String, requests: Boolean = true, responses: Boolean = false) =
        listOf(Breakpoint("b1", url, requests = requests, responses = responses))

    @Test
    fun aHeldRequestGoesOnAsItWasWhenLetGo() {
        FakeServer().use { origin ->
            val breaker = Scripted()
            val port = proxy(breaker = breaker)
            server?.breakpoints = stopAt("http://127.0.0.1:${origin.port}/orders")
            post("http://127.0.0.1:${origin.port}/orders", port, "{\"qty\":1}")

            val held = breaker.requests.single()
            assertEquals("/orders", held.head.target)
            assertContentEquals("{\"qty\":1}".toByteArray(), held.body)
            assertEquals("{\"qty\":1}", origin.received.single().body)
            val flow = sink.next()
            assertEquals("b1", flow.paused)
            assertFalse(flow.edited)
        }
    }

    @Test
    fun aHeldRequestGoesOnChanged() {
        FakeServer().use { origin ->
            val breaker =
                Scripted(
                    onRequest = { held ->
                        val body = "{\"qty\":5}".toByteArray()
                        RequestVerdict.Send(held.head.with(target = "/orders?changed=1"), body)
                    }
                )
            val port = proxy(breaker = breaker)
            server?.breakpoints = stopAt("http://127.0.0.1:${origin.port}/orders")
            post("http://127.0.0.1:${origin.port}/orders", port, "{\"qty\":1}")

            val asked = origin.received.single()
            assertEquals("/orders?changed=1", asked.target)
            assertEquals("{\"qty\":5}", asked.body)
            assertEquals("9", asked.headers["content-length"])
            val flow = sink.next()
            assertTrue(flow.edited)
            assertEquals("/orders?changed=1", flow.request.target)
        }
    }

    @Test
    fun aStoppedRequestNeverReachesTheServer() {
        FakeServer().use { origin ->
            val port = proxy(breaker = Scripted(onRequest = { RequestVerdict.Stop }))
            server?.breakpoints = stopAt("http://127.0.0.1:${origin.port}/*")
            val connection = connect("http://127.0.0.1:${origin.port}/x", port)
            assertEquals(502, connection.responseCode)
            // Said as what it is: the server was never the problem.
            assertEquals(
                "Droidective: stopped at a breakpoint.",
                connection.errorStream.use { String(it.readBytes()) },
            )

            assertEquals(emptyList(), origin.received.toList())
            assertEquals(Holding.STOPPED, sink.next().failure)
        }
    }

    @Test
    fun aHeldAnswerReachesTheAppAsItWasChanged() {
        FakeServer().use { origin ->
            origin.reply = {
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 10\r\n\r\n" +
                    "{\"open\":1}"
            }
            val breaker =
                Scripted(
                    onResponse = { held ->
                        ResponseVerdict.Answer(held.head, "{\"open\":0}".toByteArray())
                    }
                )
            val port = proxy(breaker = breaker)
            server?.breakpoints =
                stopAt("http://127.0.0.1:${origin.port}/status", requests = false, responses = true)
            assertEquals("{\"open\":0}", get("http://127.0.0.1:${origin.port}/status", port))

            assertEquals(emptyList(), breaker.requests.toList())
            assertContentEquals("{\"open\":1}".toByteArray(), breaker.responses.single().body)
            assertTrue(sink.next().edited)
        }
    }

    @Test
    fun aCompressedAnswerIsShownDecodedAndPassedOnAsItCame() {
        FakeServer().use { origin ->
            val zipped = ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(zipped).use { it.write("hello".toByteArray()) }
            val bytes = zipped.toByteArray()
            origin.replyBytes = {
                ("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: ${bytes.size}" +
                        "\r\n\r\n")
                    .toByteArray(Charsets.ISO_8859_1) + bytes
            }
            val breaker = Scripted()
            val port = proxy(breaker = breaker)
            server?.breakpoints =
                stopAt("http://127.0.0.1:${origin.port}/z", requests = false, responses = true)
            val connection = connect("http://127.0.0.1:${origin.port}/z", port)
            assertEquals("gzip", connection.getHeaderField("Content-Encoding"))
            assertContentEquals(bytes, connection.inputStream.use { it.readBytes() })
            assertContentEquals("hello".toByteArray(), breaker.responses.single().body)
            assertNull(breaker.responses.single().compressed)
        }
    }

    @Test
    fun anAnswerToBeHeldIsAskedForInCodingsThatCanBeRead() {
        FakeServer().use { origin ->
            val port = proxy(breaker = Scripted())
            server?.breakpoints =
                stopAt("http://127.0.0.1:${origin.port}/page", requests = false, responses = true)
            val connection = connect("http://127.0.0.1:${origin.port}/page", port)
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate, br, zstd")
            connection.inputStream.use { it.readBytes() }

            assertEquals("gzip, deflate", origin.received.single().headers["accept-encoding"])
        }
    }

    @Test
    fun aRequestHeldOnItsOwnAsksForWhatItAskedFor() {
        FakeServer().use { origin ->
            val port = proxy(breaker = Scripted())
            server?.breakpoints = stopAt("http://127.0.0.1:${origin.port}/page")
            val connection = connect("http://127.0.0.1:${origin.port}/page", port)
            connection.setRequestProperty("Accept-Encoding", "gzip, br")
            connection.inputStream.use { it.readBytes() }

            assertEquals("gzip, br", origin.received.single().headers["accept-encoding"])
        }
    }

    @Test
    fun anAnswerThatCantBeDecodedSaysWhatItIsStillIn() {
        FakeServer().use { origin ->
            val squeezed = byteArrayOf(0x1b, 0x0e, 0x00)
            origin.replyBytes = {
                ("HTTP/1.1 200 OK\r\nContent-Encoding: br\r\nContent-Length: 3\r\n\r\n")
                    .toByteArray(Charsets.ISO_8859_1) + squeezed
            }
            val breaker = Scripted()
            val port = proxy(breaker = breaker)
            server?.breakpoints =
                stopAt("http://127.0.0.1:${origin.port}/b", requests = false, responses = true)
            connect("http://127.0.0.1:${origin.port}/b", port).inputStream.use { it.readBytes() }

            val held = breaker.responses.single()
            assertEquals("br", held.compressed)
            assertContentEquals(squeezed, held.body)
        }
    }

    @Test
    fun anAnswerHeldPastTheAppGivingUpIsStillOnRecord() {
        FakeServer().use { origin ->
            val holding = CountDownLatch(1)
            val gone = CountDownLatch(1)
            val breaker =
                Scripted(
                    onResponse = {
                        holding.countDown()
                        gone.await(10, TimeUnit.SECONDS)
                        ResponseVerdict.Go
                    }
                )
            val port = proxy(breaker = breaker)
            server?.breakpoints =
                stopAt("http://127.0.0.1:${origin.port}/slow", requests = false, responses = true)
            Socket("127.0.0.1", port).use { socket ->
                val target = "http://127.0.0.1:${origin.port}/slow"
                socket
                    .getOutputStream()
                    .write(wire("GET $target HTTP/1.1\r\nHost: 127.0.0.1:${origin.port}\r\n\r\n"))
                socket.getOutputStream().flush()
                assertTrue(holding.await(10, TimeUnit.SECONDS))
                // The app hangs up, hard, while its answer is held.
                socket.setSoLinger(true, 0)
            }
            gone.countDown()

            val flow = sink.next()
            assertEquals(Holding.HUNG_UP, flow.failure)
            assertEquals(200, flow.response?.status)
        }
    }

    @Test
    fun aRemappedRequestGoesToTheOtherServerAsTheAppAskedIt() {
        FakeServer().use { asked ->
            FakeServer().use { local ->
                local.reply = { ok("from the laptop") }
                val port = proxy()
                server?.remaps =
                    listOf(
                        Remap(
                            "r",
                            "http://127.0.0.1:${asked.port}/*",
                            "http://127.0.0.1:${local.port}",
                        )
                    )

                assertEquals(
                    "from the laptop",
                    get("http://127.0.0.1:${asked.port}/menu?x=1", port),
                )

                assertEquals(emptyList(), asked.received.toList())
                val there = local.received.single()
                assertEquals("/menu?x=1", there.target)
                assertEquals("127.0.0.1:${local.port}", there.headers["host"])
                val flow = sink.next()
                assertEquals(asked.port, flow.port)
                assertEquals("http://127.0.0.1:${local.port}", flow.sentTo)
            }
        }
    }

    @Test
    fun aHeldRequestGoesWhereARemapSendsIt() {
        FakeServer().use { asked ->
            FakeServer().use { local ->
                val port = proxy(breaker = Scripted())
                val url = "http://127.0.0.1:${asked.port}/orders"
                server?.breakpoints = stopAt(url)
                server?.remaps = listOf(Remap("r", url, "http://127.0.0.1:${local.port}"))
                post(url, port, "{}")

                assertEquals(emptyList(), asked.received.toList())
                assertEquals("127.0.0.1:${local.port}", local.received.single().headers["host"])
                assertEquals("http://127.0.0.1:${local.port}", sink.next().sentTo)
            }
        }
    }

    @Test
    fun aMockAnswersBeforeARemapSendsAnywhere() {
        FakeServer().use { local ->
            val port = proxy()
            val url = "http://127.0.0.1:1/menu"
            server?.mocks = listOf(Mock("m", url, body = "mocked"))
            server?.remaps = listOf(Remap("r", url, "http://127.0.0.1:${local.port}"))

            assertEquals("mocked", get(url, port))
            assertEquals(emptyList(), local.received.toList())
        }
    }

    @Test
    fun theProxyNeverAsksItself() {
        val port = proxy()
        val connection = connect("http://127.0.0.1:$port/again", port)

        assertEquals(502, connection.responseCode)
        assertTrue("this proxy" in assertNotNull(sink.next().failure))
    }

    @Test
    fun aRequestNoBreakpointMatchesIsNotHeld() {
        FakeServer().use { origin ->
            val breaker = Scripted()
            val port = proxy(breaker = breaker)
            server?.breakpoints = stopAt("http://127.0.0.1:${origin.port}/other")
            get("http://127.0.0.1:${origin.port}/thing", port)
            assertEquals(emptyList(), breaker.requests.toList())
            assertNull(sink.next().paused)
        }
    }

    @Test
    fun aRequestIsOnRecordBeforeTheServerIsReached() {
        val closed = ServerSocket(0).use { it.localPort }
        val port = proxy()
        assertFailsWith<IOException> { get("http://127.0.0.1:$closed/slow", port) }

        val failed = sink.next()
        assertNotNull(failed.failure)
        // It was there, waiting, while the dial was still going.
        val waiting = sink.begun.single { it.id == failed.id }
        assertNull(waiting.response)
        assertNull(waiting.failure)
    }

    @Test
    fun anAppThatRefusesTheCertificateIsRecordedAsRefusingIt() {
        val port = proxy()
        refuse(port, "localhost:443")

        val flow = sink.next()
        // With its alert, "(certificate unknown)", when it arrives before the app hangs up.
        assertTrue(assertNotNull(flow.failure).startsWith(TlsFailure.REFUSED), flow.failure)
        assertEquals("https://localhost", flow.url)
        assertNull(flow.response)
    }

    @Test
    fun anAppThatRefusedIsLeftAloneTheNextTime() {
        secureServer().use { origin ->
            origin.reply = { ok("secret") }
            val port = proxy(owners = FlowOwners { 10144 })
            refuse(port, "localhost:${origin.port}")
            assertNotNull(sink.next().failure)

            // The same app, the same host: straight through to the server, which it trusts.
            assertEquals("secret", throughTunnel(port, origin.port, "/again"))
            val passed = sink.nextOwned()
            assertTrue(passed.tunnelled)
            assertEquals(Passing.Refused, passed.passing)
            assertEquals(listOf(Refusals.Refusal(10144, "localhost")), refusals.all())
        }
    }

    @Test
    fun withoutSteppingAsideARefusingAppIsReadEveryTime() {
        val port = proxy(ProxyRules(stepAside = false))
        refuse(port, "localhost:443")
        refuse(port, "localhost:443")
        assertNotNull(sink.next().failure)
        assertNotNull(sink.next().failure)
        assertEquals(emptyList(), refusals.all())
    }

    @Test
    fun onlyTheAppsChosenAreRead() {
        secureServer().use { origin ->
            origin.reply = { ok("hello") }
            val port = proxy(ProxyRules(apps = setOf(10200)), owners = FlowOwners { 10144 })
            assertEquals("hello", throughTunnel(port, origin.port, "/thing"))
            val passed = sink.nextOwned()
            assertEquals(Passing.OtherApp, passed.passing)
            assertNull(passed.response)
        }
    }

    @Test
    fun theAppChosenIsRead() {
        secureServer().use { origin ->
            origin.reply = { ok("hello") }
            val port = proxy(ProxyRules(apps = setOf(10144)), owners = FlowOwners { 10144 })
            assertEquals("hello", throughTunnel(port, origin.port, "/thing"))
            val read = sink.nextOwned()
            assertNull(read.passing)
            assertEquals(200, read.status)
        }
    }

    /** A client with the JDK's own trust store, which has never heard of the proxy's authority. */
    private fun refuse(proxyPort: Int, target: String) {
        Socket("127.0.0.1", proxyPort).use { socket ->
            socket.getOutputStream().write(wire("CONNECT $target HTTP/1.1\r\n\r\n"))
            socket.getOutputStream().flush()
            val established = assertNotNull(HttpReader(socket.getInputStream()).readResponse())
            assertEquals(200, established.status)
            val host = target.substringBefore(':')
            val untrusting = SSLContext.getDefault().socketFactory
            val tls = untrusting.createSocket(socket, host, 443, true) as SSLSocket
            assertFailsWith<SSLException> { tls.use { it.startHandshake() } }
        }
    }

    /** CONNECT to the proxy, then TLS inside it, then one request — the client's whole job. */
    private fun throughTunnel(proxyPort: Int, originPort: Int, path: String): String {
        val socket = Socket("127.0.0.1", proxyPort)
        val target = "localhost:$originPort"
        socket.getOutputStream().write(wire("CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n"))
        socket.getOutputStream().flush()
        // Read the proxy's answer off the raw stream: anything buffered past it would be TLS.
        val established = assertNotNull(HttpReader(socket.getInputStream()).readResponse())
        assertEquals(200, established.status)

        val tls =
            trust.socketFactory.createSocket(socket, "localhost", originPort, true) as SSLSocket
        return tls.use {
            it.startHandshake()
            it.outputStream.write(wire("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n"))
            it.outputStream.flush()
            val head = assertNotNull(HttpReader(it.inputStream).readResponse())
            String(HttpBody.relay(BodyFraming.of(head, "GET"), it.inputStream, null).bytes)
        }
    }

    private fun secureServer() =
        FakeServer(
            ProxyTls.serverContext(
                certificates.certificateFor("localhost"),
                certificates.caCertificate,
            )
        )

    private fun connect(url: String, proxyPort: Int): HttpURLConnection {
        val through = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
        return URI(url).toURL().openConnection(through) as HttpURLConnection
    }

    private fun get(url: String, proxyPort: Int): String =
        connect(url, proxyPort).inputStream.use { String(it.readBytes()) }

    private fun post(url: String, proxyPort: Int, body: String): String {
        val connection = connect(url, proxyPort)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.toByteArray().size)
        connection.outputStream.use { it.write(wire(body)) }
        return connection.inputStream.use { String(it.readBytes()) }
    }

    private companion object {
        /** Long enough for an answered future to have finished completing. */
        const val SETTLE_MILLIS = 200L

        /** Generating keys is slow, so every test in the class shares one authority. */
        val certificates: ProxyCertificates by lazy { ProxyCertificates.create("Droidective Test") }

        /**
         * A client, and the proxy's own upstream, that trust the test authority and nothing else.
         */
        val trust: SSLContext by lazy {
            val store = KeyStore.getInstance(KeyStore.getDefaultType())
            store.load(null)
            store.setCertificateEntry("ca", certificates.caCertificate)
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(store)
            SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
        }

        val trustingUpstream = UpstreamTls { origin, timeout ->
            val plain = Socket()
            plain.connect(InetSocketAddress(origin.host, origin.port), timeout)
            val socket =
                trust.socketFactory.createSocket(plain, origin.host, origin.port, true) as SSLSocket
            socket.sslParameters =
                socket.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    applicationProtocols = arrayOf("http/1.1")
                }
            socket.startHandshake()
            socket
        }

        fun ok(body: String) = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body"

        fun wire(text: String) = text.toByteArray(Charsets.ISO_8859_1)
    }
}

/** What the proxy recorded, waited on rather than slept for. */
private class Collected : FlowSink {
    private val done = LinkedBlockingQueue<Flow>()
    private val everything = CopyOnWriteArrayList<Flow>()
    val begun = CopyOnWriteArrayList<Flow>()

    override fun started(flow: Flow) {
        begun += flow
    }

    /** Runs as each record is made, before it is kept: a test's way into the middle of it. */
    @Volatile var whileRecording: (Flow) -> Unit = {}

    override fun finished(flow: Flow) {
        whileRecording(flow)
        everything += flow
        done += flow
    }

    fun next(): Flow = done.poll(10, TimeUnit.SECONDS) ?: error("the proxy recorded nothing")

    /** The next record that knows its app: the first record may come before the answer does. */
    fun nextOwned(): Flow {
        while (true) next()
            .takeIf { it.uid != null }
            ?.let {
                return it
            }
    }

    /** Waits for [count] exchanges to finish, then hands back every one of them. */
    fun all(count: Int): List<Flow> {
        repeat(count) { next() }
        return everything
    }
}

/** One request a server of ours was asked. */
internal data class Asked(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: String,
)

/** A server on loopback that answers whatever [reply] says, plain or over TLS. */
internal class FakeServer(context: SSLContext? = null) : AutoCloseable {
    private val server: ServerSocket =
        context?.serverSocketFactory?.createServerSocket(0) ?: ServerSocket(0)

    val port: Int
        get() = server.localPort

    val received = CopyOnWriteArrayList<Asked>()
    val connections = AtomicInteger()

    @Volatile var reply: (Asked) -> String = { "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi" }

    /** An answer that isn't text, such as a compressed one; used instead of [reply] when set. */
    @Volatile var replyBytes: ((Asked) -> ByteArray)? = null

    init {
        thread(isDaemon = true, name = "fake-origin") {
            while (true) {
                val socket =
                    try {
                        server.accept()
                    } catch (_: IOException) {
                        return@thread
                    }
                connections.incrementAndGet()
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                while (true) {
                    val asked = read(input) ?: return
                    received += asked
                    out.write(
                        replyBytes?.invoke(asked) ?: reply(asked).toByteArray(Charsets.ISO_8859_1)
                    )
                    out.flush()
                }
            }
        } catch (_: IOException) {
            // A client hanging up is how these connections normally end.
        }
    }

    private fun read(input: InputStream): Asked? {
        val first = line(input) ?: return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val next = line(input) ?: return null
            if (next.isEmpty()) break
            val colon = next.indexOf(':')
            headers[next.substring(0, colon).lowercase()] = next.substring(colon + 1).trim()
        }
        val parts = first.split(' ')
        return Asked(parts[0], parts[1], headers, body(input, headers))
    }

    private fun body(input: InputStream, headers: Map<String, String>): String {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val next = input.read(bytes, read, length - read)
            if (next == -1) break
            read += next
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun line(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) return null
            if (byte == '\n'.code) {
                return String(out.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r")
            }
            out.write(byte)
        }
    }

    override fun close() {
        try {
            server.close()
        } catch (_: IOException) {
            // Nothing to do about a listener that will not close.
        }
    }
}
