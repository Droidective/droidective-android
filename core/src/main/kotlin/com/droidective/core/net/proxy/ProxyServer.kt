package com.droidective.core.net.proxy

import com.droidective.core.net.http.BodyFraming
import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.HttpBody
import com.droidective.core.net.http.HttpReader
import com.droidective.core.net.http.RequestHead
import com.droidective.core.net.http.ResponseHead
import com.droidective.core.net.tls.ProxyCertificates
import com.droidective.core.net.ws.FrameReader
import com.droidective.core.net.ws.Negotiated
import com.droidective.core.net.ws.SocketMessage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * An HTTP proxy that writes down what goes through it.
 *
 * A device pointed at this sends every request here first. A plain request is forwarded and
 * recorded. A `CONNECT` either opens — the proxy answers as the host, using a certificate minted by
 * [certificates], and reads the exchange inside — or is passed through as bytes when [rules] says
 * that host is left alone.
 *
 * What it will not do is change what the app sees. Bodies are forwarded whole however much of them
 * is kept, the server's own certificate is checked before anything is believed, and a host that is
 * passed through is passed through untouched.
 */
class ProxyServer(
    private val certificates: ProxyCertificates,
    private val sink: FlowSink = FlowSink.NONE,
    private val upstream: UpstreamTls = UpstreamTls.SYSTEM,
    private val owners: FlowOwners = FlowOwners.UNKNOWN,
    /** What apps have refused the certificate for, kept by whoever outlives one run of this. */
    private val refusals: Refusals = Refusals(),
    /**
     * Where flow ids come from. A store that outlives one run of this needs one counter across
     * them: its flows are keyed by id, and a second run counting from 1 again replaced the first's.
     */
    private val ids: AtomicLong = AtomicLong(),
    /** Who decides what happens to an exchange held at a breakpoint. */
    private val breaker: Breaker = Breaker.GO,
    /** Where a mock that answers from a file finds it. */
    private val files: MockFiles = MockFiles.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Which hosts are opened; may be changed while the proxy is running. */
    @Volatile var rules: ProxyRules = ProxyRules()

    /** Answers given here instead of by the server; may be changed while the proxy is running. */
    @Volatile var mocks: List<Mock> = emptyList()

    /** How slow a network to play; read again for each request, so it applies at once. */
    @Volatile var throttle: Throttle = Throttle.NONE

    /** Where exchanges are held for someone to see; may be changed while the proxy runs. */
    @Volatile var breakpoints: List<Breakpoint> = emptyList()

    /** Which requests go to another server; may be changed while the proxy runs. */
    @Volatile var remaps: List<Remap> = emptyList()

    private val contexts = ConcurrentHashMap<String, SSLContext>()
    private val open = ConcurrentHashMap.newKeySet<Socket>()
    private var listener: ServerSocket? = null
    private var scope: CoroutineScope? = null

    /** The port being listened on, or -1 when stopped. */
    val port: Int
        get() = listener?.localPort ?: -1

    /** The address being listened on, which is always the phone's own. */
    val address: InetAddress?
        get() = listener?.inetAddress

    /**
     * Starts listening, on loopback only. Port 0 takes whatever is free, which is what the tests
     * use.
     *
     * Loopback is not a detail. A proxy listening on every interface is an open proxy for everyone
     * else on that Wi-Fi: their traffic through this phone, and this phone's address against
     * whatever they do with it. The phone points at 127.0.0.1 and nothing off the device can reach
     * it.
     *
     * It is IPv4's loopback by name, not `InetAddress.getLoopbackAddress()`: on Android that is
     * IPv6's `::1`, and a socket bound there never hears a connection to 127.0.0.1.
     */
    fun start(port: Int = 0): Int {
        check(listener == null) { "the proxy is already listening" }
        val server = ServerSocket(port, BACKLOG, LOOPBACK)
        val running = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        listener = server
        scope = running
        running.launch {
            while (isActive) {
                val client =
                    try {
                        server.accept()
                    } catch (_: IOException) {
                        break // The listener was closed: that is how stopping looks from here.
                    }
                open += client
                // Asked the moment it arrives, while the app is still waiting on the other end: an
                // app that refuses the certificate is gone a few milliseconds later, and with it
                // the only row that said which app it was.
                val owner = ownerOf(client.port)
                launch {
                    try {
                        serve(client, owner)
                    } finally {
                        open -= client
                        client.closeQuietly()
                    }
                }
            }
        }
        return server.localPort
    }

    /** Stops listening and drops every connection, including the ones parked in a tunnel. */
    fun stop() {
        listener?.closeQuietly()
        listener = null
        open.forEach { it.closeQuietly() }
        open.clear()
        scope?.cancel()
        scope = null
    }

    private fun serve(client: Socket, owner: CompletableFuture<Int?>) {
        try {
            client.soTimeout = IDLE_MILLIS
            val source = BufferedInputStream(client.getInputStream())
            val out = BufferedOutputStream(client.getOutputStream())
            val head = HttpReader(source).readRequest() ?: return
            // Once per connection: the port is the only thing tying it to an app, and it stops
            // meaning anything the moment the connection closes.
            val record = Owned(sink, owner)
            if (head.method.equals(CONNECT, ignoreCase = true)) {
                accept(head, Client(client, source, out), record)
            } else {
                exchanges(source, out, secure = false, fixed = null, first = head, record = record)
            }
        } catch (_: IOException) {
            // A client that goes away part-way through is ordinary and not worth a record.
        }
    }

    /** A `CONNECT`: answer as the host and read what follows, or pass the bytes through. */
    private fun accept(head: RequestHead, client: Client, record: Owned) {
        val (socket, source, out) = client
        val origin = Origin.authority(head.target) ?: return out.writeQuietly(BAD_REQUEST)
        out.write(ESTABLISHED)
        out.flush()
        val rules = rules
        // The answer has to be in before going on only when it decides what happens: some apps
        // read and others not, or a host some app has refused before. Every other connection
        // carries on and learns its app in the background.
        val decides = rules.apps.isNotEmpty() || (rules.stepAside && refusals.mentions(origin.host))
        val uid = if (decides) record.awaitUid(APP_WAIT_MILLIS) else record.uid()
        val passing =
            rules.passing(origin.host, uid)
                ?: Passing.Refused.takeIf { rules.stepAside && refusals.has(uid, origin.host) }
        if (passing != null) return tunnel(origin, head, client, record, passing)
        val context =
            contexts.getOrPut(origin.host) {
                val leaf = certificates.certificateFor(origin.host, clock())
                ProxyTls.serverContext(leaf, certificates.caCertificate)
            }
        val tls = ProxyTls.accept(context, socket, origin)
        try {
            tls.startHandshake()
        } catch (e: IOException) {
            // The app would not have the certificate: either the authority is not installed, or
            // the app pins its own. It may say so with an alert or simply hang up, which arrives
            // as a reset rather than as TLS. Saying so beats a connection that merely stops, and
            // the next connection steps aside so the app can get on with its day.
            if (rules.stepAside) refusals.note(record.awaitUid(APP_WAIT_MILLIS), origin.host)
            record.finished(
                begin(head, origin, secure = true)
                    .copy(
                        finishedAt = clock(),
                        failure = TlsFailure.refused(e.message),
                    )
            )
            return
        }
        exchanges(
            BufferedInputStream(tls.inputStream),
            BufferedOutputStream(tls.outputStream),
            secure = true,
            fixed = origin,
            first = null,
            record = record,
        )
    }

    /** Requests on one connection, one after another, for as long as both ends allow. */
    private fun exchanges(
        source: InputStream,
        out: OutputStream,
        secure: Boolean,
        fixed: Origin?,
        first: RequestHead?,
        record: FlowSink,
    ) {
        val reader = HttpReader(source)
        val line = Line(source, out, secure, record)
        var head = first ?: reader.readRequest()
        try {
            while (head != null) {
                val origin = fixed ?: Origin.of(head, secure) ?: break
                if (!request(head, origin, line)) break
                head = reader.readRequest()
            }
        } finally {
            line.close()
        }
    }

    /**
     * One request and its answer, wherever it goes: answered by a mock, sent where a remap says,
     * held at a breakpoint, or straight on. Returns whether the connection can carry another.
     */
    private fun request(head: RequestHead, origin: Origin, line: Line): Boolean {
        val source = line.source
        val out = line.out
        val record = line.record
        val flow = begin(head, origin, line.secure)
        // On record at once, before the server is reached: a dial can take its full fifteen
        // seconds, and a request nobody can see yet looks like one never made.
        record.started(flow)
        Mock.find(mocks, head.method, flow.url)?.let {
            return mocked(flow, head, it, source, out, record)
        }
        // Recorded as the app asked, with where it went.
        val remapped = Remap.route(remaps, flow.url)
        val routed = if (remapped != null) flow.copy(sentTo = remapped.url) else flow
        val point = Breakpoint.find(breakpoints, head.method, flow.url)
        val decided = point?.let { decide(routed, head, it, source, out, record) ?: return false }
        // Someone may have taken minutes, and a server closes a connection left idle.
        if (point?.requests == true) line.close()
        val upstream =
            try {
                line.to(remapped ?: Server(line.secure, origin))
            } catch (e: IOException) {
                failed(decided?.flow ?: routed, out, e, record)
                return false
            }
        val ends = Ends(source, out, upstream, remapped)
        return if (point != null && decided != null) held(decided, head, point, ends, record)
        else exchange(routed, head, ends, record)
    }

    /**
     * A request answered here, by [mock], without the server: the body the app sent is read and
     * kept, the answer written, and the flow says which mock gave it. Returns whether the
     * connection can carry another.
     */
    private fun mocked(
        flow: Flow,
        head: RequestHead,
        mock: Mock,
        source: InputStream,
        out: OutputStream,
        record: FlowSink,
    ): Boolean {
        val requestBody = HttpBody.relay(BodyFraming.of(head), source, null)
        val (response, body) =
            try {
                mock.file?.let { mock.response(files.read(it)) } ?: mock.response()
            } catch (e: IOException) {
                val why = "the mock's file: ${e.message}"
                record.finished(
                    flow.copy(
                        requestBody = requestBody,
                        finishedAt = clock(),
                        failure = why,
                        mocked = mock.id,
                    )
                )
                out.writeQuietly(badGateway("Droidective: $why."))
                return false
            }
        // A mock plays the network as slow as the rest, or it would answer faster than anything.
        val slow = throttle
        delay(slow)
        val paced = Paced.of(out, slow.downBytesPerSecond)
        paced.write(response.bytes())
        paced.write(body)
        paced.flush()
        val kept = CapturedBody(body, body.size.toLong(), truncated = false)
        record.finished(
            flow.copy(
                requestBody = requestBody,
                response = response,
                responseBody = kept,
                finishedAt = clock(),
                mocked = mock.id,
            )
        )
        return !closes(head.headers)
    }

    /** One request and its answer. Returns whether the connection can carry another. */
    private fun exchange(flow: Flow, head: RequestHead, ends: Ends, record: FlowSink): Boolean {
        val (source, client, upstream) = ends
        val slow = throttle
        delay(slow)
        val toServer = Paced.of(upstream.sink, slow.upBytesPerSecond)
        val out = Paced.of(client, slow.downBytesPerSecond)
        val forward = head.with(target = head.path, headers = forwardable(head.headers))
        val requestBody =
            try {
                toServer.write((ends.remapped?.hosting(forward) ?: forward).bytes())
                HttpBody.relay(BodyFraming.of(head), source, toServer).also { toServer.flush() }
            } catch (e: IOException) {
                failed(flow, out, e, record)
                return false
            }
        val asked = flow.copy(requestBody = requestBody)
        record.started(asked)

        val response =
            try {
                upstream.reader.readResponse() ?: throw IOException("the server answered nothing")
            } catch (e: IOException) {
                failed(asked, out, e, record)
                return false
            }
        return answer(asked, head, response, Ends(source, out, upstream), record)
    }

    /** An answer on its way to the app as it comes. Returns whether the connection goes on. */
    private fun answer(
        asked: Flow,
        head: RequestHead,
        response: ResponseHead,
        ends: Ends,
        record: FlowSink,
    ): Boolean {
        val (_, out, upstream) = ends
        val toApp = ToApp(out)
        val framing = BodyFraming.of(response, head.method)
        val body =
            try {
                toApp.write(response.bytes())
                if (response.status == SWITCHING) return upgrade(asked, response, ends, record)
                // The head goes as soon as it is here, before a body that may be slow to start.
                toApp.flush()
                HttpBody.relay(framing, upstream.source, toApp).also { toApp.flush() }
            } catch (e: IOException) {
                // On record as far as it got, not left waiting for an answer that has been and
                // gone.
                val why = if (e is AppGone) APP_GONE else e.reason()
                record.finished(
                    asked.copy(response = response, finishedAt = clock(), failure = why)
                )
                return false
            }
        record.finished(asked.copy(response = response, responseBody = body, finishedAt = clock()))
        return framing != BodyFraming.UntilClose &&
            !closes(head.headers) &&
            !closes(response.headers)
    }

    /**
     * A 101: from here the connection is whatever the two ends agreed on — a WebSocket, usually —
     * and no longer a sequence of requests, so the rest is carried across as bytes.
     */
    private fun upgrade(
        flow: Flow,
        response: ResponseHead,
        ends: Ends,
        record: FlowSink,
    ): Boolean {
        val (source, out, upstream) = ends
        out.flush()
        val open = flow.copy(response = response, tunnelled = true)
        // On record as it is, a socket still open, for however long it stays that way.
        record.started(open)
        val (fromApp, fromServer) = readers(flow.id, response, record)
        bothWays(source, out, upstream.source, upstream.sink, fromApp, fromServer)
        record.finished(open.copy(finishedAt = clock()))
        return false
    }

    /**
     * What reads each direction of an upgraded connection, when it became a WebSocket: messages go
     * on record as flow [flowId]'s. Anything else upgraded to stays bytes, as before.
     */
    private fun readers(flowId: Long, response: ResponseHead, record: FlowSink): Pair<Tap?, Tap?> {
        if (!response.headers[UPGRADE].equals(WEBSOCKET, ignoreCase = true)) return null to null
        val negotiated = Negotiated.of(response.headers.values(EXTENSIONS))
        val why =
            negotiated.unknown?.let { "it uses $it, which isn't read here" }
                ?: "what it sent stopped making sense as WebSocket frames"
        fun reader(fromApp: Boolean): Tap {
            val frames = FrameReader(fromApp, negotiated, clock) { record.message(flowId, it) }
            var told = false
            // Once, when it gives up: at once for an extension it can't read, or part-way.
            val tell = {
                if (frames.broken && !told) {
                    told = true
                    record.unread(flowId, fromApp, why)
                }
            }
            tell()
            return { bytes, count ->
                frames.feed(bytes, 0, count)
                tell()
            }
        }
        return reader(fromApp = true) to reader(fromApp = false)
    }

    /** A host left alone: the bytes cross untouched and only that it happened is recorded. */
    private fun tunnel(
        origin: Origin,
        head: RequestHead,
        client: Client,
        record: FlowSink,
        passing: Passing,
    ) {
        val (socket, source, out) = client
        val flow = begin(head, origin, secure = true).copy(tunnelled = true, passing = passing)
        record.started(flow)
        var upstream: Socket? = null
        try {
            upstream = Dial.connect(origin.host, origin.port, CONNECT_MILLIS)
            // A tunnel may be idle for a long time and still be alive; only a close ends it.
            socket.soTimeout = 0
            upstream.soTimeout = 0
            // Passed through is still on this network: as slow as everything else.
            val slow = throttle
            delay(slow)
            bothWays(
                source,
                Paced.of(out, slow.downBytesPerSecond),
                upstream.getInputStream(),
                Paced.of(upstream.getOutputStream(), slow.upBytesPerSecond),
            )
            record.finished(flow.copy(finishedAt = clock()))
        } catch (e: IOException) {
            record.finished(flow.copy(finishedAt = clock(), failure = e.reason()))
        } finally {
            upstream?.closeQuietly()
        }
    }

    /** A request held at a breakpoint, decided on: what goes to the server, and what came in. */
    private class Decided(
        val flow: Flow,
        val forward: RequestHead,
        val head: RequestHead,
        val body: ByteArray,
    )

    /**
     * A request matching [point], read whole and, when [point] holds requests, handed to the
     * [breaker] before the server is reached. An answer that will be held is asked for only in
     * codings the proxy can undo, so whoever it is held for can read it. Null when it won't go on,
     * the app told why.
     */
    private fun decide(
        flow: Flow,
        head: RequestHead,
        point: Breakpoint,
        source: InputStream,
        client: OutputStream,
        record: FlowSink,
    ): Decided? {
        val read = HttpBody.relay(BodyFraming.of(head), source, null, HOLD_LIMIT)
        val asked = flow.copy(requestBody = read, paused = point.id)
        if (read.truncated) {
            refuse(asked, client, Holding.TOO_BIG, record)
            return null
        }
        val passed = forwardable(head.headers)
        val forward =
            head.with(
                target = head.path,
                headers = if (point.responses) readable(passed) else passed,
            )
        if (!point.requests) return Decided(asked, forward, forward, read.bytes)
        return when (val verdict = breaker.request(HeldRequest(asked, forward, read.bytes))) {
            RequestVerdict.Go -> Decided(asked, forward, forward, read.bytes)
            is RequestVerdict.Send -> Decided(asked, forward, verdict.head, verdict.body)
            RequestVerdict.Stop -> {
                refuse(asked, client, Holding.STOPPED, record)
                null
            }
        }
    }

    /**
     * An exchange held at [point], its request [decided]: sent in one piece with its length stated,
     * and its answer handed to the [breaker] before it reaches the app when [point] holds answers.
     * Returns whether the connection can carry another.
     */
    private fun held(
        decided: Decided,
        head: RequestHead,
        point: Breakpoint,
        ends: Ends,
        record: FlowSink,
    ): Boolean {
        val (source, client, upstream) = ends
        val sentHead = decided.head
        val sentBody = decided.body
        val slow = throttle
        delay(slow)
        val toServer = Paced.of(upstream.sink, slow.upBytesPerSecond)
        val sending = whole(sentHead, sentBody.size)
        toServer.write((ends.remapped?.hosting(sending) ?: sending).bytes())
        toServer.write(sentBody)
        toServer.flush()
        val sent =
            decided.flow.copy(
                request = sentHead,
                requestBody = CapturedBody(sentBody, sentBody.size.toLong(), truncated = false),
                edited = sentHead !== decided.forward,
            )
        record.started(sent)
        val response =
            try {
                upstream.reader.readResponse() ?: throw IOException("the server answered nothing")
            } catch (e: IOException) {
                failed(sent, client, e, record)
                return false
            }
        val out = Paced.of(client, slow.downBytesPerSecond)
        if (!point.responses || response.status == SWITCHING) {
            return answer(sent, head, response, Ends(source, out, upstream), record)
        }
        return heldAnswer(sent, head, response, Ends(source, out, upstream), record)
    }

    /** An answer held before it reaches the app, decoded for whoever looks at it. */
    private fun heldAnswer(
        flow: Flow,
        head: RequestHead,
        response: ResponseHead,
        ends: Ends,
        record: FlowSink,
    ): Boolean {
        val (_, out, upstream) = ends
        val framing = BodyFraming.of(response, head.method)
        val read = HttpBody.relay(framing, upstream.source, null, HOLD_LIMIT)
        if (read.truncated) return refuse(flow, out, Holding.TOO_BIG, record)
        val coding = response.headers.contentEncoding
        val decoded = BodyView.decode(coding, read.bytes)
        val shown = decoded ?: read.bytes
        val (answered, body, edited) =
            when (
                val verdict =
                    breaker.response(
                        HeldResponse(
                            flow,
                            response,
                            shown,
                            compressed = coding.takeIf { decoded == null },
                        )
                    )
            ) {
                ResponseVerdict.Go -> Triple(whole(response, read.bytes.size), read.bytes, false)
                // Written as the person wrote it: plain, whatever the server compressed it as.
                is ResponseVerdict.Answer ->
                    Triple(plain(verdict.head, verdict.body.size), verdict.body, true)
                ResponseVerdict.Stop -> return refuse(flow, out, Holding.STOPPED, record)
            }
        val given =
            flow.copy(
                response = answered,
                responseBody = CapturedBody(body, body.size.toLong(), truncated = false),
                edited = flow.edited || edited,
            )
        try {
            out.write(answered.bytes())
            out.write(body)
            out.flush()
        } catch (_: IOException) {
            // An app that waited long enough gives up, and stopping the proxy hangs up on it.
            record.finished(given.copy(finishedAt = clock(), failure = Holding.HUNG_UP))
            return false
        }
        record.finished(given.copy(finishedAt = clock()))
        return framing != BodyFraming.UntilClose &&
            !closes(head.headers) &&
            !closes(answered.headers)
    }

    /** Ends a held exchange that won't go on, telling the app it failed and why. */
    private fun refuse(flow: Flow, out: OutputStream, why: String, record: FlowSink): Boolean {
        record.finished(flow.copy(finishedAt = clock(), failure = why))
        out.writeQuietly(badGateway("Droidective: $why."))
        return false
    }

    /** The round trip a slower network would add, before a request goes on its way. */
    private fun delay(slow: Throttle) {
        if (slow.latencyMillis > 0) Thread.sleep(slow.latencyMillis)
    }

    private fun begin(head: RequestHead, origin: Origin, secure: Boolean) =
        Flow(ids.incrementAndGet(), clock(), secure, origin.host, origin.port, head)

    private fun failed(flow: Flow, out: OutputStream, error: Throwable, record: FlowSink) {
        // A TLS failure here is the real server's, which is usually its certificate.
        val why = if (error is SSLException) TlsFailure.upstream(error.message) else error.reason()
        record.finished(flow.copy(finishedAt = clock(), failure = why))
        out.writeQuietly(badGateway(error))
    }

    /**
     * Which app holds the connection from [port], asked in the background. On a phone the answer is
     * a round trip through the shell, and a request that waited for it would be slower than the
     * same request without the proxy in the way.
     */
    private fun ownerOf(port: Int): CompletableFuture<Int?> =
        CompletableFuture.supplyAsync({ owners.uidOf(port) }, Dispatchers.IO.asExecutor())
            .exceptionally { null }

    private fun dial(server: Server): Socket {
        val (secure, origin) = server
        // Sent to itself, by an app or by a remap, a request would ask the proxy again, for ever.
        if (origin.port == port && loopback(origin.host)) throw IOException(ITSELF)
        return if (secure) {
            upstream.connect(origin, CONNECT_MILLIS)
        } else {
            Dial.connect(origin.host, origin.port, CONNECT_MILLIS).apply { soTimeout = IDLE_MILLIS }
        }
    }

    /**
     * One client connection, as its requests see it: the app's end, how it came in, and the
     * server's end, kept while the requests keep going to the same server.
     */
    private inner class Line(
        val source: InputStream,
        val out: OutputStream,
        val secure: Boolean,
        val record: FlowSink,
    ) {
        private var upstream: Upstream? = null

        /** A connection to [server]: the one already open, when it goes there again. */
        fun to(server: Server): Upstream =
            upstream?.takeIf { it.server == server }
                ?: Upstream(server, dial(server)).also {
                    close()
                    upstream = it
                }

        /** Lets the server's end go, so the next request dials afresh. */
        fun close() {
            upstream?.close()
            upstream = null
        }
    }

    /**
     * The app's end of an answer, saying so when a write fails there: the same failure from the
     * server's end means something else.
     */
    private class ToApp(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = guarded { out.write(b) }

        override fun write(b: ByteArray, off: Int, len: Int) = guarded { out.write(b, off, len) }

        override fun flush() = guarded { out.flush() }

        private inline fun guarded(write: () -> Unit) {
            try {
                write()
            } catch (e: IOException) {
                throw AppGone(e)
            }
        }
    }

    private class AppGone(cause: IOException) : IOException(APP_GONE, cause)

    /** The app's end of a connection. */
    private data class Client(val socket: Socket, val source: InputStream, val out: OutputStream)

    /**
     * The three streams one exchange moves bytes between, and the server a remap sent it to, whose
     * name its `Host` has to carry.
     */
    private data class Ends(
        val source: InputStream,
        val out: OutputStream,
        val upstream: Upstream,
        val remapped: Server? = null,
    )

    /** The server end of one client connection. */
    private class Upstream(val server: Server, private val socket: Socket) {
        val source = BufferedInputStream(socket.getInputStream())
        val sink = BufferedOutputStream(socket.getOutputStream())
        val reader = HttpReader(source)

        fun close() = socket.closeQuietly()
    }

    private companion object {
        const val CONNECT = "CONNECT"
        const val APP_GONE = "the app hung up before its answer was all there"
        const val UPGRADE = "Upgrade"
        const val WEBSOCKET = "websocket"
        const val EXTENSIONS = "Sec-WebSocket-Extensions"
        const val ITSELF = "that is this proxy, which would only ask itself again"
        const val ACCEPT_ENCODING = "Accept-Encoding"
        const val SWITCHING = 101
        const val BACKLOG = 50
        const val IDLE_MILLIS = 120_000
        const val CONNECT_MILLIS = 15_000

        /** As much of a body as is held whole at a breakpoint; past it, the exchange fails. */
        const val HOLD_LIMIT = HttpBody.MAX_CAPTURE

        /** [head] carrying a body of [length] in one piece, however it was framed before. */
        fun whole(head: RequestHead, length: Int): RequestHead {
            val kept = head.headers.without(Headers.CONTENT_LENGTH, Headers.TRANSFER_ENCODING)
            val stated =
                if (length > 0 || head.headers.has(Headers.CONTENT_LENGTH)) {
                    kept.add(Headers.CONTENT_LENGTH, length.toString())
                } else {
                    kept
                }
            return head.with(headers = stated)
        }

        fun whole(head: ResponseHead, length: Int): ResponseHead =
            head.copy(
                headers =
                    head.headers
                        .without(Headers.CONTENT_LENGTH, Headers.TRANSFER_ENCODING)
                        .add(Headers.CONTENT_LENGTH, length.toString())
            )

        /** Whether [host] is this phone, written as its own name for itself. */
        fun loopback(host: String): Boolean =
            host.equals("localhost", ignoreCase = true) || host == "::1" || host.startsWith("127.")

        /** [headers] asking the server only for codings the proxy can undo. */
        fun readable(headers: Headers): Headers {
            val kept = Repeater.readable(headers[ACCEPT_ENCODING]) ?: return headers
            return headers.without(ACCEPT_ENCODING).add(ACCEPT_ENCODING, kept)
        }

        /** An answer written out as text: no compression claimed, whatever it had before. */
        fun plain(head: ResponseHead, length: Int): ResponseHead =
            whole(head.copy(headers = head.headers.without(Headers.CONTENT_ENCODING)), length)

        /**
         * How long a connection waits to learn its app when that decides whether it is read: a
         * shell round trip, with room to spare. Past it the app counts as unknown.
         */
        const val APP_WAIT_MILLIS = 2_000L

        /** What the phone's proxy setting names. */
        val LOOPBACK: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))

        val ESTABLISHED = wire("HTTP/1.1 200 Connection Established\r\n\r\n")
        val BAD_REQUEST = wire("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")

        /**
         * The headers that name this hop rather than the message. They are answered here and must
         * not travel on. `Transfer-Encoding` stays: the relay passes a chunked body along in the
         * shape it arrived, and the far side has to be told that.
         */
        val PER_HOP =
            arrayOf(
                "Connection",
                "Proxy-Connection",
                "Keep-Alive",
                "Proxy-Authenticate",
                "Proxy-Authorization",
                "TE",
            )

        fun forwardable(headers: Headers): Headers =
            // An upgrade is negotiated with the very headers that otherwise stop here, so a
            // request asking for one keeps them and the two ends settle it between themselves.
            if (headers.has("Upgrade")) headers.without("Proxy-Connection", "Proxy-Authorization")
            else headers.without(*PER_HOP, "Upgrade")

        fun closes(headers: Headers): Boolean =
            headers.values("Connection").any { value ->
                value.split(',').any { it.trim().equals("close", ignoreCase = true) }
            }

        fun badGateway(error: Throwable): ByteArray =
            badGateway("Droidective could not reach the server: ${error.reason()}")

        /** A 502 saying [body]: the proxy answering for a server it couldn't, or wouldn't, ask. */
        fun badGateway(body: String): ByteArray =
            wire(
                "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
            )

        fun wire(text: String) = text.toByteArray(StandardCharsets.ISO_8859_1)
    }
}

/**
 * One connection's records, with the app behind it filled in as soon as that is known.
 *
 * Nobody waits for the answer. An exchange that finishes before it arrives is recorded without it,
 * then again once it does — a flow's last record is the one that counts.
 */
private class Owned(private val sink: FlowSink, private val owner: CompletableFuture<Int?>) :
    FlowSink {
    /** The app, if it is known yet. */
    fun uid(): Int? = owner.getNow(null)

    /** The app, waiting up to [millis] for it. */
    fun awaitUid(millis: Long): Int? =
        try {
            owner.get(millis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: InterruptedException) {
            // Stopping: the answer no longer matters, and the interrupt is not this call's to eat.
            Thread.currentThread().interrupt()
            null
        }

    override fun started(flow: Flow) = sink.started(stamped(flow))

    override fun finished(flow: Flow) {
        val now = stamped(flow)
        sink.finished(now)
        // Not "unless it is done by now": an answer arriving since it was stamped would be lost.
        // A done one runs this at once.
        if (now.uid == null) {
            owner.thenAccept { uid -> if (uid != null) sink.finished(now.copy(uid = uid)) }
        }
    }

    override fun message(flowId: Long, message: SocketMessage) = sink.message(flowId, message)

    override fun unread(flowId: Long, fromApp: Boolean, why: String) =
        sink.unread(flowId, fromApp, why)

    private fun stamped(flow: Flow): Flow =
        if (flow.uid != null) flow else flow.copy(uid = owner.getNow(null))
}

/**
 * Shown each chunk of bytes as it crosses, after it has gone on: the buffer, and how much of it.
 */
private typealias Tap = (ByteArray, Int) -> Unit

/**
 * Copies both ways at once and returns when either side is done, showing what crosses from the app
 * to [up] and what crosses from the server to [down]. Whichever end hangs up first is hung up on at
 * the other: otherwise a server is never told the app has gone, and the copy waiting on it — with
 * no timeout, on a tunnel or a socket — waits for ever.
 */
private fun bothWays(
    clientIn: InputStream,
    clientOut: OutputStream,
    serverIn: InputStream,
    serverOut: OutputStream,
    up: Tap? = null,
    down: Tap? = null,
) {
    val back =
        thread(name = "droidective-proxy-tunnel") {
            copyQuietly(serverIn, clientOut, down)
            clientIn.closeQuietly()
        }
    copyQuietly(clientIn, serverOut, up)
    serverIn.closeQuietly()
    back.join()
}

private fun copyQuietly(source: InputStream, sink: OutputStream, tap: Tap?) {
    var watching = tap
    try {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = source.read(buffer)
            if (read == -1) break
            sink.write(buffer, 0, read)
            sink.flush()
            try {
                watching?.invoke(buffer, read)
            } catch (_: RuntimeException) {
                // Reading along is for the record, and the record must never cost the connection.
                watching = null
            }
        }
    } catch (_: IOException) {
        // Either end closing is how a tunnel ends; there is nothing to report about it.
    }
}

private fun OutputStream.writeQuietly(bytes: ByteArray) {
    try {
        write(bytes)
        flush()
    } catch (_: IOException) {
        // The client is already gone, which is why we were telling it something went wrong.
    }
}

private fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // Closing is the one thing there is no recovering from and nothing to say about.
    }
}

private fun ServerSocket.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // As above.
    }
}

private fun Throwable.reason(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
