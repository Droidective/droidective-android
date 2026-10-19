package com.droidective.core.device

import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The tables of values the phone keeps that a developer edits: settings, properties, flags. */
@Serializable
enum class ValueTable(val title: String) {
    @SerialName("global") Global("Global"),
    @SerialName("secure") Secure("Secure"),
    @SerialName("system") System("System"),
    @SerialName("props") Props("Properties"),
    @SerialName("flags") Flags("Flags");

    /** Whether a value can be deleted, rather than only set. Properties can't be unset. */
    val deletable: Boolean
        get() = this != Props
}

/** One value: its key (`namespace/name` for a flag) and what it holds; null for no value. */
data class SystemValue(val key: String, val value: String?)

/**
 * The commands that read and write each [ValueTable], as the shell user: `settings` for the three
 * settings tables, `getprop`/`setprop` for properties, and `device_config` for the flags Android
 * and Google Play services turn features on with. Every key and value is quoted.
 */
object SystemValues {
    fun list(table: ValueTable): ShellCommand =
        when (table) {
            ValueTable.Props -> ShellCommand.of("getprop")
            ValueTable.Flags -> ShellCommand.of("cmd", "device_config", "list")
            else -> ShellCommand.of("settings", "list", table.name.lowercase())
        }

    fun parse(table: ValueTable, output: String): List<SystemValue> =
        when (table) {
            ValueTable.Props -> Parse.props(output).map { (k, v) -> SystemValue(k, v) }
            else ->
                Parse.lines(output)
                    .filter { '=' in it }
                    .map { line ->
                        val key = line.substringBefore('=')
                        val value = line.substringAfter('=')
                        SystemValue(key, value.takeUnless { it == "null" })
                    }
                    .filter { it.key.isNotBlank() }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })

    /** Reads one value, for what a change replaces: the list may be out of date by then. */
    fun get(table: ValueTable, key: String): ShellCommand =
        when (table) {
            ValueTable.Props -> ShellCommand.of("getprop", shellQuote(key))
            ValueTable.Flags -> {
                val (namespace, name) = flagParts(key)
                ShellCommand.of(
                    "cmd",
                    "device_config",
                    "get",
                    shellQuote(namespace),
                    shellQuote(name),
                )
            }
            else -> ShellCommand.of("settings", "get", table.name.lowercase(), shellQuote(key))
        }

    /**
     * What [get] read. A setting or flag with no value reads `null`; a property with none reads
     * empty, which is also what putting it back sets, since a property can't be deleted.
     */
    fun current(table: ValueTable, output: String): String? {
        val value = output.trimEnd('\r', '\n')
        return if (table == ValueTable.Props) value else value.takeUnless { it == "null" }
    }

    /** Whether [key] can be changed at all: `ro.` properties are set once, as the phone starts. */
    fun editable(table: ValueTable, key: String): Boolean =
        table != ValueTable.Props || !key.startsWith("ro.")

    fun put(table: ValueTable, key: String, value: String): ShellCommand =
        when (table) {
            ValueTable.Props -> ShellCommand.of("setprop", shellQuote(key), shellQuote(value))
            ValueTable.Flags -> {
                val (namespace, name) = flagParts(key)
                ShellCommand.of(
                    "cmd",
                    "device_config",
                    "put",
                    shellQuote(namespace),
                    shellQuote(name),
                    shellQuote(value),
                )
            }
            else ->
                ShellCommand.of(
                    "settings",
                    "put",
                    table.name.lowercase(),
                    shellQuote(key),
                    shellQuote(value),
                )
        }

    fun delete(table: ValueTable, key: String): ShellCommand {
        require(table.deletable) { "${table.title} can't be deleted" }
        return when (table) {
            ValueTable.Flags -> {
                val (namespace, name) = flagParts(key)
                ShellCommand.of(
                    "cmd",
                    "device_config",
                    "delete",
                    shellQuote(namespace),
                    shellQuote(name),
                )
            }
            else -> ShellCommand.of("settings", "delete", table.name.lowercase(), shellQuote(key))
        }
    }

    /**
     * Stops Google Play services from putting flags back while a test runs: a flag set here stays
     * set until [releaseFlags] or the phone restarts, whichever comes first.
     */
    val holdFlags: ShellCommand =
        ShellCommand.of("cmd", "device_config", "set_sync_disabled_for_tests", "until_reboot")

    val releaseFlags: ShellCommand =
        ShellCommand.of("cmd", "device_config", "set_sync_disabled_for_tests", "none")

    val flagsHoldState: ShellCommand =
        ShellCommand.of("cmd", "device_config", "get_sync_disabled_for_tests")

    /** Whether [flagsHoldState] says flags are held: `until_reboot` or `persistent`. */
    fun flagsHeld(output: String): Boolean? =
        when (Parse.lines(output).firstOrNull { it.isNotBlank() }?.trim()) {
            "none" -> false
            "until_reboot",
            "persistent" -> true
            else -> null
        }

    /**
     * [reason] in a developer's terms where Android's own is an internal one: since Android 15 the
     * shell user may change only the flags on Android's allowlist.
     */
    fun explain(table: ValueTable, reason: String): String =
        if (table == ValueTable.Flags && "must add flag to the allowlist" in reason) {
            "This flag isn't on Android's allowlist for the shell user. Root can change it."
        } else {
            reason
        }

    /** The command that puts [change] back the way it was before. */
    fun undo(change: ValueChange): ShellCommand =
        change.before?.let { put(change.table, change.key, it) } ?: delete(change.table, change.key)

    private fun flagParts(key: String): Pair<String, String> {
        val namespace = key.substringBefore('/', "")
        val name = key.substringAfter('/', "")
        require(namespace.isNotEmpty() && name.isNotEmpty()) { "\"$key\" isn’t namespace/name" }
        return namespace to name
    }
}
