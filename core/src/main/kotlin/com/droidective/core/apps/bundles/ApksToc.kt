package com.droidective.core.apps.bundles

/**
 * One variant of a bundletool `.apks`: the APKs built for one range of Android versions.
 *
 * @property minSdk the lowest API level it targets; null when it targets every one.
 * @property number bundletool's variant number; files of variant n > 0 end in `_<n+1>.apk`.
 */
data class ApksVariant(val minSdk: Int?, val number: Int, val paths: List<String>)

/**
 * The `toc.pb` table of contents bundletool writes into every `.apks`: a `BuildApksResult`
 * protobuf. Only what picking a variant needs is read, field numbers from bundletool's
 * `commands.proto` and `targeting.proto`:
 * ```
 * BuildApksResult { repeated Variant variant = 1; }
 * Variant { VariantTargeting targeting = 1; repeated ApkSet apk_set = 2; uint32 variant_number = 3; }
 * VariantTargeting { SdkVersionTargeting sdk_version_targeting = 1; }
 * SdkVersionTargeting { repeated SdkVersion value = 1; }
 * SdkVersion { google.protobuf.Int32Value min = 1; }    Int32Value { int32 value = 1; }
 * ApkSet { repeated ApkDescription apk_description = 2; }
 * ApkDescription { string path = 2; }
 * ```
 */
object ApksToc {
    /** Every variant, or null when [bytes] isn't a table bundletool wrote. */
    fun parse(bytes: ByteArray): List<ApksVariant>? =
        try {
            Reader(bytes).messages(1).map(::variant).toList()
        } catch (_: MalformedToc) {
            null
        }

    /**
     * The variant for API level [sdk]: the one with the highest minimum at or below it, as
     * bundletool picks. Standalone-only variants (pre-Lollipop) hold no splits and are skipped.
     */
    fun pick(variants: List<ApksVariant>, sdk: Int): ApksVariant? =
        variants
            .filter { variant -> variant.paths.any { it.startsWith("splits/") } }
            .filter { (it.minSdk ?: 0) <= sdk }
            .maxByOrNull { it.minSdk ?: 0 }

    private fun variant(bytes: ByteArray): ApksVariant {
        val reader = Reader(bytes)
        var minSdk: Int? = null
        var number = 0
        val paths = mutableListOf<String>()
        reader.forEach { field, value ->
            when (field) {
                1 -> minSdk = minSdk ?: minSdk(value.bytes())
                2 -> paths += apkPaths(value.bytes())
                3 -> number = value.varint().toInt()
            }
        }
        return ApksVariant(minSdk, number, paths)
    }

    private fun minSdk(targeting: ByteArray): Int? {
        val sdk = Reader(targeting).messages(1).firstOrNull() ?: return null
        val version = Reader(sdk).messages(1).firstOrNull() ?: return null
        val min = Reader(version).messages(1).firstOrNull() ?: return null
        var value: Int? = null
        Reader(min).forEach { field, v -> if (field == 1) value = v.varint().toInt() }
        return value
    }

    private fun apkPaths(apkSet: ByteArray): List<String> =
        Reader(apkSet)
            .messages(2)
            .mapNotNull { description ->
                var path: String? = null
                Reader(description).forEach { field, v ->
                    if (field == 2) path = v.bytes().decodeToString()
                }
                path
            }
            .toList()

    private class MalformedToc : Exception()

    /** A field's value: a varint, or a length-delimited run of bytes. */
    private class Value(private val varint: Long?, private val bytes: ByteArray?) {
        fun varint(): Long = varint ?: throw MalformedToc()

        fun bytes(): ByteArray = bytes ?: throw MalformedToc()
    }

    /**
     * Protobuf's wire format, enough of it: varints, length-delimited fields, fixed widths skipped.
     */
    private class Reader(private val data: ByteArray) {
        private var at = 0

        fun forEach(each: (field: Int, value: Value) -> Unit) {
            at = 0
            while (at < data.size) {
                val key = readVarint()
                val field = (key ushr 3).toInt()
                if (field == 0) throw MalformedToc()
                when ((key and 7).toInt()) {
                    VARINT -> each(field, Value(readVarint(), null))
                    LENGTH -> {
                        val length = readVarint().toInt()
                        if (length < 0 || at + length > data.size) throw MalformedToc()
                        each(field, Value(null, data.copyOfRange(at, at + length)))
                        at += length
                    }
                    FIXED64 -> skip(8)
                    FIXED32 -> skip(4)
                    else -> throw MalformedToc()
                }
            }
        }

        /** Every length-delimited value of [field], in order. */
        fun messages(field: Int): Sequence<ByteArray> {
            val found = mutableListOf<ByteArray>()
            forEach { f, value -> if (f == field) found += value.bytes() }
            return found.asSequence()
        }

        private fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < MAX_VARINT_BITS) {
                if (at >= data.size) throw MalformedToc()
                val byte = data[at++].toInt()
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
            throw MalformedToc()
        }

        private fun skip(count: Int) {
            if (at + count > data.size) throw MalformedToc()
            at += count
        }

        companion object {
            const val VARINT = 0
            const val FIXED64 = 1
            const val LENGTH = 2
            const val FIXED32 = 5
            const val MAX_VARINT_BITS = 64
        }
    }
}
