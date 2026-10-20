package com.droidective.core.apps

import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What an intent starts: `am start`, `am broadcast` or `am startservice`. */
@Serializable
enum class IntentKind(val verb: String, val title: String) {
    @SerialName("activity") Activity("start", "Activity"),
    @SerialName("broadcast") Broadcast("broadcast", "Broadcast"),
    @SerialName("service") Service("startservice", "Service"),
    @SerialName("foreground-service")
    ForegroundService("start-foreground-service", "Foreground service"),
}

/** An extra's type, as `am`'s flag for it says. */
@Serializable
enum class ExtraType(val flag: String, val title: String, val expected: String) {
    @SerialName("string") Text("--es", "Text", "text"),
    @SerialName("int") Int("--ei", "Int", "a whole number"),
    @SerialName("long") Long("--el", "Long", "a whole number"),
    @SerialName("float") Float("--ef", "Float", "a number"),
    @SerialName("bool") Bool("--ez", "Boolean", "true or false"),
    @SerialName("uri") Uri("--eu", "Uri", "a URI");

    /** Whether [value] is one of these, so `am` won't stop at it. */
    fun accepts(value: String): Boolean =
        when (this) {
            Int -> value.toIntOrNull() != null
            Long -> value.toLongOrNull() != null
            Float -> value.toFloatOrNull() != null
            Bool -> value == "true" || value == "false"
            Text,
            Uri -> true
        }
}

@Serializable data class IntentExtra(val key: String, val type: ExtraType, val value: String)

/** The `Intent` flags people reach for, as `am` takes them. */
@Serializable
enum class IntentFlag(val option: String, val title: String) {
    @SerialName("new-task") NewTask("--activity-new-task", "New task"),
    @SerialName("clear-task") ClearTask("--activity-clear-task", "Clear task"),
    @SerialName("clear-top") ClearTop("--activity-clear-top", "Clear top"),
    @SerialName("single-top") SingleTop("--activity-single-top", "Single top"),
    @SerialName("no-history") NoHistory("--activity-no-history", "No history"),
    @SerialName("foreground") Foreground("--receiver-foreground", "Foreground receiver"),
}

/**
 * One intent, everything optional but what's set. [coldStart] stops the app first and [wait] waits
 * for the launch, which together time a cold start the way Android Studio does.
 */
@Serializable
data class IntentSpec(
    val kind: IntentKind = IntentKind.Activity,
    val action: String = "",
    val data: String = "",
    val type: String = "",
    val component: String = "",
    val packageName: String = "",
    val categories: List<String> = emptyList(),
    val extras: List<IntentExtra> = emptyList(),
    val flags: List<IntentFlag> = emptyList(),
    val wait: Boolean = true,
    val coldStart: Boolean = false,
) {
    /** What's wrong with it before it runs, or null. */
    val problem: String?
        get() =
            when {
                action.isBlank() && data.isBlank() && component.isBlank() ->
                    "An intent needs an action, data or a component."
                component.isNotBlank() && !Intents.isComponent(component) ->
                    "A component is package/class, such as com.example/.MainActivity."
                packageName.isNotBlank() && !AppActions.isPackageName(packageName) ->
                    "\"$packageName\" isn’t a package name."
                extras.any { it.key.isBlank() } -> "Every extra needs a key."
                else ->
                    extras
                        .firstOrNull { !it.type.accepts(it.value) }
                        ?.let { "${it.key} has to be ${it.type.expected}, not \"${it.value}\"." }
            }
}

/** What `am` said back. [totalMillis] is how long the launch took, when it was waited for. */
data class IntentResult(
    val ok: Boolean,
    val message: String,
    val launchState: String?,
    val activity: String?,
    val totalMillis: Int?,
    val waitMillis: Int?,
)

/** Intents sent through `am` as the shell, which can reach anything exported. */
object Intents {
    private val component = Regex("""^[A-Za-z][\w.]*/[\w.$]+$""")

    fun isComponent(value: String): Boolean = component.matches(value)

    fun command(spec: IntentSpec): ShellCommand {
        require(spec.problem == null) { spec.problem.orEmpty() }
        val words = mutableListOf("am", spec.kind.verb)
        if (spec.kind == IntentKind.Activity) {
            if (spec.wait) words += "-W"
            if (spec.coldStart) words += "-S"
        }
        if (spec.action.isNotBlank()) words += listOf("-a", shellQuote(spec.action))
        if (spec.data.isNotBlank()) words += listOf("-d", shellQuote(spec.data))
        if (spec.type.isNotBlank()) words += listOf("-t", shellQuote(spec.type))
        spec.categories.filter { it.isNotBlank() }.forEach { words += listOf("-c", shellQuote(it)) }
        spec.flags.forEach { words += it.option }
        spec.extras.forEach {
            words += listOf(it.type.flag, shellQuote(it.key), shellQuote(it.value))
        }
        if (spec.component.isNotBlank()) words += listOf("-n", shellQuote(spec.component))
        else if (spec.packageName.isNotBlank()) words += listOf("-p", shellQuote(spec.packageName))
        return ShellCommand(words.joinToString(" "))
    }

    /** The same intent from a computer, for a script or a bug report. */
    fun adb(spec: IntentSpec): String = "adb shell " + command(spec).line

    /** Reads `am`'s answer. `am` exits 0 for most failures, so its words decide. */
    fun parse(output: String): IntentResult {
        val lines = Parse.lines(output).map { it.trim() }.filter { it.isNotEmpty() }
        fun field(name: String) =
            lines.firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()
        val error = lines.firstOrNull { it.startsWith("Error") && ':' in it }
        val exception = Parse.failure(output).takeIf { "Exception" in output }
        val broadcast = field("Broadcast completed")
        val failed = error != null || exception != null
        return IntentResult(
            ok = !failed,
            message =
                when {
                    error != null -> error.substringAfter(':').trim()
                    exception != null -> exception
                    broadcast != null -> "Delivered, $broadcast"
                    field("Status") == "ok" -> "Started"
                    else -> lines.lastOrNull() ?: "Sent"
                },
            launchState = field("LaunchState"),
            activity = field("Activity"),
            totalMillis = field("TotalTime")?.toIntOrNull(),
            waitMillis = field("WaitTime")?.toIntOrNull(),
        )
    }
}
