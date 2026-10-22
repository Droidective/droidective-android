package com.droidective.android.proxy

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import com.droidective.core.net.http.ResponseHead
import com.droidective.core.net.proxy.Breakpoint
import com.droidective.core.net.proxy.BreakpointRepository
import com.droidective.core.net.proxy.Flow
import com.droidective.core.net.proxy.HeldRequest
import com.droidective.core.net.proxy.HeldResponse
import com.droidective.core.net.proxy.RequestVerdict
import com.droidective.core.net.proxy.ResponseVerdict
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A held exchange waits on its connection's thread until someone decides, and the decision is what
 * that thread goes on with.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class HoldsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val notifications
        get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before fun attach() = Holds.attach(context)

    @After
    fun nothingLeftHeld() {
        Holds.releaseAll()
        assertTrue(Holds.held.value.isEmpty())
    }

    @Test
    fun aRequestWaitsUntilItIsLetGo() {
        val waiting = holdRequest(flow(1))
        val hold = heldNow(1)
        assertIs<Hold.Request>(hold)
        assertTrue(!waiting.isDone)

        Holds.go(1)

        assertEquals(RequestVerdict.Go, waiting.get(WAIT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun aChangedRequestGoesOnAsItWasChanged() {
        val waiting = holdRequest(flow(2))
        heldNow(2)
        val changed = RequestHead("PUT", "/orders/2", "HTTP/1.1", Headers().add("X-Edited", "yes"))

        Holds.send(2, changed, "{}".toByteArray())

        val verdict = assertIs<RequestVerdict.Send>(waiting.get(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(changed, verdict.head)
        assertEquals("{}", verdict.body.decodeToString())
    }

    @Test
    fun aStoppedRequestIsNotSent() {
        val waiting = holdRequest(flow(3))
        heldNow(3)

        Holds.stop(3)

        assertEquals(RequestVerdict.Stop, waiting.get(WAIT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun anAnswerCanBeChangedBeforeTheAppSeesIt() {
        val answer = ResponseHead("HTTP/1.1", 200, "OK", Headers())
        val waiting = CompletableFuture.supplyAsync {
            Holds.response(HeldResponse(flow(4), answer, "[]".toByteArray()))
        }
        assertIs<Hold.Response>(heldNow(4))
        // An answer can't be sent as a request: the wrong decision changes nothing.
        Holds.send(4, RequestHead("GET", "/", "HTTP/1.1", Headers()), ByteArray(0))
        assertTrue(!waiting.isDone)

        Holds.answer(4, answer.copy(status = 500), "oops".toByteArray())

        val verdict = assertIs<ResponseVerdict.Answer>(waiting.get(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(500, verdict.head.status)
    }

    @Test
    fun theNoticeSaysWhatIsHeld() {
        val answer = ResponseHead("HTTP/1.1", 200, "OK", Headers())
        val holds =
            listOf(
                Hold.Response(HeldResponse(flow(5), answer, ByteArray(0)), CompletableFuture()),
                Hold.Request(
                    HeldRequest(flow(6), flow(6).request, ByteArray(0)),
                    CompletableFuture(),
                ),
            )

        ProxyNotices.held(context, holds)

        val posted = notifications.allNotifications.single()
        assertEquals("Held at a breakpoint", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(
            "The answer to POST https://api.example.com/orders/5 and 1 more",
            posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertEquals("Let it go", posted.actions.single().title.toString())
    }

    @Test
    fun theNoticeGoesWhenNothingIsHeld() {
        val waiting = holdRequest(flow(7))
        heldNow(7)

        Holds.go(7)
        waiting.get(WAIT_SECONDS, TimeUnit.SECONDS)

        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test
    fun stoppingTheProxyLetsEverythingGo() {
        val first = holdRequest(flow(8))
        val second = holdRequest(flow(9))
        heldNow(8)
        heldNow(9)

        Holds.releaseAll()

        assertEquals(RequestVerdict.Go, first.get(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(RequestVerdict.Go, second.get(WAIT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun theBreakpointsAreKeptInOrder() {
        val orders = Breakpoint("b1", "https://api.example.com/orders/*")
        val menu = Breakpoint("b2", "https://api.example.com/menu", "GET", responses = true)

        Holds.save(context, orders)
        Holds.save(context, menu)
        Holds.save(context, orders.copy(enabled = false))

        val kept = listOf(orders.copy(enabled = false), menu)
        assertEquals(kept, Holds.breakpoints.value)
        assertEquals(kept, BreakpointRepository(context.filesDir).all())

        Holds.remove(context, "b1")

        assertEquals(listOf(menu), BreakpointRepository(context.filesDir).all())
        Holds.remove(context, "b2")
    }

    private fun flow(id: Long) =
        Flow(
            id,
            startedAt = 0,
            secure = true,
            host = "api.example.com",
            port = 443,
            request = RequestHead("POST", "/orders/$id", "HTTP/1.1", Headers()),
        )

    /** A connection's thread, held at a breakpoint until it hears what to do. */
    private fun holdRequest(flow: Flow): CompletableFuture<RequestVerdict> =
        CompletableFuture.supplyAsync {
            Holds.request(HeldRequest(flow, flow.request, ByteArray(0)))
        }

    /** The hold for [id], as soon as its thread has handed it over. */
    private fun heldNow(id: Long): Hold = runBlocking {
        withTimeout(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) {
            Holds.held.mapNotNull { held -> held.firstOrNull { it.flow.id == id } }.first()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 5L
    }
}
