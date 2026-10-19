package com.droidective.core.testing

import com.droidective.core.shell.Parse
import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellResult
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Replays real device output recorded by the Mac's emulator harness (fixtures/, synced by
 * scripts/sync-fixtures.sh). Each recorded `adb -s <serial> shell <cmd>` answers `<cmd>` here.
 */
class FixtureShell(name: String = "android-emulator.json") : Shell {
    @Serializable private data class Recording(val entries: List<Entry>)

    @Serializable
    private data class Entry(
        val arguments: List<String>,
        val exitCode: Int,
        val stdout: String? = null,
        val stderr: String = "",
    )

    private val byCommand: Map<String, ShellResult>

    init {
        val dir =
            System.getProperty("fixtures.dir") ?: error("fixtures.dir isn't set for this test task")
        val recording = json.decodeFromString<Recording>(File(dir, name).readText())
        byCommand =
            recording.entries
                .filter { it.stdout != null && "shell" in it.arguments }
                .associate { entry ->
                    val command = entry.arguments.drop(entry.arguments.indexOf("shell") + 1)
                    command.joinToString(" ") to
                        ShellResult(entry.exitCode, entry.stdout.orEmpty(), entry.stderr)
                }
    }

    val commands: Set<String>
        get() = byCommand.keys

    override suspend fun run(command: ShellCommand): ShellResult =
        byCommand[command.line] ?: error("No recording for `${command.line}`")

    override fun stream(command: ShellCommand): Flow<String> = flow {
        Parse.lines(run(command).stdout).filter { it.isNotEmpty() }.forEach { emit(it) }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
