package com.droidective.core.net.proxy

import com.droidective.core.net.grpc.Grpc
import com.droidective.core.net.grpc.GrpcReflection
import com.droidective.core.net.grpc.GrpcReflection.Answer
import com.droidective.core.net.grpc.GrpcReflection.Gathered
import com.droidective.core.net.grpc.GrpcStatus
import com.droidective.core.net.grpc.ProtoSchema
import com.droidective.core.net.h2.Http2Client
import com.droidective.core.net.http.Header
import com.droidective.core.net.http.Headers
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * A gRPC server's schema, asked of the server itself by reflection, for a call it answered. It goes
 * on a connection of its own with the call's own metadata, since a server that guards its
 * reflection guards it the way it guards its calls.
 */
class ServerSchema(private val upstream: UpstreamTls = UpstreamTls.SYSTEM) {
    sealed interface Outcome {
        /** [set] is a FileDescriptorSet of [files] files, to keep as a `.protoset` is kept. */
        class Found(val set: ByteArray, val schema: ProtoSchema, val files: Int) : Outcome

        data class Refused(val why: String) : Outcome
    }

    /** What the server that answered [call] says its schema is, or why it says nothing. */
    fun ask(call: Flow): Outcome {
        val service = service(call.request.path) ?: return Outcome.Refused(NOT_A_CALL)
        return try {
            openHttp2(upstream, Server(call.secure, Origin(call.host, call.port))).use { socket ->
                val client = Http2Client(socket.getInputStream(), socket.getOutputStream())
                gather(client, call, service)
            }
        } catch (e: SSLException) {
            Outcome.Refused(TlsFailure.upstream(e.message))
        } catch (e: IOException) {
            Outcome.Refused(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun gather(client: Http2Client, call: Flow, service: String): Outcome {
        for (path in GrpcReflection.PATHS) {
            var unserved = false
            val gathered =
                GrpcReflection.gather(service) { request ->
                    ask(client, call, path, request).also {
                        unserved = it is Answer.Error && it.status.code == UNIMPLEMENTED
                    }
                }
            if (unserved) continue
            return when (gathered) {
                is Gathered.Files ->
                    ProtoSchema.parse(gathered.set)?.let {
                        Outcome.Found(gathered.set, it, gathered.count)
                    } ?: Outcome.Refused(UNREADABLE)
                is Gathered.Refused ->
                    Outcome.Refused("The server didn't give it: ${gathered.why}.")
            }
        }
        return Outcome.Refused(NO_REFLECTION)
    }

    /** One reflection request as a call of its own; a call that failed is an error answer. */
    private fun ask(client: Http2Client, call: Flow, path: String, request: ByteArray): Answer? {
        val reply = client.send(fields(call, path), framed(request))
        val headers = Headers(reply.fields.filterNot { it.name.startsWith(':') })
        val status = reply.fields.firstOrNull { it.name == STATUS }?.value?.toIntOrNull()
        if (status != OK_HTTP) return Answer.Error(GrpcStatus(fromHttp(status), "HTTP $status"))
        val grpc = GrpcStatus.of(headers, Headers(reply.trailers))
        if (grpc != null && !grpc.ok) return Answer.Error(grpc)
        val message = Grpc.messages(reply.body.bytes, headers[GRPC_ENCODING]).firstOrNull()
        return message?.takeIf { it.problem == null }?.let { GrpcReflection.answer(it.bytes) }
    }

    /**
     * The call's own metadata — its credentials among it — less what framed its body, so the answer
     * comes back uncompressed.
     */
    private fun fields(call: Flow, path: String): List<Header> {
        val own =
            call.request.headers.without(
                *Headers.HOP_BY_HOP,
                Headers.HOST,
                Headers.CONTENT_LENGTH,
                CONTENT_TYPE,
                GRPC_ENCODING,
                GRPC_ACCEPT_ENCODING,
                GRPC_TIMEOUT,
            )
        return listOf(
            Header(":method", "POST"),
            Header(":scheme", call.scheme),
            Header(":authority", call.authority),
            Header(":path", path),
            Header(CONTENT_TYPE, GRPC),
            Header(TE, TRAILERS),
        ) + own.all.map { Header(it.name.lowercase(), it.value) }
    }

    companion object {
        /**
         * The file the schema [host] gave is kept as, beside the ones pushed: its own name, so
         * asking again replaces it, with nothing in it a path could make something of.
         */
        fun fileName(host: String): String {
            val safe = host.map {
                if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in ".-") it else '_'
            }
            return "reflected-${safe.joinToString("")}.protoset"
        }

        /** `package.Service` from `/package.Service/Method`. */
        fun service(path: String): String? {
            val parts = path.substringBefore('?').trim('/').split('/')
            return parts.takeIf { it.size == 2 && it.none(String::isEmpty) }?.first()
        }

        /** One message in gRPC's frame: uncompressed, its length, itself. */
        private fun framed(message: ByteArray): ByteArray {
            val length = message.size
            return byteArrayOf(
                0,
                (length ushr 24).toByte(),
                (length ushr 16).toByte(),
                (length ushr 8).toByte(),
                length.toByte(),
            ) + message
        }

        /** gRPC's own mapping, for an answer from something in front of the server. */
        private fun fromHttp(status: Int?): Int =
            when (status) {
                400 -> 13
                401 -> 16
                403 -> 7
                404 -> UNIMPLEMENTED
                429,
                502,
                503,
                504 -> 14
                else -> 2
            }

        private const val STATUS = ":status"
        private const val OK_HTTP = 200
        private const val UNIMPLEMENTED = 12
        private const val CONTENT_TYPE = "content-type"
        private const val GRPC = "application/grpc"
        private const val GRPC_ENCODING = "grpc-encoding"
        private const val GRPC_ACCEPT_ENCODING = "grpc-accept-encoding"
        private const val GRPC_TIMEOUT = "grpc-timeout"
        private const val TE = "te"
        private const val TRAILERS = "trailers"
        private const val NOT_A_CALL =
            "This isn't a gRPC call to a method, so there's nothing to ask."
        private const val NO_REFLECTION =
            "The server doesn't answer reflection, so its schema has to come from the app's .proto " +
                "files."
        private const val UNREADABLE = "What the server sent doesn't read as a schema."
    }
}
