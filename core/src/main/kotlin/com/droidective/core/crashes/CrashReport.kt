package com.droidective.core.crashes

/** The three ways Android records an app going down. */
enum class CrashKind(val title: String) {
    Crash("Crashed"),
    NativeCrash("Crashed in native code"),
    Anr("Stopped responding"),
}

/**
 * One crash, native crash or ANR, read from the system's DropBox, where Android files each one with
 * a `Key: Value` header and the stack, tombstone or thread dump after a blank line.
 *
 * @property tag and [timeMillis] find the entry in DropBox again.
 * @property headline the line that says what happened: the exception, the signal or abort message,
 *   or the ANR's reason.
 * @property details everything after the header, as far as the entry was read.
 */
data class CrashReport(
    val kind: CrashKind,
    val tag: String,
    val timeMillis: Long,
    val packageName: String,
    val versionName: String?,
    val process: String,
    val pid: Int?,
    val foreground: Boolean?,
    val headline: String,
    val details: String,
) {
    companion object {
        /** The DropBox tags this reads, and what each one holds. */
        val tags: Map<String, CrashKind> =
            mapOf(
                "data_app_crash" to CrashKind.Crash,
                "system_app_crash" to CrashKind.Crash,
                "data_app_native_crash" to CrashKind.NativeCrash,
                "system_app_native_crash" to CrashKind.NativeCrash,
                "data_app_anr" to CrashKind.Anr,
                "system_app_anr" to CrashKind.Anr,
            )

        private const val ABORT = "Abort message: "
        private val packageLine = Regex("""^(\S+) v(\d+) \((.*)\)$""")
        private val signalLine = Regex("""^signal \d+ \(\w+\).*""")

        /**
         * Reads one entry's text, as `DropBoxManager.Entry.getText` returns it.
         *
         * @return the report, or null for a tag this doesn't read or an entry with no process.
         */
        fun parse(tag: String, timeMillis: Long, text: String): CrashReport? {
            val kind = tags[tag] ?: return null
            val lines = text.lines()
            val blank = lines.indexOfFirst { it.isBlank() }.let { if (it < 0) lines.size else it }
            val header =
                lines.take(blank).mapNotNull { line ->
                    line
                        .indexOf(": ")
                        .takeIf { it > 0 }
                        ?.let { line.substring(0, it) to line.substring(it + 2) }
                }
            val process = header.firstOrNull { it.first == "Process" }?.second ?: return null
            // An app sharing a process lists one Package line per package; the first is the one
            // the process was started for.
            val pkg = header.firstOrNull { it.first == "Package" }?.second?.let(packageLine::find)
            val body = lines.drop(blank + 1).dropWhile { it.isBlank() }
            return CrashReport(
                kind = kind,
                tag = tag,
                timeMillis = timeMillis,
                packageName = pkg?.groupValues?.get(1) ?: process.substringBefore(':'),
                versionName = pkg?.groupValues?.get(3),
                process = process,
                pid = header.firstOrNull { it.first == "PID" }?.second?.toIntOrNull(),
                foreground =
                    when (header.firstOrNull { it.first == "Foreground" }?.second) {
                        "Yes" -> true
                        "No" -> false
                        else -> null
                    },
                headline = headline(kind, body),
                details = body.joinToString("\n").trimEnd(),
            )
        }

        private fun headline(kind: CrashKind, body: List<String>): String =
            when (kind) {
                CrashKind.Crash -> body.firstOrNull()?.trim()
                CrashKind.NativeCrash ->
                    body
                        .firstOrNull { it.startsWith(ABORT) }
                        ?.removePrefix(ABORT)
                        ?.removeSurrounding("'") ?: body.firstOrNull { signalLine.matches(it) }
                CrashKind.Anr ->
                    body.firstOrNull { it.startsWith("Subject: ") }?.removePrefix("Subject: ")
            } ?: kind.title
    }
}
