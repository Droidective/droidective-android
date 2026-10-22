package com.droidective.android.proxy

import android.content.Context
import com.droidective.core.net.http.RequestHead
import com.droidective.core.net.http.ResponseHead
import com.droidective.core.net.proxy.Breaker
import com.droidective.core.net.proxy.Breakpoint
import com.droidective.core.net.proxy.Flow
import com.droidective.core.net.proxy.HeldRequest
import com.droidective.core.net.proxy.HeldResponse
import com.droidective.core.net.proxy.RequestVerdict
import com.droidective.core.net.proxy.ResponseVerdict
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One exchange held at a breakpoint, waiting for someone to say what happens to it. */
sealed interface Hold {
    val flow: Flow

    class Request(val held: HeldRequest, internal val verdict: CompletableFuture<RequestVerdict>) :
        Hold {
        override val flow: Flow
            get() = held.flow
    }

    class Response(
        val held: HeldResponse,
        internal val verdict: CompletableFuture<ResponseVerdict>,
    ) : Hold {
        override val flow: Flow
            get() = held.flow
    }
}

/**
 * The breakpoints, and what is held at them: the proxy's [Breaker], answered by a person.
 *
 * A held exchange waits on its connection's thread until someone lets it go, changes it or stops it
 * — or for [WAIT_MINUTES], after which it goes on as it was, so a phone left alone doesn't end up
 * with an app waiting forever.
 */
object Holds : Breaker {
    private val points =
        KeptList(
            "traffic-breakpoints.json",
            Breakpoint.serializer(),
            Breakpoint::id,
            TrafficProxy::applyBreakpoints,
        )
    private val waiting = MutableStateFlow<List<Hold>>(emptyList())

    val breakpoints: StateFlow<List<Breakpoint>> = points.items

    /** What is held right now, oldest first. */
    val held: StateFlow<List<Hold>> = waiting.asStateFlow()

    fun load(context: Context) = points.load(context)

    fun save(context: Context, point: Breakpoint) = points.save(context, point)

    fun remove(context: Context, id: String) = points.remove(context, id)

    override fun request(held: HeldRequest): RequestVerdict {
        val hold = Hold.Request(held, CompletableFuture())
        return wait(hold, hold.verdict, RequestVerdict.Go)
    }

    override fun response(held: HeldResponse): ResponseVerdict {
        val hold = Hold.Response(held, CompletableFuture())
        return wait(hold, hold.verdict, ResponseVerdict.Go)
    }

    private fun <T : Any> wait(hold: Hold, verdict: CompletableFuture<T>, otherwise: T): T {
        waiting.update { it + hold }
        return try {
            verdict.get(WAIT_MINUTES, TimeUnit.MINUTES)
        } catch (_: TimeoutException) {
            otherwise
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            otherwise
        } finally {
            waiting.update { it - hold }
        }
    }

    fun go(id: Long) {
        when (val hold = find(id)) {
            is Hold.Request -> hold.verdict.complete(RequestVerdict.Go)
            is Hold.Response -> hold.verdict.complete(ResponseVerdict.Go)
            null -> Unit
        }
    }

    fun stop(id: Long) {
        when (val hold = find(id)) {
            is Hold.Request -> hold.verdict.complete(RequestVerdict.Stop)
            is Hold.Response -> hold.verdict.complete(ResponseVerdict.Stop)
            null -> Unit
        }
    }

    fun send(id: Long, head: RequestHead, body: ByteArray) {
        (find(id) as? Hold.Request)?.verdict?.complete(RequestVerdict.Send(head, body))
    }

    fun answer(id: Long, head: ResponseHead, body: ByteArray) {
        (find(id) as? Hold.Response)?.verdict?.complete(ResponseVerdict.Answer(head, body))
    }

    /** Lets everything go: the proxy is stopping, and nobody is left to decide. */
    fun releaseAll() = waiting.value.forEach { go(it.flow.id) }

    private fun find(id: Long): Hold? = waiting.value.firstOrNull { it.flow.id == id }

    /** How long a held exchange waits for someone before it goes on as it was. */
    const val WAIT_MINUTES = 5L
}
