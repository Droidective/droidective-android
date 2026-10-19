package com.droidective.core.toggles

import com.droidective.core.Access
import com.droidective.core.AccessLevel
import com.droidective.core.shell.Parse
import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.core.shell.shellQuote

/** Access the user grants in Android's own Settings screens, as opposed to an [AccessLevel]. */
enum class SpecialAccess(val title: String) {
    ModifySystemSettings("Modify system settings"),
    DoNotDisturb("Do Not Disturb access"),
}

/** A settings value as the app could read it. */
sealed interface SettingRead {
    data class Value(val value: String?) : SettingRead

    /**
     * Android 12+ won't let an app read a hidden key unless it's marked readable, even one the app
     * may write. Only the shell can read it then.
     */
    data object Unreadable : SettingRead
}

/** The Settings provider, as the app sees it. The platform module implements it. */
interface SettingsStore {
    fun get(scope: SettingScope, key: String): SettingRead

    /** Writes through the Settings API. @return false when Android refused the write. */
    fun put(scope: SettingScope, key: String, value: String): Boolean

    /** Whether the app may write [scope] through the Settings API right now. */
    fun canWrite(scope: SettingScope): Boolean
}

/** The toggles implemented with Android APIs rather than settings or commands. */
interface PlatformToggles {
    suspend fun read(api: PlatformApi): String?

    suspend fun write(api: PlatformApi, state: String): Outcome
}

/** What happened when a toggle was set. */
sealed interface Outcome {
    /** The toggle is now in [state]; [confirmed] is false when it couldn't be read back. */
    data class Applied(val state: String, val confirmed: Boolean) : Outcome

    /** The level is missing and the toggle has a stand-in, such as a system panel. */
    data class UseFallback(val fallback: Fallback) : Outcome

    /** The level is missing and there's no stand-in. */
    data class NeedsLevel(val level: AccessLevel) : Outcome

    data class NeedsSpecialAccess(val access: SpecialAccess) : Outcome

    /** The write needs a value the person hasn't set yet, such as the proxy's address. */
    data class NeedsValue(val param: Param) : Outcome

    /** Android accepted the request but the read-back shows [actual] instead. */
    data class DidNotStick(val wanted: String, val actual: String?) : Outcome

    data class Failed(val message: String) : Outcome
}

/**
 * Reads and sets toggles. All the routing lives here so it can be tested without a phone: which
 * path a write takes, which commands run, and the read-back after every change.
 */
class ToggleRunner(
    private val settings: SettingsStore,
    private val platform: PlatformToggles,
    /** Runs commands as the app itself, for reads like `getprop` that need no privilege. */
    private val appShell: Shell,
    /** The privileged shell, when Shizuku or root is available. */
    private val shell: () -> Shell?,
    private val access: () -> Access,
    /** Values a person set for parameterised toggles, such as [Param.Proxy]. */
    private val params: (Param) -> String? = { null },
) {
    /** The toggle's current state id, or null when it can't be read. */
    suspend fun read(toggle: ToggleDef): String? =
        when (val probe = toggle.read) {
            is Probe.Setting -> readSetting(probe)
            is Probe.Api -> platform.read(probe.api)
            is Probe.Command -> readCommand(probe)
            Probe.None -> null
        }

    /** Through the Settings API, or `settings get` as the shell when Android hides the key. */
    private suspend fun readSetting(probe: Probe.Setting): String? =
        when (val read = settings.get(probe.scope, probe.key)) {
            is SettingRead.Value -> probe.parse(read.value)
            SettingRead.Unreadable -> {
                val get = ShellCommand.of("settings", "get", probe.scope.namespace, probe.key)
                readCommand(
                    Probe.Command(get, { probe.parse(Parse.settingValue(it)) }, AccessLevel.Shell)
                )
            }
        }

    /** Sets [toggle] to [state], then reads it back. */
    suspend fun apply(toggle: ToggleDef, state: String): Outcome {
        require(toggle.states.any { it.id == state }) { "${toggle.id} has no state \"$state\"" }
        if (!access().allows(toggle.level)) {
            return toggle.fallback?.let { Outcome.UseFallback(it) }
                ?: Outcome.NeedsLevel(toggle.level)
        }
        val written =
            when (val write = toggle.write) {
                is Write.Settings -> writeSettings(write, state)
                is Write.Commands -> runFilled(write.commands.getValue(state))
                is Write.Derived -> runDerived(write, state)
                is Write.Api ->
                    platform.write(write.api, state).takeUnless { it is Outcome.Applied }
                is Write.Pending ->
                    Outcome.Failed("${toggle.title} isn’t built yet: ${write.reason}")
            }
        if (written != null) return written
        if (toggle.isAction || toggle.read is Probe.None)
            return Outcome.Applied(state, confirmed = false)
        val actual = read(toggle) ?: return Outcome.Applied(state, confirmed = false)
        return if (actual == state) Outcome.Applied(state, confirmed = true)
        else Outcome.DidNotStick(wanted = state, actual = actual)
    }

    private suspend fun readCommand(probe: Probe.Command): String? {
        val runner =
            if (probe.level == AccessLevel.App) appShell
            else if (access().allows(probe.level)) shell() ?: return null else return null
        return try {
            val result = runner.run(probe.command)
            if (result.succeeded) probe.parse(result.stdout) else null
        } catch (_: ShellUnavailableException) {
            null
        }
    }

    /** @return null when every key was written, or the outcome that stopped it. */
    private suspend fun writeSettings(write: Write.Settings, state: String): Outcome? {
        for ((key, template) in write.values.getValue(state)) {
            val value =
                when (val filled = Placeholders.fill(template, params, quote = false)) {
                    is Placeholders.Result.Filled -> filled.text
                    is Placeholders.Result.Missing -> return Outcome.NeedsValue(filled.param)
                }
            val outcome = writeSetting(write.scope, key, value)
            if (outcome != null) return outcome
        }
        return null
    }

    /** Fills `{name}` parameters, quoted, then runs the lines. */
    private suspend fun runFilled(templates: List<ShellCommand>): Outcome? {
        val commands = templates.map { template ->
            when (val filled = Placeholders.fill(template.line, params, quote = true)) {
                is Placeholders.Result.Filled -> ShellCommand(filled.text)
                is Placeholders.Result.Missing -> return Outcome.NeedsValue(filled.param)
            }
        }
        return runCommands(commands)
    }

    private suspend fun runDerived(write: Write.Derived, state: String): Outcome? {
        val runner = shell() ?: return Outcome.NeedsLevel(write.level)
        val probe =
            try {
                runner.run(write.probe)
            } catch (e: ShellUnavailableException) {
                return Outcome.Failed(e.message ?: "The shell stopped responding")
            }
        val commands =
            write.derive(probe.stdout, state)
                ?: return Outcome.Failed("`${write.probe}` didn’t say enough to work from")
        return runCommands(commands)
    }

    /** @return null when the write went through, or the outcome that stopped it. */
    private suspend fun writeSetting(scope: SettingScope, key: String, value: String): Outcome? {
        val viaApi =
            when (scope) {
                SettingScope.Global,
                SettingScope.Secure -> access().granted && settings.canWrite(scope)
                // Modify system settings, or else the shell when there is one: someone with Shizuku
                // or root shouldn't have to grant a special access the shell makes moot.
                SettingScope.SystemPublic ->
                    settings.canWrite(scope) || !access().allows(AccessLevel.Shell)
                SettingScope.SystemPrivate -> false
            }
        if (viaApi) {
            if (scope == SettingScope.SystemPublic && !settings.canWrite(scope)) {
                return Outcome.NeedsSpecialAccess(SpecialAccess.ModifySystemSettings)
            }
            return if (settings.put(scope, key, value)) null
            else Outcome.Failed("Android refused to change $key")
        }
        return runCommands(
            listOf(ShellCommand.of("settings", "put", scope.namespace, key, shellQuote(value)))
        )
    }

    /** @return null when every command succeeded, or the outcome that stopped it. */
    private suspend fun runCommands(commands: List<ShellCommand>): Outcome? {
        val runner = shell() ?: return Outcome.NeedsLevel(AccessLevel.Shell)
        for (command in commands) {
            val result =
                try {
                    runner.run(command)
                } catch (e: ShellUnavailableException) {
                    return Outcome.Failed(e.message ?: "The shell stopped responding")
                }
            if (!result.succeeded) {
                val reason = Parse.lines(result.stderr).firstOrNull { it.isNotBlank() }?.trim()
                return Outcome.Failed(reason ?: "`$command` exited with ${result.exitCode}")
            }
        }
        return null
    }
}
