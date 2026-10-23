package com.droidective.core.net.grpc

/**
 * gRPC server reflection (`grpc/reflection/v1/reflection.proto`): a server describing what it
 * serves, as grpcurl asks it to, so its calls read with field names with no `.proto` on the phone.
 * The requests are written and the answers read here; sending them is the caller's.
 */
object GrpcReflection {
    /** Where to ask, the current version first: many servers still answer only the older one. */
    val PATHS =
        listOf(
            "/grpc.reflection.v1.ServerReflection/ServerReflectionInfo",
            "/grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo",
        )

    /** What one ServerReflectionResponse says. */
    sealed interface Answer {
        data class Services(val names: List<String>) : Answer

        /** FileDescriptorProtos, each serialized, as `protoc` writes them. */
        class Files(val files: List<ByteArray>) : Answer

        data class Error(val status: GrpcStatus) : Answer
    }

    /** What gathering a schema came to. */
    sealed interface Gathered {
        /** A FileDescriptorSet of [count] files, as `protoc --descriptor_set_out` writes one. */
        class Files(val set: ByteArray, val count: Int) : Gathered

        data class Refused(val why: String) : Gathered
    }

    fun listServices(): ByteArray = ProtoWire.field(LIST_SERVICES, ByteArray(0))

    fun fileContaining(symbol: String): ByteArray =
        ProtoWire.field(FILE_CONTAINING_SYMBOL, symbol.toByteArray())

    fun fileNamed(name: String): ByteArray = ProtoWire.field(FILE_BY_FILENAME, name.toByteArray())

    /** What [message], one ServerReflectionResponse, says; null when it says none of these. */
    fun answer(message: ByteArray): Answer? {
        val fields = ProtoWire.fields(message) ?: return null
        for (field in fields) {
            val bytes = field.bytes ?: continue
            when (field.number) {
                FILE_DESCRIPTOR_RESPONSE -> return Answer.Files(repeated(bytes, 1))
                LIST_SERVICES_RESPONSE ->
                    return Answer.Services(
                        repeated(bytes, 1).mapNotNull { service ->
                            repeated(service, 1).firstOrNull()?.let(::String)
                        }
                    )
                ERROR_RESPONSE -> return error(bytes)
            }
        }
        return null
    }

    /** A FileDescriptorProto's own name. */
    fun name(file: ByteArray): String? = repeated(file, FILE_NAME).firstOrNull()?.let(::String)

    /** The files a FileDescriptorProto imports, by name. */
    fun imports(file: ByteArray): List<String> = repeated(file, FILE_DEPENDENCY).map(::String)

    /** [files] as one FileDescriptorSet. */
    fun descriptorSet(files: Collection<ByteArray>): ByteArray =
        files.fold(ByteArray(0)) { set, file -> set + ProtoWire.field(SET_FILE, file) }

    /**
     * Every file [symbol] needs, asked of [ask]: the file that defines it, then each file it
     * imports that the server hasn't given yet, until nothing is missing. Some servers send a file
     * with all it imports, others only the file asked for.
     */
    fun gather(symbol: String, ask: (ByteArray) -> Answer?): Gathered {
        val files = LinkedHashMap<String, ByteArray>()
        val asked = HashSet<String>()
        val wanted = ArrayDeque(listOf(fileContaining(symbol)))
        var asks = 0
        while (wanted.isNotEmpty()) {
            if (++asks > MAX_ASKS) return Gathered.Refused("it kept naming files it didn't send")
            when (val answer = ask(wanted.removeFirst())) {
                is Answer.Files ->
                    answer.files.forEach { file -> name(file)?.let { files.putIfAbsent(it, file) } }
                is Answer.Error ->
                    return Gathered.Refused(refusal(answer.status, asks == 1, symbol))
                is Answer.Services,
                null -> return Gathered.Refused("its answer wasn't a schema")
            }
            for (missing in files.values.flatMap(::imports)) {
                if (missing !in files && asked.add(missing)) wanted += fileNamed(missing)
            }
        }
        if (files.isEmpty()) return Gathered.Refused("it sent no files for $symbol")
        return Gathered.Files(descriptorSet(files.values), files.size)
    }

    private fun refusal(status: GrpcStatus, first: Boolean, symbol: String): String =
        if (first && status.code == NOT_FOUND) "it doesn't know $symbol" else "it said $status"

    private fun error(bytes: ByteArray): Answer.Error {
        val fields = ProtoWire.fields(bytes).orEmpty()
        val code = fields.firstOrNull { it.number == 1 && it.bytes == null }?.bits?.toInt() ?: 0
        val message = fields.firstOrNull { it.number == 2 }?.bytes?.let(::String)
        return Answer.Error(GrpcStatus(code, message))
    }

    /** The length-delimited values of field [number] in [message], in order. */
    private fun repeated(message: ByteArray, number: Int): List<ByteArray> =
        ProtoWire.fields(message).orEmpty().filter { it.number == number }.mapNotNull { it.bytes }

    // ServerReflectionRequest
    private const val FILE_BY_FILENAME = 3
    private const val FILE_CONTAINING_SYMBOL = 4
    private const val LIST_SERVICES = 7

    // ServerReflectionResponse
    private const val FILE_DESCRIPTOR_RESPONSE = 4
    private const val LIST_SERVICES_RESPONSE = 6
    private const val ERROR_RESPONSE = 7

    // FileDescriptorProto and FileDescriptorSet
    private const val FILE_NAME = 1
    private const val FILE_DEPENDENCY = 3
    private const val SET_FILE = 1

    private const val NOT_FOUND = 5

    /** Far past the files any one service needs, short of a server that never stops naming more. */
    private const val MAX_ASKS = 500
}
