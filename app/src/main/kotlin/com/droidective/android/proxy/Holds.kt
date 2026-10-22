package com.droidective.android.proxy

import android.content.Context
import com.droidective.core.net.http.RequestHead
import com.droidective.core.net.http.ResponseHead
import com.droidective.core.net.proxy.Breaker
import com.droidective.core.net.proxy.Breakpoint
import com.droidective.core.net.proxy.BreakpointRepository
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
    private val points = MutableStateFlow<List<Breakpoint>>(emptyList())
    @Volatile private var loaded = false
    private val waiting = MutableStateFlow<List<Hold>>(emptyList())
    @Volatile private var context: Context? = null

    val breakpoints: StateFlow<List<Breakpoint>> = points.asStateFlow()

    /** What is held right now, oldest first. */
    val held: StateFlow<List<Hold>> = waiting.asStateFlow()

    /**
     * Where the notification goes from, set by the proxy when it starts. A notice still up from a
     * process Android ended names holds that went with it, so it comes down.
     */
    internal fun attach(context: Context) {
        this.context = context.applicationContext
        notifyHeld()
    }

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            points.value = BreakpointRepository(context.filesDir).all()
            loaded = true
        }
    }

    fun save(context: Context, point: Breakpoint) =
        change(context) { all ->
            if (all.any { it.id == point.id }) all.map { if (it.id == point.id) point else it }
            else all + point
        }

    fun remove(context: Context, id: String) =
        change(context) { all -> all.filterNot { it.id == id } }

    private fun change(context: Context, edit: (List<Breakpoint>) -> List<Breakpoint>) {
        load(context)
        val changed = synchronized(this) { edit(points.value).also { points.value = it } }
        BreakpointRepository(context.filesDir).save(changed)
        TrafficProxy.applyBreakpoints(changed)
    }

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
        notifyHeld()
        return try {
            verdict.get(WAIT_MINUTES, TimeUnit.MINUTES)
        } catch (_: TimeoutException) {
            otherwise
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            otherwise
        } finally {
            waiting.update { it - hold }
            notifyHeld()
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

    private fun notifyHeld() {
        context?.let { ProxyNotices.held(it, waiting.value) }
    }

    /** How long a held exchange waits for someone before it goes on as it was. */
    const val WAIT_MINUTES = 5L
}
