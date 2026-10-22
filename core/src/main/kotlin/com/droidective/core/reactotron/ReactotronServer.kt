package com.droidective.core.reactotron

import com.droidective.core.net.http.HttpReader
import com.droidective.core.net.http.RequestHead
import com.droidective.core.net.ws.FrameReader
import com.droidective.core.net.ws.Negotiated
import com.droidective.core.net.ws.SocketMessage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a [ReactotronServer] hears, from whichever thread heard it. */
interface ReactotronSink {
    /** Connection [connection] said who it is, and is client [clientId] from now on. */
    fun connected(connection: Int, clientId: String, intro: ReactotronEvent.ClientIntro)

    fun command(connection: Int, command: ReactotronCommand)

    /** [connection] is gone, [why] in words; null when its own app's reload took its place. */
    fun disconnected(connection: Int, why: String?)
}

/**
 * The server a React Native app's Reactotron client connects to, on the phone itself.
 *
 * The client dials `localhost:9090`, which on a phone with no `adb reverse` is the phone: listening
 * there, Droidective gets the app's logs, API calls and state with no computer in the way. It
 * speaks what `reactotron-core-server` speaks — WebSocket, a JSON command per text frame — and
 * finishes each client's handshake the same way, so an app can't tell the two apart.
 *
 * It listens on `127.0.0.1` only, and refuses an upgrade from a web page: a browser's request
 * carries its page's `Origin`, and React Native's names localhost.
 */
class ReactotronServer(
    private val sink: ReactotronSink,
    private val port: Int = DEFAULT_PORT,
    private val pingMillis: Long = PING_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var listener: ServerSocket? = null
    private val connections = ConcurrentHashMap<Int, Connection>()
    private val ids = AtomicInteger()

    @Volatile private var running = false

    /** Starts listening, returning the port; a port already taken throws a `BindException`. */
    fun start(): Int {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(LOOPBACK, port), BACKLOG)
        } catch (e: IOException) {
            server.close()
            throw e
        }
        listener = server
        running = true
        thread(isDaemon = true, name = "droidective-reactotron") { accept(server) }
        thread(isDaemon = true, name = "droidective-reactotron-ping") { pings() }
        return server.localPort
    }

    fun stop() {
        running = false
        listener?.closeQuietly()
        listener = null
        connections.values.forEach { it.close() }
        connections.clear()
    }

    /** Sends one command, `{type, payload}`, to [connection]; nothing when it has gone. */
    fun send(connection: Int, type: String, payload: JsonElement) {
        connections[connection]?.send(command(type, payload))
    }

    private fun accept(server: ServerSocket) {
        while (running) {
            val socket =
                try {
                    server.accept()
                } catch (_: IOException) {
                    return
                }
            if (connections.size >= MAX_CONNECTIONS) {
                socket.closeQuietly()
                continue
            }
            val connection = Connection(ids.incrementAndGet(), socket)
            connections[connection.id] = connection
            thread(isDaemon = true, name = "droidective-reactotron-${connection.id}") {
                connection.serve()
            }
        }
    }

    private fun pings() {
        val ping = WebSocketFrames.ping()
        while (running) {
            try {
                Thread.sleep(pingMillis)
            } catch (_: InterruptedException) {
                return
            }
            connections.values.filter { it.upgraded }.forEach { it.send(ping) }
        }
    }

    /**
     * On `client.intro`: a client with no id is given one, any other connection with the same id is
     * an earlier copy of the app — a reload connects before the old socket dies — and goes, and the
     * empty subscription list goes out, which is what tells the client it is connected.
     */
    private fun introduced(connection: Connection, intro: ReactotronCommand) {
        var clientId = intro.payload["clientId"].text
        if (clientId.isNullOrEmpty()) {
            clientId = UUID.randomUUID().toString()
            connection.send(command(SET_CLIENT_ID, JsonPrimitive(clientId)))
        }
        connections.values
            .filter { it !== connection && it.clientId == clientId }
            .forEach { it.replaced() }
        connection.clientId = clientId
        val paths = JsonObject(mapOf("paths" to JsonArray(emptyList())))
        connection.send(command(SUBSCRIBE, paths))
        sink.connected(
            connection.id,
            clientId,
            ReactotronEvent.of(intro) as ReactotronEvent.ClientIntro,
        )
    }

    private inner class Connection(val id: Int, private val socket: Socket) {
        private val out = BufferedOutputStream(socket.getOutputStream())

        @Volatile var clientId: String? = null

        /** Whether it is a WebSocket yet: nothing but the 101 may be written before. */
        @Volatile
        var upgraded = false
            private set

        /** Set when another connection took its place; its going needs no telling. */
        @Volatile private var quiet = false

        /** Why it is ending, in words, once that is known. */
        @Volatile private var ending = DROPPED

        fun serve() {
            try {
                val source = BufferedInputStream(socket.getInputStream())
                if (upgrade(source)) frames(source)
            } catch (_: IOException) {
                // A socket that goes away part-way is how most connections end.
            } finally {
                close()
                val known = connections.remove(id) != null
                if (upgraded && (known || quiet)) sink.disconnected(id, if (quiet) null else ending)
            }
        }

        private fun upgrade(source: InputStream): Boolean {
            val head = HttpReader(source).readRequest() ?: return false
            val key = head.headers[KEY]
            val upgrade =
                head.method == "GET" &&
                    head.headers[UPGRADE].equals(WEBSOCKET, ignoreCase = true) &&
                    head.headers[VERSION] == "13" &&
                    key != null
            if (!upgrade) {
                send(REFUSED.toByteArray(Charsets.ISO_8859_1))
                return false
            }
            if (!local(head)) {
                send(FORBIDDEN.toByteArray(Charsets.ISO_8859_1))
                return false
            }
            send(switching(checkNotNull(key)).toByteArray(Charsets.ISO_8859_1))
            upgraded = true
            return true
        }

        /** Reads frames until the socket ends. */
        private fun frames(source: InputStream) {
            val reader =
                FrameReader(fromApp = true, Negotiated.PLAIN, clock, MAX_MESSAGE) { message ->
                    message(message)?.let {
                        ending = it
                        close()
                    }
                }
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = source.read(buffer)
                if (read == -1) return
                reader.feed(buffer, 0, read)
                if (reader.broken) {
                    ending = UNREADABLE
                    return
                }
            }
        }

        /** Handles one message; returns why the connection is ending, when it is. */
        private fun message(message: SocketMessage): String? {
            when (message.kind) {
                SocketMessage.Kind.Text -> text(message)
                SocketMessage.Kind.Ping -> send(WebSocketFrames.pong(message.data))
                SocketMessage.Kind.Close -> {
                    send(WebSocketFrames.close(message.data))
                    return if (message.closeCode == GOING_AWAY) WENT_AWAY else CLOSED
                }
                SocketMessage.Kind.Binary,
                SocketMessage.Kind.Pong -> Unit
            }
            return null
        }

        private fun text(message: SocketMessage) {
            val command =
                ReactotronCommand.parse(message.text)
                    ?: ReactotronCommand(
                        UNDECODABLE,
                        JsonPrimitive(
                            if (message.truncated) "a frame over ${MAX_MESSAGE / MEGABYTE} MB"
                            else message.text.take(PREVIEW)
                        ),
                    )
            if (command.type == INTRO) introduced(this, command) else sink.command(id, command)
        }

        @Synchronized
        fun send(bytes: ByteArray) {
            try {
                out.write(bytes)
                out.flush()
            } catch (_: IOException) {
                // A connection going away is found out by its reader, which says so.
            }
        }

        fun replaced() {
            quiet = true
            connections.remove(id)
            close()
        }

        fun close() = socket.closeQuietly()
    }

    companion object {
        /** Where Reactotron clients connect unless told otherwise. */
        const val DEFAULT_PORT = 9090

        /** reactotron-core-server's keepalive, so a half-dead socket is found out. */
        const val PING_MILLIS = 30_000L

        /** The most one command can be; past it the frame is said to be too large. */
        const val MAX_MESSAGE = 16 * 1024 * 1024

        private const val MEGABYTE = 1024 * 1024
        private const val MAX_CONNECTIONS = 32
        private const val BACKLOG = 8
        private const val BUFFER = 16 * 1024
        private const val PREVIEW = 300
        private const val GOING_AWAY = 1001
        private const val INTRO = "client.intro"
        private const val SET_CLIENT_ID = "setClientId"
        private const val SUBSCRIBE = "state.values.subscribe"
        private const val UNDECODABLE = "(undecodable)"
        private const val KEY = "Sec-WebSocket-Key"
        private const val VERSION = "Sec-WebSocket-Version"
        private const val UPGRADE = "Upgrade"
        private const val WEBSOCKET = "websocket"
        private const val ORIGIN = "Origin"
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val DROPPED = "the connection dropped"
        private const val CLOSED = "the app closed the connection"
        private const val WENT_AWAY = "the app went away: reloaded, or quit"
        private const val UNREADABLE = "it sent something that isn't WebSocket"
        private const val REFUSED = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n"
        private const val FORBIDDEN = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"

        private val LOOPBACK: InetAddress =
            InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")

        /** RFC 6455's answer to a key: SHA-1 of it and the protocol's own GUID, in base64. */
        internal fun accept(key: String): String =
            Base64.getEncoder()
                .encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key.trim() + GUID).toByteArray())
                )

        private fun switching(key: String) =
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${accept(key)}\r\n\r\n"

        /**
         * No `Origin`, as most clients send, or one that names this phone, as React Native's does.
         */
        internal fun local(head: RequestHead): Boolean {
            val origin = head.headers[ORIGIN] ?: return true
            val host =
                try {
                    URI(origin.trim()).host
                } catch (_: URISyntaxException) {
                    return false
                } ?: return false
            return host.lowercase() in LOCAL_HOSTS
        }

        private fun command(type: String, payload: JsonElement): ByteArray =
            WebSocketFrames.text(
                JsonObject(mapOf("type" to JsonPrimitive(type), "payload" to payload)).toString()
            )
    }
}

/** Frames as a server writes them: whole, and never masked. */
internal object WebSocketFrames {
    fun text(text: String): ByteArray = frame(TEXT, text.toByteArray(Charsets.UTF_8))

    fun ping(): ByteArray = frame(PING, ByteArray(0))

    fun pong(payload: ByteArray): ByteArray = frame(PONG, payload)

    /** A close answering one, with its status code echoed back as RFC 6455 asks. */
    fun close(received: ByteArray): ByteArray =
        frame(CLOSE, received.copyOf(minOf(2, received.size)))

    fun frame(opcode: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + MAX_HEADER)
        out.write(FIN or opcode)
        when {
            payload.size < LENGTH_16 -> out.write(payload.size)
            payload.size <= 0xffff -> {
                out.write(LENGTH_16)
                out.write(payload.size ushr 8)
                out.write(payload.size)
            }
            else -> {
                out.write(LENGTH_64)
                for (shift in 56 downTo 0 step 8) out.write(
                    (payload.size.toLong() ushr shift).toInt()
                )
            }
        }
        out.write(payload)
        return out.toByteArray()
    }

    private const val FIN = 0x80
    private const val TEXT = 1
    private const val CLOSE = 8
    private const val PING = 9
    private const val PONG = 10
    private const val LENGTH_16 = 126
    private const val LENGTH_64 = 127
    private const val MAX_HEADER = 10
}

private fun java.io.Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // Closing is the one thing there is no recovering from and nothing to say about.
    }
}
