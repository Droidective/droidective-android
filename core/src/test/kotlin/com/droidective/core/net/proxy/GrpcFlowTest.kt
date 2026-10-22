package com.droidective.core.net.proxy

import com.droidective.core.net.grpc.Grpc
import com.droidective.core.net.grpc.ProtoSchema
import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.HTTP_2
import com.droidective.core.net.http.Headers
import com.droidective.core.net.http.RequestHead
import com.droidective.core.net.http.ResponseHead
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A gRPC call as the proxy shows it: named by its method, judged by its own status. */
class GrpcFlowTest {
    @Test
    fun aCallThatFailedIsNotASuccessForBeingA200() {
        assertEquals(FlowOutcome.ClientError, FlowLabels.outcome(call(status = "3")))
        assertEquals(FlowOutcome.ServerError, FlowLabels.outcome(call(status = "14")))
        assertEquals(FlowOutcome.Success, FlowLabels.outcome(call(status = "0")))
        // Still waiting on its trailers, it is what its HTTP status says so far.
        assertEquals(FlowOutcome.Success, FlowLabels.outcome(call(status = null)))
        assertEquals("200", FlowLabels.status(call(status = "3")))
    }

    @Test
    fun theStatusIsReadForGrpcAndNothingElse() {
        // A JSON API that happens to send a grpc-status header is still judged by its HTTP status.
        val call = call(status = "3")
        val json =
            call.copy(
                request = call.request.copy(headers = Headers().add(CONTENT_TYPE, JSON)),
                response = ResponseHead(HTTP_2, 200, "", Headers().add(CONTENT_TYPE, JSON)),
            )
        assertNull(FlowLabels.grpc(json))
        assertEquals(FlowOutcome.Success, FlowLabels.outcome(json))
        assertEquals("INVALID_ARGUMENT: bad", FlowLabels.grpc(call).toString())
    }

    @Test
    fun aCallIsNamedByItsServiceAndMethod() {
        assertEquals("Firestore/GetDocument", FlowLabels.grpcMethod(call(status = "0")))
        assertNull(FlowLabels.grpcMethod(flow(path = "/google.firestore.v1.Firestore/Get")))
    }

    @Test
    fun aBodyIsShownMessageByMessage() {
        // 1: 150 and 2: "testing", then an empty message.
        val message = hex("089601120774657374696e67")
        val bytes = framed(message) + framed(ByteArray(0))
        val shape =
            BodyView.of(
                Headers().add(CONTENT_TYPE, GRPC),
                CapturedBody(bytes, bytes.size.toLong(), false),
            )
        val messages = assertIs<BodyShape.Grpc>(shape).messages
        assertEquals("1: 150\n2: \"testing\"\n", messages[0].fields)
        assertEquals(message.size, messages[0].wireLength)
        assertEquals("", messages[1].fields)
        assertTrue(shape.text.startsWith("# Message 1\n1: 150\n"))
    }

    @Test
    fun withTheAppsSchemaABodyIsReadAsItsType() {
        val schema = assertNotNull(ProtoSchema.parse(resource("check.protoset")))
        val typed = assertNotNull(schema.typed("/droidective.check.Check/Echo", answer = true))
        // Inner { id: 9, label: "in" }
        val bytes = framed(hex("08091202696e"))
        val shape =
            BodyView.of(
                Headers().add(CONTENT_TYPE, GRPC),
                CapturedBody(bytes, bytes.size.toLong(), false),
                typed,
            )
        val message = assertIs<BodyShape.Grpc>(shape).messages.single()
        assertEquals("id: 9\nlabel: \"in\"\n", message.fields)
        assertEquals("droidective.check.Inner", message.type)
        // The request is the other type, and a method the schema hasn't is no type at all.
        assertEquals("droidective.check.Everything", schema.typed(ECHO, answer = false)?.type)
        assertNull(schema.typed("/droidective.check.Check/Gone", answer = false))
    }

    @Test
    fun aBodyTheSchemaCanNotReadFallsBackToReadingWithoutIt() {
        val schema = assertNotNull(ProtoSchema.parse(resource("check.protoset")))
        val bytes = framed(hex("089601"))
        val shape =
            BodyView.of(
                Headers().add(CONTENT_TYPE, GRPC),
                CapturedBody(bytes, bytes.size.toLong(), false),
                ProtoSchema.Typed(schema, "droidective.check.NotThere"),
            )
        val message = assertIs<BodyShape.Grpc>(shape).messages.single()
        assertEquals("1: 150\n", message.fields)
        assertNull(message.type)
    }

    @Test
    fun theStatusDetailsReadWithTheSchemaWhenItHasThem() {
        val schema = assertNotNull(ProtoSchema.parse(resource("status.protoset")))
        // Status { code: 7, message: "no" }, base64'd as gRPC sends it.
        val value = Base64.getEncoder().encodeToString(hex("080712026e6f"))
        assertEquals(
            "code: 7\nmessage: \"no\"\n",
            Grpc.binaryHeader("grpc-status-details-bin", value, schema),
        )
        assertEquals("1: 7\n2: \"no\"\n", Grpc.binaryHeader("grpc-status-details-bin", value))
    }

    @Test
    fun aMessageThatIsNotProtobufIsShownAsTextOrSaidToBeNeither() {
        val text = "{\"json\":true}".toByteArray()
        val binary = byteArrayOf(0xff.toByte(), 0xfe.toByte())
        val bytes = framed(text) + framed(binary)
        val shape =
            BodyView.of(
                Headers().add(CONTENT_TYPE, "application/grpc+json"),
                CapturedBody(bytes, bytes.size.toLong(), false),
            )
        val messages = assertIs<BodyShape.Grpc>(shape).messages
        assertEquals("{\"json\":true}", messages[0].text)
        assertEquals("neither protobuf nor text", messages[1].problem)
    }

    @Test
    fun aCallIsNotSentAgainOrEdited() {
        // A repeat goes out as HTTP/1.1, which a gRPC server turns away.
        val refused = assertIs<Repeat.Refused>(Repeater.prepare(call(status = "0")))
        assertTrue("HTTP/2" in refused.reason, refused.reason)
        assertNull(RequestDraft.of(call(status = "0")))
    }

    @Test
    fun anHttp2FlowIsNotMockedSinceNoMockCouldReachIt() {
        val call = call(status = "0")
        assertNull(Mock.of(call, "id"))
        assertEquals(FlowLabels.HTTP2_UNTOUCHED, Mock.whyNot(call))
    }

    private fun call(status: String?): Flow {
        val trailers = if (status == null) Headers() else Headers().add("grpc-status", status)
        return Flow(
            id = 3,
            startedAt = 1_000,
            secure = true,
            host = "firestore.googleapis.com",
            port = 443,
            request =
                RequestHead(
                    "POST",
                    "/google.firestore.v1.Firestore/GetDocument",
                    HTTP_2,
                    Headers().add(CONTENT_TYPE, GRPC),
                ),
            requestBody = CapturedBody(framed(ByteArray(0)), 5, false),
            response = ResponseHead(HTTP_2, 200, "", Headers().add(CONTENT_TYPE, GRPC)),
            trailers = if (status == null) trailers else trailers.add("grpc-message", "bad"),
        )
    }

    private fun resource(name: String): ByteArray =
        assertNotNull(javaClass.getResource("/grpc/$name"), name).readBytes()

    private fun framed(bytes: ByteArray): ByteArray {
        val length = bytes.size
        return byteArrayOf(0, 0, 0, (length ushr 8).toByte(), length.toByte()) + bytes
    }

    private fun hex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val CONTENT_TYPE = "content-type"
        const val GRPC = "application/grpc"
        const val JSON = "application/json"
        const val ECHO = "/droidective.check.Check/Echo"
    }
}
