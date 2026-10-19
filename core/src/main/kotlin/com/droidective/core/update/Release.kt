package com.droidective.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A published release of this app, as far as the update check needs it.
 *
 * @property page the release's GitHub page, where its notes and checksum are.
 * @property apk the APK attached to it, if there is one.
 */
data class Release(
    val version: Version,
    val tag: String,
    val page: String,
    val apk: Asset?,
    val notes: String,
) {
    data class Asset(val name: String, val url: String, val size: Long)

    companion object {
        /** Every page and download the app will open lives under this. */
        const val REPO = "https://github.com/Droidective/droidective-android"
        private const val PAGES = "$REPO/releases/"
        private const val DOWNLOADS = "$REPO/releases/download/"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Reads GitHub's `releases/latest` reply. A draft, a pre-release, a tag that isn't a
         * version, or a link that leaves this repo's releases gives null: the app only ever offers
         * to open this repo's own pages.
         */
        fun parse(text: String): Release? {
            val wire =
                try {
                    json.decodeFromString<Wire>(text)
                } catch (_: SerializationException) {
                    return null
                } catch (_: IllegalArgumentException) {
                    return null
                }
            if (wire.draft || wire.prerelease || !wire.htmlUrl.startsWith(PAGES)) return null
            val version = Version.parse(wire.tagName) ?: return null
            val apk =
                wire.assets
                    .firstOrNull { it.name.endsWith(".apk") && it.url.startsWith(DOWNLOADS) }
                    ?.let { Asset(it.name, it.url, it.size) }
            return Release(version, wire.tagName, wire.htmlUrl, apk, wire.body.orEmpty())
        }
    }

    @Serializable
    private class Wire(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<WireAsset> = emptyList(),
    )

    @Serializable
    private class WireAsset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
    )
}
