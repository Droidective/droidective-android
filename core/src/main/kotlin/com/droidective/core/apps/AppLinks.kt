package com.droidective.core.apps

import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote

/** One domain an app declares for App Links, with where Android's verification of it stands. */
data class LinkDomain(val domain: String, val state: String) {
    /** Whether a link to it opens the app straight away, with no chooser. */
    val opensTheApp: Boolean
        get() = state in VERIFIED

    /** What [state] means for someone wondering why a link opens the browser. */
    val meaning: String
        get() =
            when {
                state in VERIFIED -> MEANINGS.getValue(state)
                state.toIntOrNull()?.let { it >= CUSTOM_ERROR } == true ->
                    "Failed: the verifier's error $state. Check assetlinks.json and its fingerprint."
                else -> MEANINGS[state] ?: state
            }

    private companion object {
        val VERIFIED =
            setOf(
                "verified",
                "approved",
                "migrated",
                "restored",
                "system_configured",
                "pre_verified",
            )
        val MEANINGS =
            mapOf(
                "verified" to "Verified: links open the app",
                "approved" to "Approved by a shell command: links open the app",
                "migrated" to "Kept from before an update: links open the app",
                "restored" to "Restored from a backup: links open the app",
                "system_configured" to "Approved by the phone's own setup",
                "pre_verified" to "Verified by the installer: links open the app",
                "none" to "Not verified: links open a chooser or the browser",
                "denied" to "Denied by a shell command: links open the browser",
                "legacy_failure" to
                    "Failed an older check: links open a chooser. Check assetlinks.json.",
            )
        const val CUSTOM_ERROR = 1024
    }
}

/**
 * What `pm get-app-links --user` says about one app: each domain's verification, and whether this
 * user lets it open links at all, and for which domains.
 */
data class AppLinkStatus(
    val domains: List<LinkDomain>,
    val handlingAllowed: Boolean?,
    val enabled: List<String>,
    val disabled: List<String>,
)

/**
 * Android App Links, as Android 12 and later verify them: read, verified again, or approved for a
 * test build whose domain can't host its assetlinks.json.
 */
object AppLinks {
    fun status(packageName: String, user: Int): ShellCommand =
        ShellCommand("pm get-app-links --user $user ${quoted(packageName)}")

    /** Asks the verifier again, even if it answered before. It answers in the background. */
    fun reverify(packageName: String): ShellCommand =
        ShellCommand("pm verify-app-links --re-verify ${quoted(packageName)}")

    /** Every declared domain approved, as if verified, which a debug build often needs. */
    fun approve(packageName: String): ShellCommand =
        ShellCommand("pm set-app-links --package ${quoted(packageName)} 2 all")

    /** Lets this user's links open the app for every declared domain, verified or not. */
    fun allowForUser(packageName: String, user: Int): ShellCommand =
        ShellCommand(
            "pm set-app-links-user-selection --user $user --package ${quoted(packageName)} true all"
        )

    /** Back to no answer recorded, then asks the verifier, as after a fresh install. */
    fun reset(packageName: String): List<ShellCommand> =
        listOf(ShellCommand("pm reset-app-links ${quoted(packageName)}"), reverify(packageName))

    fun parse(output: String): AppLinkStatus {
        val lines = Parse.lines(output)
        fun block(header: String): List<String> {
            val start = lines.indexOfFirst { it.trim() == header }
            if (start < 0) return emptyList()
            val indent = lines[start].indexOfFirst { !it.isWhitespace() }
            return lines.drop(start + 1).takeWhile { line ->
                line.isNotBlank() && line.indexOfFirst { !it.isWhitespace() } > indent
            }
        }
        val domains =
            block("Domain verification state:").mapNotNull { line ->
                val trimmed = line.trim()
                val domain = trimmed.substringBefore(':', "")
                val state = trimmed.substringAfter(':', "").trim()
                if (domain.isEmpty() || state.isEmpty()) null else LinkDomain(domain, state)
            }
        val allowed = lines.firstNotNullOfOrNull { line ->
            line
                .trim()
                .removePrefix("Verification link handling allowed:")
                .takeIf { it != line.trim() }
                ?.trim()
                ?.toBooleanStrictOrNull()
        }
        return AppLinkStatus(
            domains = domains,
            handlingAllowed = allowed,
            enabled = block("Enabled:").map { it.trim() },
            disabled = block("Disabled:").map { it.trim() },
        )
    }

    private fun quoted(packageName: String): String {
        require(AppActions.isPackageName(packageName)) { "\"$packageName\" isn’t a package name" }
        return shellQuote(packageName)
    }
}
