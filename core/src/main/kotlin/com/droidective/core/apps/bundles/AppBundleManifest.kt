package com.droidective.core.apps.bundles

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a bundle says about the app inside it: APKPure's `manifest.json` (`.xapk`) or APKMirror's
 * `info.json` (`.apkm`). Both are conveniences. Without one, or when it doesn't parse, the
 * installer scans the archive for APKs, so nothing here decides the install.
 *
 * @property splitFiles the APKs the manifest lists; empty means scan the archive instead.
 * @property expansions OBB files and where under external storage they go.
 */
data class AppBundleManifest(
    val packageName: String? = null,
    val appName: String? = null,
    val versionName: String? = null,
    val versionCode: String? = null,
    val splitFiles: List<String> = emptyList(),
    val expansions: List<Expansion> = emptyList(),
) {
    data class Expansion(val file: String, val installPath: String)

    companion object {
        /** The file each format keeps at its archive's root, if any. */
        fun fileName(format: AppPackageFormat): String? =
            when (format) {
                AppPackageFormat.Xapk -> "manifest.json"
                AppPackageFormat.Apkm -> "info.json"
                AppPackageFormat.Apk,
                AppPackageFormat.Apks -> null
            }

        /**
         * Both producers have shipped several schemas, typing one field as a string in one and a
         * number in the next, so every value is read leniently and a missing one is just null.
         */
        fun parse(text: String, format: AppPackageFormat): AppBundleManifest? {
            val root =
                try {
                    Json.parseToJsonElement(text) as? JsonObject
                } catch (_: SerializationException) {
                    null
                } ?: return null
            return when (format) {
                AppPackageFormat.Xapk -> xapk(root)
                AppPackageFormat.Apkm -> apkm(root)
                AppPackageFormat.Apk,
                AppPackageFormat.Apks -> null
            }
        }

        private fun xapk(root: JsonObject) =
            AppBundleManifest(
                packageName = string(root["package_name"]),
                appName = string(root["name"]),
                versionName = string(root["version_name"]),
                versionCode = string(root["version_code"]),
                splitFiles =
                    objects(root["split_apks"]).mapNotNull { safeRelativePath(string(it["file"])) },
                expansions =
                    objects(root["expansions"]).mapNotNull { entry ->
                        val file = safeRelativePath(string(entry["file"])) ?: return@mapNotNull null
                        Expansion(file, safeRelativePath(string(entry["install_path"])) ?: file)
                    },
            )

        /** APKMirror's `info.json` describes the release but never lists the splits. */
        private fun apkm(root: JsonObject) =
            AppBundleManifest(
                packageName = string(root["pname"]),
                appName = string(root["app_name"]) ?: string(root["apk_title"]),
                versionName = string(root["release_version"]),
                versionCode = string(root["versioncode"]),
            )

        private fun string(value: JsonElement?): String? =
            (value as? JsonPrimitive)
                ?.takeIf { it !is JsonNull }
                ?.content
                ?.takeIf { it.isNotEmpty() }

        private fun objects(value: JsonElement?): List<JsonObject> =
            (value as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

        /**
         * A bundle is untrusted, and an expansion's `install_path` is joined onto a device path, so
         * a path that is absolute, starts at home, or climbs out with `..` is refused.
         */
        fun safeRelativePath(path: String?): String? {
            if (path.isNullOrEmpty() || path.startsWith("/") || path.startsWith("~")) return null
            val parts = path.replace('\\', '/').split('/')
            if (".." in parts || "" in parts) return null
            return parts.joinToString("/")
        }
    }
}
