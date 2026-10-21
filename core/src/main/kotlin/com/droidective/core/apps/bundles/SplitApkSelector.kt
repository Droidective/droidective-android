package com.droidective.core.apps.bundles

/**
 * The installable package formats. Only `.apk` is one package; the others are zips around a split
 * app (a base APK plus per-ABI, density and language config splits), each from a different
 * producer: `.apks` from bundletool (or SAI), `.xapk` from APKPure, `.apkm` from APKMirror.
 */
enum class AppPackageFormat(val extension: String, val displayName: String) {
    Apk("apk", "APK"),
    Apks("apks", "APKS"),
    Xapk("xapk", "XAPK"),
    Apkm("apkm", "APKM");

    val isBundle: Boolean
        get() = this != Apk

    companion object {
        /** By file extension; null for anything else, so a stray file is refused, not guessed. */
        fun detect(fileName: String): AppPackageFormat? {
            val extension = fileName.substringAfterLast('/').substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.extension == extension }
        }

        /**
         * By media type, for a file another app handed over under a name with no extension. Only
         * the types that name one format; `application/octet-stream` and `zip` name none.
         */
        fun ofMediaType(type: String?): AppPackageFormat? = MEDIA_TYPES[type?.lowercase()]

        /** What [ofMediaType] knows, for the intent filters that take these files. */
        val MEDIA_TYPES =
            mapOf(
                "application/vnd.android.package-archive" to Apk,
                "application/xapk-package-archive" to Xapk,
                "application/vnd.apkm" to Apkm,
            )
    }
}

/**
 * What this phone can run, as far as split selection cares.
 *
 * @property abis supported ABIs, best first, as Android names them (`arm64-v8a`).
 * @property densityDpi the screen density; 0 when unknown.
 * @property languages the person's languages as ISO codes, such as `en`.
 */
data class DeviceSpec(val abis: List<String>, val densityDpi: Int, val languages: List<String>)

/**
 * The splits to install, and whether the bundle's native code fits the phone at all.
 *
 * @property files the chosen entries, in the order given.
 * @property abiUnmatched the bundle ships ABI splits but none for this phone: installing the rest
 *   gives an app that crashes on its first native call.
 */
data class SplitSelection(
    val files: List<String>,
    val chosenAbi: String?,
    val abiUnmatched: Boolean,
)

/**
 * Picks the splits of a bundle that belong on one phone, as Play would deliver them: the base and
 * every feature module, one ABI, one density, and the phone's languages. The same rules as the
 * Mac's `SplitApkSelector`.
 */
object SplitApkSelector {
    /** The qualifier a split's file name encodes. */
    sealed interface Qualifier {
        /** A base APK or a feature module: always installed. */
        data object Base : Qualifier

        data class Abi(val abi: String) : Qualifier

        data class Density(val bucket: String) : Qualifier

        data class Language(val language: String) : Qualifier

        /**
         * A qualifier this doesn't model (texture compression, device tier). Kept: dropping a split
         * the app needs fails worse than carrying one it doesn't.
         */
        data class Other(val token: String) : Qualifier
    }

    private val knownAbis =
        setOf("armeabi", "armeabi_v7a", "arm64_v8a", "x86", "x86_64", "mips", "mips64", "riscv64")

    private val densityBuckets =
        mapOf(
            "ldpi" to 120,
            "mdpi" to 160,
            "tvdpi" to 213,
            "hdpi" to 240,
            "xhdpi" to 320,
            "xxhdpi" to 480,
            "xxxhdpi" to 640,
        )

    private val densityAgnostic = setOf("nodpi", "anydpi")

    /**
     * Classifies a split by file name. Play, APKPure and APKMirror write `config.<qualifier>.apk`
     * (with a module prefix for a feature's own splits); bundletool writes `base-<qualifier>.apk`
     * and `<module>-master.apk`. Only the file name counts, not the directory around it.
     */
    fun qualifier(fileName: String): Qualifier {
        val stem = fileName.substringAfterLast('/').substringBeforeLast('.').lowercase()
        val token =
            when {
                stem.contains("config.") -> stem.substringAfterLast("config.")
                stem.contains('-') -> stem.substringAfterLast('-')
                else -> return Qualifier.Base
            }
        if (token.isEmpty() || token == "master") return Qualifier.Base
        return classify(token)
    }

    /**
     * ABIs and densities are closed sets, so they're checked first; a language tag is the only
     * other thing Play emits that looks like a bare word.
     */
    private fun classify(token: String): Qualifier {
        val normalized = token.replace('-', '_')
        return when {
            normalized in knownAbis -> Qualifier.Abi(normalized)
            normalized in densityBuckets || normalized in densityAgnostic ->
                Qualifier.Density(normalized)
            // Texture-compression splits would otherwise read as the language "tcf".
            normalized.startsWith("tcf_") -> Qualifier.Other(normalized)
            isLanguageTag(normalized) -> Qualifier.Language(normalized.substringBefore('_'))
            else -> Qualifier.Other(normalized)
        }
    }

    /** A 2–3 letter ISO code, optionally followed by a script or region (`pt_br`, `b+sr+latn`). */
    private fun isLanguageTag(token: String): Boolean {
        val head = token.takeWhile { it in 'a'..'z' }
        if (head.length !in 2..3) return false
        val rest = token.drop(head.length)
        return rest.isEmpty() || rest.first() == '_' || rest.first() == '+'
    }

    /**
     * Every base and feature APK; the best ABI the phone has; the smallest density at or above the
     * screen's, else the largest below, so nothing is upscaled; the phone's languages and English,
     * the near-universal fallback when its own isn't shipped.
     */
    fun select(files: List<String>, spec: DeviceSpec): SplitSelection {
        val classified = files.map { it to qualifier(it) }
        val available = classified.mapNotNull { (it.second as? Qualifier.Abi)?.abi }.toSet()
        val chosenAbi =
            spec.abis.map { it.replace('-', '_').lowercase() }.firstOrNull { it in available }
        val chosenDensity = pickDensity(classified.map { it.second }, spec.densityDpi)
        val languages = spec.languages.map { it.lowercase() }.toSet() + "en"
        val kept = classified.filter { (_, qualifier) ->
            when (qualifier) {
                Qualifier.Base,
                is Qualifier.Other -> true
                is Qualifier.Abi -> qualifier.abi == chosenAbi
                is Qualifier.Density ->
                    qualifier.bucket in densityAgnostic || qualifier.bucket == chosenDensity
                is Qualifier.Language -> qualifier.language in languages
            }
        }
        return SplitSelection(
            files = kept.map { it.first },
            chosenAbi = chosenAbi,
            abiUnmatched = available.isNotEmpty() && chosenAbi == null,
        )
    }

    /** With an unknown screen density the largest bucket wins: big art scales down cleanly. */
    private fun pickDensity(qualifiers: List<Qualifier>, deviceDpi: Int): String? {
        val buckets =
            qualifiers
                .mapNotNull { (it as? Qualifier.Density)?.bucket }
                .distinct()
                .mapNotNull { name -> densityBuckets[name]?.let { name to it } }
        if (buckets.isEmpty()) return null
        val largest = buckets.maxBy { it.second }
        if (deviceDpi <= 0) return largest.first
        return (buckets.filter { it.second >= deviceDpi }.minByOrNull { it.second } ?: largest)
            .first
    }
}
