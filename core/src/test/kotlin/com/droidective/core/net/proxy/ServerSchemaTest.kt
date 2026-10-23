package com.droidective.core.net.proxy

import com.droidective.core.net.h2.H2Wire.frame
import com.droidective.core.net.h2.H2Wire.headers
import com.droidective.core.net.h2.Http2Frame
import com.droidective.core.net.http.HTTP_2
import com.droidective.core.net.http.Header
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Asking a server for its schema over TLS and HTTP/2, as the detail screen does. */
class ServerSchemaTest {
    @Test
    fun aServerAnsweringOnlyTheOlderVersionStillGivesItsSchema() =
        H2Origin(ProxyTls.H2).use { origin ->
            origin.answer = { stream ->
                if (path(origin) == V1) unimplemented(stream) else answered(stream, "symbol")
            }
            val found = assertIs<ServerSchema.Outcome.Found>(asker().ask(call(origin.port)))
            assertEquals(3, found.files)
            assertNotNull(found.schema.typed(ORDER, answer = true))
            assertEquals(listOf(V1, V1ALPHA), origin.asked.map { fields -> fields.value(":path") })
            val asked = origin.asked.last()
            assertEquals("localhost:${origin.port}", asked.value(":authority"))
            assertEquals("application/grpc", asked.value("content-type"))
            assertEquals("trailers", asked.value("te"))
            // The call's own credentials go; how its body was compressed does not.
            assertEquals("Bearer t0ken", asked.value("authorization"))
            assertNull(asked.value("grpc-accept-encoding"))
        }

    @Test
    fun aServerWithNoReflectionSaysSo() =
        H2Origin(ProxyTls.H2).use { origin ->
            origin.answer = ::unimplemented
            assertEquals(
                ServerSchema.Outcome.Refused(
                    "The server doesn't answer reflection, so its schema has to come from the " +
                        "app's .proto files."
                ),
                asker().ask(call(origin.port)),
            )
        }

    @Test
    fun aServerThatGuardsItsReflectionGivesItsReason() =
        H2Origin(ProxyTls.H2).use { origin ->
            origin.answer = { stream ->
                headers(
                    stream,
                    ":status" to "200",
                    "content-type" to "application/grpc",
                    "grpc-status" to "16",
                    "grpc-message" to "no%20token",
                )
            }
            assertEquals(
                ServerSchema.Outcome.Refused(
                    "The server didn't give it: it said UNAUTHENTICATED: no token."
                ),
                asker().ask(call(origin.port)),
            )
        }

    @Test
    fun somethingInFrontAnswering404IsNoReflection() =
        H2Origin(ProxyTls.H2).use { origin ->
            origin.answer = { stream -> headers(stream, ":status" to "404") }
            val refused = assertIs<ServerSchema.Outcome.Refused>(asker().ask(call(origin.port)))
            assertEquals(true, refused.why.startsWith("The server doesn't answer reflection"))
        }

    @Test
    fun whatIsNotACallToAMethodIsNotAsked() {
        assertEquals("google.firestore.v1.Firestore", ServerSchema.service(FIRESTORE))
        assertEquals("a.B", ServerSchema.service("/a.B/C?x=1"))
        assertNull(ServerSchema.service("/"))
        assertNull(ServerSchema.service("/only"))
        val refused = asker().ask(call(port = 1, path = "/"))
        assertEquals(
            ServerSchema.Outcome.Refused(
                "This isn't a gRPC call to a method, so there's nothing to ask."
            ),
            refused,
        )
    }

    @Test
    fun whatAServerGivesIsKeptUnderItsOwnName() {
        assertEquals("reflected-api.example.com.protoset", ServerSchema.fileName("api.example.com"))
        // Nothing in a host may lead out of the folder, or name another file.
        assertEquals("reflected-.._.._x.protoset", ServerSchema.fileName("../../x"))
        assertEquals("reflected-___1_.protoset", ServerSchema.fileName("[::1]"))
    }

    private fun asker() = ServerSchema(TestAuthority.upstream)

    private fun call(port: Int, path: String = ORDER) =
        Flow(
            id = 1,
            startedAt = 0,
            secure = true,
            host = "localhost",
            port = port,
            request =
                RequestHead(
                    "POST",
                    path,
                    HTTP_2,
                    Headers()
                        .add("content-type", "application/grpc")
                        .add("grpc-accept-encoding", "gzip")
                        .add("authorization", "Bearer t0ken"),
                ),
        )

    private fun path(origin: H2Origin): String? = origin.asked.last().value(":path")

    private fun unimplemented(stream: Int) =
        headers(
            stream,
            ":status" to "200",
            "content-type" to "application/grpc",
            "grpc-status" to "12",
            "grpc-message" to "Method not found!",
        )

    /** The recorded answer [name], as grpcio-reflection sent it. */
    private fun answered(stream: Int, name: String): ByteArray {
        val body = assertNotNull(javaClass.getResource("/grpc/reflection/$name.grpc")).readBytes()
        return headers(
            stream,
            ":status" to "200",
            "content-type" to "application/grpc",
            end = false,
        ) + frame(Http2Frame.DATA, 0, stream, body) + headers(stream, "grpc-status" to "0")
    }

    private fun List<Header>.value(name: String): String? = firstOrNull { it.name == name }?.value

    private companion object {
        const val V1 = "/grpc.reflection.v1.ServerReflection/ServerReflectionInfo"
        const val V1ALPHA = "/grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo"
        const val ORDER = "/droidective.reflected.Shop/Order"
        const val FIRESTORE = "/google.firestore.v1.Firestore/GetDocument"
    }
}
