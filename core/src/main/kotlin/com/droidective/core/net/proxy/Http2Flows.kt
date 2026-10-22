package com.droidective.core.net.proxy

import com.droidective.core.net.h2.Http2Events
import com.droidective.core.net.h2.Http2Exchange
import com.droidective.core.net.http.HTTP_2
import com.droidective.core.net.http.Header
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import com.droidective.core.net.http.ResponseHead

/**
 * The streams of one HTTP/2 connection to [origin], on record as flows: listed, shown and exported
 * as any other exchange is. A stream's pseudo-headers become its method, path and status, and the
 * rest are its headers, as HTTP/1.1 would have carried them.
 *
 * Called with the reader's lock held, which is what keeps [open] whole.
 */
internal class Http2Flows(
    private val origin: Origin,
    private val record: FlowSink,
    private val ids: () -> Long,
    private val clock: () -> Long,
    private val onStopped: (String) -> Unit,
) : Http2Events {
    private val open = HashMap<Int, Flow>()

    override fun requested(exchange: Http2Exchange) {
        val fields = exchange.request
        val head =
            RequestHead(
                method = value(fields, METHOD).orEmpty(),
                target = value(fields, PATH).orEmpty(),
                version = HTTP_2,
                headers = plain(fields),
            )
        val flow = Flow(ids(), clock(), secure = true, origin.host, origin.port, head)
        open[exchange.stream] = flow
        record.started(answered(flow, exchange))
    }

    override fun answered(exchange: Http2Exchange) {
        val flow = open[exchange.stream] ?: return
        record.started(answered(flow, exchange))
    }

    override fun finished(exchange: Http2Exchange) {
        val flow = open.remove(exchange.stream) ?: return
        record.finished(
            answered(flow, exchange)
                .copy(
                    requestBody = exchange.requestBody,
                    finishedAt = clock(),
                    failure = exchange.reset ?: exchange.cut,
                )
        )
    }

    override fun stopped(why: String) = onStopped(why)

    /** [flow] with as much of the answer as has come, which is none while it is on its way. */
    private fun answered(flow: Flow, exchange: Http2Exchange): Flow {
        val fields = exchange.response ?: return flow
        val status = value(fields, STATUS)?.toIntOrNull() ?: return flow
        return flow.copy(
            response = ResponseHead(HTTP_2, status, "", plain(fields)),
            responseBody = exchange.responseBody,
            trailers = Headers(exchange.trailers),
        )
    }

    private fun value(fields: List<Header>, name: String): String? =
        fields.firstOrNull { it.name == name }?.value

    private fun plain(fields: List<Header>): Headers =
        Headers(fields.filterNot { it.name.startsWith(':') })

    private companion object {
        const val METHOD = ":method"
        const val PATH = ":path"
        const val STATUS = ":status"
    }
}
