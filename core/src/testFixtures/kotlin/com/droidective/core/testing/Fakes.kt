package com.droidective.core.testing

import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellResult
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.core.toggles.Outcome
import com.droidective.core.toggles.PlatformApi
import com.droidective.core.toggles.PlatformToggles
import com.droidective.core.toggles.SettingRead
import com.droidective.core.toggles.SettingScope
import com.droidective.core.toggles.SettingsStore

/**
 * A shell that records every command and answers from a script, the Kotlin twin of ADBKit's
 * MockProcessRunner. Unscripted commands succeed with no output.
 */
class FakeShell : Shell {
    val invocations = mutableListOf<String>()
    private val responses = mutableMapOf<String, ShellResult>()
    var unavailable = false

    fun respond(line: String, stdout: String = "", exitCode: Int = 0, stderr: String = "") {
        responses[line] = ShellResult(exitCode, stdout, stderr)
    }

    override suspend fun run(command: ShellCommand): ShellResult {
        if (unavailable) throw ShellUnavailableException("Shizuku stopped")
        invocations += command.line
        return responses[command.line] ?: ShellResult(0, "", "")
    }
}

/** A settings provider held in memory, keyed by namespace and key. */
class FakeSettingsStore : SettingsStore {
    val values = mutableMapOf<Pair<String, String>, String>()
    val writable = mutableSetOf<SettingScope>()
    /** Keys Android 12+ hides from apps: reading them through the API fails. */
    val hidden = mutableSetOf<String>()
    var refuseWrites = false

    override fun get(scope: SettingScope, key: String): SettingRead =
        if (key in hidden) SettingRead.Unreadable
        else SettingRead.Value(values[scope.namespace to key])

    override fun put(scope: SettingScope, key: String, value: String): Boolean {
        if (refuseWrites) return false
        values[scope.namespace to key] = value
        return true
    }

    override fun canWrite(scope: SettingScope): Boolean = scope in writable
}

/** Platform toggles held in memory. */
class FakePlatformToggles : PlatformToggles {
    val states = mutableMapOf<PlatformApi, String>()
    val writes = mutableListOf<Pair<PlatformApi, String>>()

    override suspend fun read(api: PlatformApi): String? = states[api]

    override suspend fun write(api: PlatformApi, state: String): Outcome {
        writes += api to state
        states[api] = state
        return Outcome.Applied(state, confirmed = false)
    }
}
