package com.droidective.core.net.grpc

import com.droidective.core.net.grpc.GrpcReflection.Answer
import com.droidective.core.net.grpc.GrpcReflection.Gathered
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Reflection against what grpcio-reflection 1.84 really answered (resources/grpc/reflection). */
class GrpcReflectionTest {
    @Test
    fun requestsAreWrittenAsTheReferenceLibraryWritesThem() {
        assertEquals("3a00", GrpcReflection.listServices().hex())
        assertEquals(
            "221a64726f69646563746976652e7265666c65637465642e53686f70",
            GrpcReflection.fileContaining("droidective.reflected.Shop").hex(),
        )
        assertEquals(
            "1a157265666c65637465645f74797065732e70726f746f",
            GrpcReflection.fileNamed("reflected_types.proto").hex(),
        )
    }

    @Test
    fun eachKindOfAnswerReads() {
        assertEquals(
            Answer.Services(
                listOf("droidective.reflected.Shop", "grpc.reflection.v1alpha.ServerReflection")
            ),
            answer("list"),
        )
        val files = assertIs<Answer.Files>(answer("symbol")).files
        assertEquals(
            listOf("reflected.proto", "google/protobuf/timestamp.proto", "reflected_types.proto"),
            files.map { GrpcReflection.name(it) },
        )
        assertEquals(
            listOf("google/protobuf/timestamp.proto", "reflected_types.proto"),
            GrpcReflection.imports(files.first()),
        )
        assertEquals(Answer.Error(GrpcStatus(5, "not found")), answer("missing"))
        assertEquals(null, GrpcReflection.answer(byteArrayOf(0x7f)))
    }

    @Test
    fun aServerThatSendsEverythingAtOnceIsAskedOnce() {
        val asked = mutableListOf<String>()
        val gathered =
            GrpcReflection.gather("droidective.reflected.Shop") { request ->
                asked += request.hex()
                answer("symbol")
            }
        val files = assertIs<Gathered.Files>(gathered)
        assertEquals(3, files.count)
        assertEquals(1, asked.size)
        // What reflection gave reads a call as what protoc compiled does.
        val receipt = "0a027231" + "1a050a03455552"
        val ours = assertNotNull(ProtoSchema.parse(files.set))
        val protoc = assertNotNull(ProtoSchema.parse(resource("reflected.protoset")))
        val path = "/droidective.reflected.Shop/Order"
        assertEquals(
            protoc.typed(path, answer = true)?.render(receipt.unhex()),
            ours.typed(path, answer = true)?.render(receipt.unhex()),
        )
        assertEquals(
            "id: \"r1\"\ntotal {\n  currency: \"EUR\"\n}\n",
            ours.render(receipt.unhex(), RECEIPT),
        )
    }

    @Test
    fun aServerThatSendsOnlyTheFileAskedForIsAskedForEachImport() {
        val asked = mutableListOf<ByteArray>()
        val gathered =
            GrpcReflection.gather("droidective.reflected.Shop") { request ->
                asked += request
                when (request.hex()) {
                    GrpcReflection.fileContaining("droidective.reflected.Shop").hex() ->
                        answer("symbol-alone")
                    GrpcReflection.fileNamed("reflected_types.proto").hex() -> answer("types")
                    GrpcReflection.fileNamed("google/protobuf/timestamp.proto").hex() ->
                        answer("timestamp")
                    else -> error("asked for ${request.hex()}")
                }
            }
        assertEquals(3, assertIs<Gathered.Files>(gathered).count)
        assertEquals(3, asked.size, "each import asked for once")
        assertNotNull(
            ProtoSchema.parse(gathered.set)?.typed("/droidective.reflected.Shop/Order", true)
        )
    }

    @Test
    fun aSymbolTheServerDoesntKnowIsSaidSo() {
        val gathered = GrpcReflection.gather("droidective.reflected.Nope") { answer("missing") }
        assertEquals(Gathered.Refused("it doesn't know droidective.reflected.Nope"), gathered)
    }

    @Test
    fun anImportTheServerWontGiveIsWhyNot() {
        var first = true
        val gathered =
            GrpcReflection.gather("droidective.reflected.Shop") {
                if (first) answer("symbol-alone").also { first = false }
                else Answer.Error(GrpcStatus(7, "only the file you asked for"))
            }
        assertEquals(
            Gathered.Refused("it said PERMISSION_DENIED: only the file you asked for"),
            gathered,
        )
    }

    @Test
    fun aServerThatNeverStopsNamingFilesIsStopped() {
        var made = 0
        val gathered =
            GrpcReflection.gather("x.Y") {
                made++
                // A file that imports one more, every time it is asked.
                val file =
                    ProtoWire.field(1, "f$made.proto".toByteArray()) +
                        ProtoWire.field(3, "f${made + 1}.proto".toByteArray())
                Answer.Files(listOf(file))
            }
        assertEquals(Gathered.Refused("it kept naming files it didn't send"), gathered)
        assertEquals(500, made)
    }

    @Test
    fun aDescriptorSetHoldsEachFileAsTheServerSentIt() {
        val files = assertIs<Answer.Files>(answer("symbol")).files
        val set = assertNotNull(ProtoWire.fields(GrpcReflection.descriptorSet(files)))
        // FileDescriptorSet.file is field 1, and each file goes in untouched.
        assertEquals(List(files.size) { 1 }, set.map { it.number })
        files.zip(set).forEach { (sent, kept) -> assertContentEquals(sent, kept.bytes) }
    }

    private fun answer(name: String): Answer? =
        GrpcReflection.answer(resource("$name.grpc").copyOfRange(5, resource("$name.grpc").size))

    private fun resource(name: String): ByteArray =
        assertNotNull(javaClass.getResource("/grpc/reflection/$name"), name).readBytes()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val RECEIPT = "droidective.reflected.Receipt"
    }
}
