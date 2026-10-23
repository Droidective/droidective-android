package com.droidective.core.automations

import com.droidective.core.apps.AppActions
import com.droidective.core.commands.CustomCommand
import com.droidective.core.shell.shellQuote
import com.droidective.core.toggles.ToggleDef
import com.droidective.core.toggles.Toggles
import com.droidective.core.toggles.Write

/** One action as a person reads it, with every shell line it can run. */
data class ReviewLine(val text: String, val shell: List<String>, val destructive: Boolean)

/**
 * What an automation does, spelled out. An imported automation arrives switched off, and this is
 * what someone reads before turning it on: every action, and every shell command it will run.
 */
object Review {
    fun lines(
        automation: Automation,
        commands: (String) -> CustomCommand?,
        toggles: (String) -> ToggleDef? = Toggles::byId,
    ): List<ReviewLine> =
        automation.actions.map { action ->
            ReviewLine(
                describe(action, commands, toggles),
                shell(action, commands, toggles),
                action.isDestructive,
            )
        }

    fun describe(
        action: Action,
        commands: (String) -> CustomCommand?,
        toggles: (String) -> ToggleDef? = Toggles::byId,
    ): String =
        when (action) {
            is Action.SetToggle -> {
                val toggle = toggles(action.toggleId)
                val label = toggle?.states?.firstOrNull { it.id == action.state }?.label
                "${toggle?.title ?: action.toggleId}: ${label ?: action.state}"
            }
            is Action.OpenApp -> "Open ${action.label}"
            is Action.OpenLink -> "Open ${action.uri}"
            is Action.OpenShortcut -> "Open ${action.label}"
            is Action.OpenSettings -> "Open ${action.label}"
            is Action.RunCommand ->
                commands(action.commandId)?.let { "Run “${it.name}”" }
                    ?: "Run a command that isn’t on this phone"
            is Action.ForApp -> "${action.action.title}: ${action.packageName}"
            is Action.Permission ->
                "${if (action.grant) "Grant" else "Revoke"} ${action.permission} " +
                    "${if (action.grant) "to" else "from"} ${action.packageName}"
            is Action.Notify -> "Notify: ${action.title}"
            is Action.Wait -> "Wait ${action.seconds} s"
            is Action.RunAutomation -> "Run the automation “${action.automationId}”"
            is Action.SetLocation -> "Put the phone at ${action.name}"
            Action.RealLocation -> "Give the phone its own location back"
            is Action.ReactotronCommand -> "Run “${action.label}” in ${action.app}"
        }

    /** The shell lines [action] can run; settings written through Android's API run none. */
    fun shell(
        action: Action,
        commands: (String) -> CustomCommand?,
        toggles: (String) -> ToggleDef? = Toggles::byId,
    ): List<String> =
        when (action) {
            is Action.SetToggle -> toggles(action.toggleId)?.let { toggleLines(it, action.state) }
            is Action.RunCommand ->
                commands(action.commandId)?.let { listOfNotNull(it.command, it.offCommand) }
            is Action.ForApp -> listOf(AppActions.command(action.action, action.packageName).line)
            is Action.Permission ->
                listOf(
                    AppActions.permission(action.packageName, action.permission, action.grant).line
                )
            is Action.Notify -> action.buttons.flatMap { shell(it.action, commands, toggles) }
            else -> null
        }.orEmpty()

    /**
     * A settings write is shown as the `settings put` the shell runs when Android's API can't take
     * it; a density step as the probe that it's relative to.
     */
    private fun toggleLines(toggle: ToggleDef, state: String): List<String> =
        when (val write = toggle.write) {
            is Write.Commands -> write.commands[state].orEmpty().map { it.line }
            is Write.Settings ->
                write.values[state].orEmpty().map { (key, value) ->
                    "settings put ${write.scope.namespace} $key ${shellQuote(value)}"
                }
            is Write.Derived -> listOf("${write.probe.line}, then a line relative to its answer")
            is Write.Api,
            is Write.Pending -> emptyList()
        }
}
