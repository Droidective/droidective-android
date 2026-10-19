package com.droidective.core.apps

import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote

/** A language an app can be switched to, by its BCP 47 tag. */
data class AppLanguage(val tag: String, val name: String, val note: String? = null)

/**
 * An app's own language, Android 13's per-app locales, through the locale service's shell command:
 * an app switches without the phone's language changing, which is most of what i18n testing needs.
 */
object AppLocales {
    /** The first API with per-app locales and `cmd locale`. */
    const val MIN_SDK = 33

    /**
     * The languages worth a tap, with the two pseudo-locales: en-XA stretches and accents every
     * string, ar-XB mirrors the layout right to left. A build only draws them when it was built
     * with pseudo-locales on, as debug builds usually are.
     */
    val common =
        listOf(
            AppLanguage("en-XA", "Pseudo: accented", "Long, accented strings; debug builds"),
            AppLanguage("ar-XB", "Pseudo: right to left", "Mirrored layout; debug builds"),
            AppLanguage("ar", "Arabic", "Right to left"),
            AppLanguage("he", "Hebrew", "Right to left"),
            AppLanguage("de-DE", "German", "Long words"),
            AppLanguage("fr-FR", "French"),
            AppLanguage("es-ES", "Spanish"),
            AppLanguage("pt-BR", "Portuguese (Brazil)"),
            AppLanguage("hi-IN", "Hindi"),
            AppLanguage("ta-IN", "Tamil"),
            AppLanguage("ja-JP", "Japanese"),
            AppLanguage("zh-CN", "Chinese (Simplified)"),
            AppLanguage("ko-KR", "Korean"),
            AppLanguage("ru-RU", "Russian"),
            AppLanguage("tr-TR", "Turkish"),
            AppLanguage("en-GB", "English (UK)"),
            AppLanguage("en-US", "English (US)"),
        )

    private val tag = Regex("""^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$""")

    /** A language tag such as "fr", "pt-BR" or "zh-Hant-TW". */
    fun isTag(text: String): Boolean = tag.matches(text)

    /** Sets [packageName]'s languages, first preferred; none puts it back on the phone's. */
    fun set(packageName: String, tags: List<String>): ShellCommand {
        require(AppActions.isPackageName(packageName)) { "\"$packageName\" isn’t a package name" }
        require(tags.all(::isTag)) { "${tags.filterNot(::isTag)} aren’t language tags" }
        val base = ShellCommand.of("cmd", "locale", "set-app-locales", shellQuote(packageName))
        return if (tags.isEmpty()) base
        else ShellCommand("${base.line} --locales ${shellQuote(tags.joinToString(","))}")
    }

    fun get(packageName: String): ShellCommand {
        require(AppActions.isPackageName(packageName)) { "\"$packageName\" isn’t a package name" }
        return ShellCommand.of("cmd", "locale", "get-app-locales", shellQuote(packageName))
    }

    private val answer = Regex("""are \[(.*)\]""")

    /**
     * Reads `Locales for <pkg> for user 0 are [fr-FR,de]`: the app's languages, empty when it
     * follows the phone's, null when the output isn't that.
     */
    fun parse(output: String): List<String>? {
        val inside =
            Parse.lines(output).firstNotNullOfOrNull { answer.find(it)?.groupValues?.get(1) }
                ?: return null
        return inside.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** What a Monkey run did, from its verbose output. */
data class MonkeyReport(
    val injected: Int?,
    val crashed: Boolean,
    val notResponding: Boolean,
    val aborted: Boolean,
    val crash: String?,
)

/**
 * The Mac's Monkey Test: random taps, swipes and keys sent to one app, to shake out crashes. It's
 * destructive (it presses whatever it finds), so it asks first wherever it starts.
 */
object Monkey {
    val COUNTS = 1..100_000

    /** Long enough between events for the app to draw, so a run finds real crashes, not lag. */
    const val THROTTLE_MILLIS = 50

    /**
     * `-v` prints each event and, at the end, how many were injected and what went wrong. System
     * keys are off: on a phone with no hardware keys Monkey refuses to start with them on.
     */
    fun command(packageName: String, count: Int, seed: Long? = null): ShellCommand {
        require(AppActions.isPackageName(packageName)) { "\"$packageName\" isn’t a package name" }
        require(count in COUNTS) { "$count events is outside ${COUNTS.first}–${COUNTS.last}" }
        val words =
            listOfNotNull(
                "monkey",
                "-p",
                shellQuote(packageName),
                "--throttle",
                THROTTLE_MILLIS.toString(),
                "--pct-syskeys",
                "0",
                seed?.let { "-s $it" },
                "-v",
                count.toString(),
            )
        return ShellCommand(words.joinToString(" "))
    }

    private val injected = Regex("""Events injected: (\d+)""")

    /**
     * Reads a run's output: how many events went in, and the first crash's exception line, which
     * Monkey prints as `// Long Msg: java.lang.…` after `// CRASH:`.
     */
    fun report(output: String): MonkeyReport {
        val lines = Parse.lines(output)
        val crashAt = lines.indexOfFirst { it.startsWith("// CRASH:") }
        val crash =
            if (crashAt < 0) null
            else
                lines
                    .drop(crashAt)
                    .firstOrNull { it.startsWith("// Long Msg:") }
                    ?.removePrefix("// Long Msg:")
                    ?.trim()
        return MonkeyReport(
            injected =
                lines.firstNotNullOfOrNull { injected.find(it)?.groupValues?.get(1) }?.toInt(),
            crashed = crashAt >= 0,
            notResponding = lines.any { it.startsWith("// NOT RESPONDING:") },
            aborted = lines.any { "Monkey aborted" in it },
            crash = crash,
        )
    }
}
