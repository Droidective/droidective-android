package com.droidective.core.net.proxy

import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import com.droidective.core.net.http.ResponseHead
import com.droidective.core.store.JsonFileStore
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Where the proxy stops and waits: a request matching [url] (and [method], when named) is held
 * before it goes out, or its answer before it reaches the app, to be looked at and changed —
 * Charles's *Breakpoints*.
 *
 * @property url the URL it stops at, without the query; `*` stands for any run of characters.
 */
@Serializable
data class Breakpoint(
    val id: String,
    val url: String,
    val method: String? = null,
    val requests: Boolean = true,
    val responses: Boolean = false,
    val enabled: Boolean = true,
) {
    fun matches(method: String, requestUrl: String): Boolean {
        if (!enabled || !(requests || responses)) return false
        if (this.method != null && !this.method.equals(method, ignoreCase = true)) return false
        return UrlPattern.matches(url, requestUrl)
    }

    /** What is wrong with this breakpoint as it was typed, or null when it can be kept. */
    fun problem(): String? =
        when {
            !UrlPattern.isPattern(url) -> "The URL needs http:// or https:// and a host."
            method != null && !Headers.isToken(method) -> "\"$method\" isn't a method."
            !requests && !responses -> "Hold the request, the answer or both."
            else -> null
        }

    companion object {
        /** A breakpoint for requests like [flow]'s: its method, to its URL without the query. */
        fun of(flow: Flow, id: String): Breakpoint =
            Breakpoint(id, flow.url.substringBefore('?'), flow.request.method.uppercase())

        /** The first enabled breakpoint for [method] [url], in the order they were made. */
        fun find(points: List<Breakpoint>, method: String, url: String): Breakpoint? =
            points.firstOrNull {
                it.matches(method, url)
            }
    }
}

/** How a held exchange that didn't go on is recorded. */
object Holding {
    const val STOPPED = "stopped at a breakpoint"
    const val TOO_BIG = "too big to hold at a breakpoint"
}

/** A request held at a breakpoint: as it would go to the server, head and whole body. */
class HeldRequest(val flow: Flow, val head: RequestHead, val body: ByteArray)

/** An answer held at a breakpoint: as it would reach the app, head and whole body, decoded. */
class HeldResponse(val flow: Flow, val head: ResponseHead, val body: ByteArray)

/** What to do with a held request. */
sealed interface RequestVerdict {
    /** On its way as it was. */
    data object Go : RequestVerdict

    /** On its way, changed. */
    class Send(val head: RequestHead, val body: ByteArray) : RequestVerdict

    /** Not sent: the app is told it failed. */
    data object Stop : RequestVerdict
}

/** What to do with a held answer. */
sealed interface ResponseVerdict {
    data object Go : ResponseVerdict

    class Answer(val head: ResponseHead, val body: ByteArray) : ResponseVerdict

    data object Stop : ResponseVerdict
}

/**
 * Where the proxy hands what it holds, and waits for what to do with it. Both calls are made on the
 * connection's own thread, which waits for the answer; an implementation decides how long someone
 * gets to give one.
 */
interface Breaker {
    fun request(held: HeldRequest): RequestVerdict

    fun response(held: HeldResponse): ResponseVerdict

    companion object {
        /** Lets everything through, for a proxy nobody is watching. */
        val GO =
            object : Breaker {
                override fun request(held: HeldRequest) = RequestVerdict.Go

                override fun response(held: HeldResponse) = ResponseVerdict.Go
            }
    }
}

/** The breakpoints, kept in `traffic-breakpoints.json`, in the order they were made. */
class BreakpointRepository(root: File) {
    private val store =
        JsonFileStore(
            File(root, "traffic-breakpoints.json"),
            ListSerializer(Breakpoint.serializer()),
        ) {
            emptyList()
        }

    fun all(): List<Breakpoint> = store.load()

    fun save(points: List<Breakpoint>) = store.save(points)
}
