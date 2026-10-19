package com.droidective.android.crashes

import android.os.Build
import com.droidective.android.BuildConfig
import java.io.File

/**
 * Droidective's own crash log: no SDK and nothing sent anywhere. An uncaught exception is written
 * to a file before Android's handler takes the process down, and Setup offers the last one to copy
 * into a bug report. Crash Catcher sees these too, but only once it has its permissions.
 */
class OwnCrashes(private val dir: File) {
    /** A crash this app recorded: when, and the report as written. */
    data class Record(val timeMillis: Long, val text: String)

    /** Records each uncaught exception, then hands it to [previous] as before. */
    fun install(previous: Thread.UncaughtExceptionHandler?) {
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // A crash log that crashes would hide the crash it was writing down.
            runCatching { record(thread, error, System.currentTimeMillis()) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(thread: Thread, error: Throwable, timeMillis: Long) {
        dir.mkdirs()
        File(dir, "$timeMillis$SUFFIX").writeText(report(thread, error))
        recent().drop(KEEP).forEach { File(dir, "${it.timeMillis}$SUFFIX").delete() }
    }

    /** Newest first, at most [KEEP]. */
    fun recent(): List<Record> =
        dir.listFiles { file -> file.name.endsWith(SUFFIX) }
            .orEmpty()
            .mapNotNull { file ->
                file.name.removeSuffix(SUFFIX).toLongOrNull()?.let { Record(it, file.readText()) }
            }
            .sortedByDescending { it.timeMillis }

    fun clear() {
        dir.listFiles { file -> file.name.endsWith(SUFFIX) }.orEmpty().forEach { it.delete() }
    }

    private fun report(thread: Thread, error: Throwable): String =
        "Droidective ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on Android " +
            "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Thread: ${thread.name}\n\n" +
            error.stackTraceToString()

    companion object {
        const val KEEP = 5
        private const val SUFFIX = ".txt"
    }
}
