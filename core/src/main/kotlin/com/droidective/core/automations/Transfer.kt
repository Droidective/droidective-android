package com.droidective.core.automations

import com.droidective.core.commands.CustomCommand
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Automations as a file, with the custom commands they run, so the file works on a phone that
 * doesn't have those commands yet.
 */
@Serializable
data class AutomationBundle(
    val format: String = AutomationTransfer.FORMAT,
    val version: Int = AutomationTransfer.VERSION,
    val automations: List<Automation>,
    val commands: List<CustomCommand> = emptyList(),
)

object AutomationTransfer {
    const val FORMAT = "droidective-automations"
    const val VERSION = 1

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    sealed interface Result {
        /**
         * Ready to save: every automation switched off, ids that clashed renamed, and references
         * between them rewritten to match.
         */
        data class Imported(val automations: List<Automation>, val commands: List<CustomCommand>) :
            Result

        data class Failed(val reason: String) : Result
    }

    fun export(automations: List<Automation>, commands: (String) -> CustomCommand?): String {
        val used = automations.flatMap { commandIds(it) }.distinct().mapNotNull(commands)
        return json.encodeToString(
            AutomationBundle.serializer(),
            AutomationBundle(automations = automations, commands = used),
        )
    }

    fun import(
        text: String,
        existingAutomationIds: Set<String>,
        existingCommands: List<CustomCommand>,
    ): Result {
        val bundle =
            try {
                json.decodeFromString(AutomationBundle.serializer(), text)
            } catch (e: SerializationException) {
                return Result.Failed("This isn’t a Droidective automations file: ${e.message}")
            } catch (e: IllegalArgumentException) {
                return Result.Failed("This isn’t a Droidective automations file: ${e.message}")
            }
        if (bundle.format != FORMAT)
            return Result.Failed("This isn’t a Droidective automations file")
        if (bundle.version > VERSION) {
            return Result.Failed("This file is from a newer Droidective. Update the app first.")
        }
        val commandIds = renameCommands(bundle.commands, existingCommands)
        val automationIds = renames(bundle.automations.map { it.id }, existingAutomationIds)
        val commands =
            bundle.commands
                .filterNot { it in existingCommands }
                .map { it.copy(id = commandIds.getValue(it.id)) }
        val automations =
            bundle.automations.map { automation ->
                rewrite(automation, automationIds, commandIds)
                    .copy(id = automationIds.getValue(automation.id), enabled = false)
            }
        return Result.Imported(automations, commands)
    }

    /**
     * Each bundled command's id on this phone. One identical to a command already here reuses it
     * and isn't saved again; one that clashes with a different command gets a fresh id.
     */
    private fun renameCommands(
        bundled: List<CustomCommand>,
        existing: List<CustomCommand>,
    ): Map<String, String> {
        val reused = bundled.filter { it in existing }.associate { it.id to it.id }
        val fresh =
            renames(
                bundled.filterNot { it in existing }.map { it.id },
                existing.map { it.id }.toSet(),
            )
        return fresh + reused.filterKeys { it !in fresh }
    }

    /** New ids for [ids] that clash with [taken] or with each other: `name`, `name-2`, … */
    private fun renames(ids: List<String>, taken: Set<String>): Map<String, String> {
        val used = taken.toMutableSet()
        return ids.associateWith { id ->
            var candidate = id
            var n = 2
            while (candidate in used) candidate = "${id.take(60)}-${n++}"
            used += candidate
            candidate
        }
    }

    private fun rewrite(
        automation: Automation,
        automationIds: Map<String, String>,
        commandIds: Map<String, String>,
    ): Automation {
        fun fix(action: Action): Action =
            when (action) {
                is Action.RunAutomation ->
                    action.copy(
                        automationId = automationIds[action.automationId] ?: action.automationId
                    )
                is Action.RunCommand ->
                    action.copy(commandId = commandIds[action.commandId] ?: action.commandId)
                is Action.Notify ->
                    action.copy(buttons = action.buttons.map { it.copy(action = fix(it.action)) })
                else -> action
            }
        return automation.copy(actions = automation.actions.map(::fix))
    }

    private fun commandIds(automation: Automation): List<String> {
        fun ids(action: Action): List<String> =
            when (action) {
                is Action.RunCommand -> listOf(action.commandId)
                is Action.Notify -> action.buttons.flatMap { ids(it.action) }
                else -> emptyList()
            }
        return automation.actions.flatMap(::ids)
    }
}
