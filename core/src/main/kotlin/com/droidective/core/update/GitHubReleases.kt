package com.droidective.core.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/** What asking GitHub for the latest release found. */
sealed interface Latest {
    data class Found(val release: Release) : Latest

    /** GitHub has no release to offer: none published yet, or the repo isn't public. */
    data object None : Latest

    data class Failed(val reason: String) : Latest
}

/**
 * Asks GitHub for this app's latest release. The request carries nothing but the app's version, in
 * its User-Agent, which GitHub requires.
 *
 * @param endpoint the `releases/latest` API URL; tests point it at a local server.
 */
class GitHubReleases(
    private val appVersion: String,
    private val endpoint: String = LATEST,
) {
    /** Blocks for up to [TIMEOUT_MILLIS] twice over, so call it off the main thread. */
    fun latest(): Latest {
        val connection =
            try {
                URI(endpoint).toURL().openConnection() as HttpURLConnection
            } catch (error: IOException) {
                return Latest.Failed("Couldn’t reach GitHub: ${error.message}")
            }
        return try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Droidective-Android/$appVersion")
            read(connection)
        } catch (error: IOException) {
            Latest.Failed("Couldn’t reach GitHub: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    private fun read(connection: HttpURLConnection): Latest =
        when (val code = connection.responseCode) {
            HttpURLConnection.HTTP_OK -> {
                val body = connection.inputStream.use { readCapped(it) }
                if (body == null) Latest.Failed("GitHub’s reply was too large.")
                else
                    Release.parse(body)?.let { Latest.Found(it) }
                        ?: Latest.Failed("GitHub’s reply wasn’t a release this app can read.")
            }
            HttpURLConnection.HTTP_NOT_FOUND -> Latest.None
            HttpURLConnection.HTTP_FORBIDDEN,
            TOO_MANY_REQUESTS ->
                Latest.Failed(
                    "GitHub turned the check away (HTTP $code). It allows 60 checks an hour " +
                        "from one network; try again later."
                )
            else -> Latest.Failed("GitHub answered HTTP $code.")
        }

    /**
     * The reply as text, or null past [MAX_REPLY_BYTES]. A loop rather than `readNBytes`, which
     * Android only has from API 33.
     */
    private fun readCapped(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return out.toByteArray().decodeToString()
            out.write(buffer, 0, count)
            if (out.size() > MAX_REPLY_BYTES) return null
        }
    }

    companion object {
        const val LATEST =
            "https://api.github.com/repos/Droidective/droidective-android/releases/latest"
        const val TIMEOUT_MILLIS = 10_000
        private const val MAX_REPLY_BYTES = 1 shl 20
        private const val BUFFER_BYTES = 8 * 1024
        private const val TOO_MANY_REQUESTS = 429
    }
}

/** When to look, and when a release is worth telling someone about. */
object UpdatePolicy {
    const val INTERVAL_MILLIS = 24L * 60 * 60 * 1000

    /** True when [release] is newer than [installed]; an unreadable installed version never is. */
    fun isNewer(installed: String, release: Release): Boolean =
        Version.parse(installed)?.let { release.version > it } ?: false

    /** A notification goes out once per release, and only for one newer than [installed]. */
    fun shouldNotify(installed: String, release: Release, notifiedTag: String?): Boolean =
        isNewer(installed, release) && release.tag != notifiedTag
}
