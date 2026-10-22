package com.droidective.core.reactotron

import com.droidective.core.reactotron.ReactotronEvent.ApiResponse
import com.droidective.core.reactotron.ReactotronEvent.AsyncStorage
import com.droidective.core.reactotron.ReactotronEvent.Benchmark
import com.droidective.core.reactotron.ReactotronEvent.Clear
import com.droidective.core.reactotron.ReactotronEvent.ClientIntro
import com.droidective.core.reactotron.ReactotronEvent.Display
import com.droidective.core.reactotron.ReactotronEvent.Image
import com.droidective.core.reactotron.ReactotronEvent.Log
import com.droidective.core.reactotron.ReactotronEvent.StateAction
import com.droidective.core.reactotron.ReactotronEvent.StateAnswer
import com.droidective.core.reactotron.ReactotronEvent.StateChange
import com.droidective.core.reactotron.ReactotronEvent.Unknown
import java.util.Locale

/** The timeline's sorts, as its filter chips name them. */
enum class ReactotronKind(val title: String) {
    Logs("Logs"),
    Network("Network"),
    State("State"),
    Other("Other"),
}

/** How an entry reads in the timeline: short, and the same wherever one is shown. */
object ReactotronLabels {
    fun kind(event: ReactotronEvent): ReactotronKind =
        when (event) {
            is Log -> ReactotronKind.Logs
            is ApiResponse -> ReactotronKind.Network
            is StateAction,
            is StateChange,
            is StateAnswer,
            is AsyncStorage -> ReactotronKind.State
            else -> ReactotronKind.Other
        }

    /** The word ahead of a row: what kind of thing it is, in a few capitals. */
    fun tag(event: ReactotronEvent): String =
        when (event) {
            is ClientIntro -> "HELLO"
            is Log -> event.level.name.uppercase()
            is ApiResponse -> if (event.status == 0) "FAILED" else event.status.toString()
            is StateAction -> "ACTION"
            is StateChange -> "STATE"
            is StateAnswer -> if (event.keys) "KEYS" else "VALUE"
            is AsyncStorage -> "STORAGE"
            is Display -> "DISPLAY"
            is Image -> "IMAGE"
            is Benchmark -> "BENCH"
            Clear -> "CLEAR"
            is Unknown -> if (event.type == ReactotronTimeline.GONE) "GONE" else "OTHER"
        }

    /** What the row says. */
    fun summary(event: ReactotronEvent): String =
        when (event) {
            is ClientIntro ->
                listOfNotNull(event.name, event.platform, event.version).joinToString(" · ")
            is Log -> event.message
            is ApiResponse -> "${event.method} ${event.url} · ${millis(event.durationMillis)}"
            is StateAction -> event.name + (event.millis?.let { " · ${millis(it)}" } ?: "")
            is StateChange -> event.changes.joinToString(", ") { it.first }
            is StateAnswer -> event.path.ifEmpty { "the whole store" }
            is AsyncStorage -> event.action
            is Display -> listOfNotNull(event.name, event.preview).joinToString(" · ")
            is Image -> event.caption ?: event.uri
            is Benchmark ->
                event.title + (event.steps.lastOrNull()?.let { " · ${millis(it.time)}" } ?: "")
            Clear -> "The app cleared its timeline"
            is Unknown ->
                if (event.type == ReactotronTimeline.GONE) event.payload.text.orEmpty()
                else event.type
        }

    /** Whether an entry is wanted: of [kind] when there is one, and saying [query] somewhere. */
    fun matches(entry: ReactotronTimeline.Entry, kind: ReactotronKind?, query: String): Boolean {
        if (kind != null && kind(entry.event) != kind) return false
        val wanted = query.trim()
        if (wanted.isEmpty()) return true
        return listOfNotNull(entry.app, entry.command.type, tag(entry.event), summary(entry.event))
            .any { it.contains(wanted, ignoreCase = true) }
    }

    /** "38 ms", "1.2 s": as long as it took. */
    fun millis(value: Double): String =
        if (value < SECOND) "${value.toLong()} ms"
        else String.format(Locale.ROOT, "%.1f s", value / SECOND)

    private const val SECOND = 1_000.0
}
