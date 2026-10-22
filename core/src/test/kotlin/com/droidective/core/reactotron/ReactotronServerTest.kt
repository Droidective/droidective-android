package com.droidective.core.reactotron

import com.droidective.core.net.http.HttpReader
import java.net.BindException
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The server against the JDK's own WebSocket client, which checks the handshake itself. */
class ReactotronServerTest {
    private val heard = Heard()
    private var server: ReactotronServer? = null

    @AfterTest
    fun stop() {
        server?.stop()
    }

    private fun start(pingMillis: Long = 60_000): Int {
        val running = ReactotronServer(heard, port = 0, pingMillis = pingMillis)
        server = running
        return running.start()
    }

    @Test
    fun anAppThatSaysWhoItIsIsGivenAnIdAndToldItIsConnected() {
        val app = App(start())
        app.send("""{"type":"client.intro","payload":{"name":"StreamLab","platform":"android"}}""")
        val id = app.next()
        assertEquals("setClientId", id.type())
        val given = id["payload"]!!.jsonPrimitive.content
        val subscribe = app.next()
        assertEquals("state.values.subscribe", subscribe.type())
        assertEquals("""{"paths":[]}""", subscribe["payload"].toString())
        val (_, clientId, intro) = heard.connected.poll(5, TimeUnit.SECONDS)!!
        assertEquals(given, clientId)
        assertEquals("StreamLab", intro.name)
        assertEquals("android", intro.platform)
    }

    @Test
    fun anAppWithAnIdKeepsIt() {
        val app = App(start())
        app.send("""{"type":"client.intro","payload":{"name":"A","clientId":"abc-123"}}""")
        // No id is handed out: the first thing back is the subscription.
        assertEquals("state.values.subscribe", app.next().type())
        assertEquals("abc-123", heard.connected.poll(5, TimeUnit.SECONDS)!!.second)
    }

    @Test
    fun commandsArriveInTheOrderTheyWereSent() {
        val app = App(start())
        app.send("""{"type":"client.intro","payload":{"name":"A","clientId":"c"}}""")
        for (index in 1..50) {
            app.send("""{"type":"log","payload":{"level":"debug","message":"line $index"}}""")
        }
        val messages = List(50) { (heard.commands.poll(5, TimeUnit.SECONDS)!!.second) }
        assertEquals((1..50).map { "line $it" }, messages.map { it.payload["message"].text })
    }

    @Test
    fun aFrameLargerThanOneReadIsStillOneCommand() {
        val app = App(start())
        val big = "x".repeat(300_000)
        app.send("""{"type":"log","payload":{"message":"$big"}}""")
        val (_, command) = heard.commands.poll(5, TimeUnit.SECONDS)!!
        assertEquals(big.length, command.payload["message"].text?.length)
    }

    @Test
    fun whatIsNotJsonIsSaidToBeUndecodable() {
        val app = App(start())
        app.send("not json at all")
        val (_, command) = heard.commands.poll(5, TimeUnit.SECONDS)!!
        assertEquals("(undecodable)", command.type)
        assertEquals("not json at all", command.payload.text)
    }

    @Test
    fun aReloadTakesTheEarlierConnectionsPlaceQuietly() {
        val port = start()
        val first = App(port)
        first.send("""{"type":"client.intro","payload":{"name":"A","clientId":"same"}}""")
        val (firstConnection) = heard.connected.poll(5, TimeUnit.SECONDS)!!
        val second = App(port)
        second.send("""{"type":"client.intro","payload":{"name":"A","clientId":"same"}}""")
        heard.connected.poll(5, TimeUnit.SECONDS)!!
        val (gone, why) = heard.disconnected.poll(5, TimeUnit.SECONDS)!!
        assertEquals(firstConnection, gone)
        assertNull(why)
        assertTrue(first.closed.await())
    }

    @Test
    fun anAppGoingAwaySaysSo() {
        val app = App(start())
        app.send("""{"type":"client.intro","payload":{"name":"A","clientId":"c"}}""")
        heard.connected.poll(5, TimeUnit.SECONDS)!!
        app.socket.sendClose(1001, "reload").join()
        val (_, why) = heard.disconnected.poll(5, TimeUnit.SECONDS)!!
        assertEquals("the app went away: reloaded, or quit", why)
    }

    @Test
    fun aPingIsAnswered() {
        val app = App(start())
        app.socket.sendPing(ByteBuffer.wrap("are you there".toByteArray())).join()
        assertEquals("are you there", app.pongs.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun clientsArePingedSoAHalfDeadSocketIsFoundOut() {
        val app = App(start(pingMillis = 100))
        assertNotNull(app.pings.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun aWebPageIsTurnedAway() {
        val port = start()
        assertEquals(403, upgrade(port, "https://ads.example"))
        // A connection never upgraded is nobody's, and its going is no news.
        Thread.sleep(200)
        assertTrue(heard.disconnected.isEmpty())
        assertEquals(101, upgrade(port, "http://localhost:$port"))
        assertEquals(101, upgrade(port, null))
    }

    @Test
    fun aPortAlreadyTakenFailsToStart() {
        val port = start()
        val second = ReactotronServer(heard, port = port)
        assertFailsWith<BindException> { second.start() }
    }

    /** An upgrade request sent by hand, with [origin] when there is one: the status it gets. */
    private fun upgrade(port: Int, origin: String?): Int =
        Socket("127.0.0.1", port).use { socket ->
            val key = java.util.Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
            val request = buildString {
                append("GET / HTTP/1.1\r\nHost: localhost:$port\r\nUpgrade: websocket\r\n")
                append("Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Key: $key\r\n")
                origin?.let { append("Origin: $it\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()
            assertNotNull(HttpReader(socket.getInputStream()).readResponse()).status
        }

    /** An app's Reactotron client, as far as the WebSocket goes. */
    private class App(port: Int) : WebSocket.Listener {
        val texts = LinkedBlockingQueue<String>()
        val pings = LinkedBlockingQueue<String>()
        val pongs = LinkedBlockingQueue<String>()
        val closed = java.util.concurrent.CompletableFuture<Boolean>()
        private val partial = StringBuilder()

        val socket: WebSocket =
            HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI("ws://127.0.0.1:$port"), this)
                .get(5, TimeUnit.SECONDS)

        fun send(text: String) {
            socket.sendText(text, true).get(5, TimeUnit.SECONDS)
        }

        fun next(): JsonObject =
            Json.parseToJsonElement(assertNotNull(texts.poll(5, TimeUnit.SECONDS))).jsonObject

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*>? {
            partial.append(data)
            if (last) {
                texts += partial.toString()
                partial.clear()
            }
            webSocket.request(1)
            return null
        }

        override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
            pings += Charsets.UTF_8.decode(message).toString()
            webSocket.request(1)
            return null
        }

        override fun onPong(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
            pongs += Charsets.UTF_8.decode(message).toString()
            webSocket.request(1)
            return null
        }

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String,
        ): CompletionStage<*>? {
            closed.complete(true)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            closed.complete(true)
        }
    }

    private class Heard : ReactotronSink {
        val connected = LinkedBlockingQueue<Triple<Int, String, ReactotronEvent.ClientIntro>>()
        val commands = LinkedBlockingQueue<Pair<Int, ReactotronCommand>>()
        val disconnected = LinkedBlockingQueue<Pair<Int, String?>>()

        override fun connected(
            connection: Int,
            clientId: String,
            intro: ReactotronEvent.ClientIntro,
        ) {
            connected += Triple(connection, clientId, intro)
        }

        override fun command(connection: Int, command: ReactotronCommand) {
            commands += connection to command
        }

        override fun disconnected(connection: Int, why: String?) {
            disconnected += connection to why
        }
    }

    private fun JsonObject.type() = this["type"]!!.jsonPrimitive.content

    private fun java.util.concurrent.CompletableFuture<Boolean>.await() = get(5, TimeUnit.SECONDS)
}
