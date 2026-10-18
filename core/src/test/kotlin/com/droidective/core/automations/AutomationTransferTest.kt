package com.droidective.core.automations

import com.droidective.core.apps.AppAction
import com.droidective.core.commands.CustomCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AutomationTransferTest {
    private val reset = CustomCommand("cmd-reset", "Reset", "pm clear com.example.app")
    private val vpn = CustomCommand("cmd-vpn", "VPN", "svc vpn up", offCommand = "svc vpn down")

    private val onboarding =
        Automation(
            "reset-onboarding",
            "Reset onboarding",
            trigger = Trigger.On(Event.Manual),
            actions =
                listOf(
                    Action.RunCommand("cmd-reset"),
                    Action.OpenLink("myapp://onboarding"),
                    Action.RunAutomation("bedtime"),
                ),
        )
    private val bedtime =
        Automation(
            "bedtime",
            "Bedtime",
            trigger = Trigger.On(Event.Schedule(23 * 60 + 30)),
            actions = listOf(Action.SetToggle("battery-saver", "on")),
        )

    private fun export() =
        AutomationTransfer.export(
            listOf(onboarding, bedtime),
            mapOf(reset.id to reset, vpn.id to vpn)::get,
        )

    @Test
    fun anExportCarriesOnlyTheCommandsItRuns() {
        val text = export()
        assertTrue("svc vpn up" !in text)
        assertTrue("pm clear com.example.app" in text)
        assertTrue("\"format\": \"droidective-automations\"" in text)
    }

    @Test
    fun importsArriveSwitchedOff() {
        val result = AutomationTransfer.import(export(), emptySet(), emptyList())
        assertIs<AutomationTransfer.Result.Imported>(result)
        assertTrue(result.automations.none { it.enabled })
        assertEquals(listOf(reset), result.commands)
        assertEquals(listOf("reset-onboarding", "bedtime"), result.automations.map { it.id })
    }

    @Test
    fun clashingIdsAreRenamedAndReferencesFollow() {
        val different = reset.copy(command = "reboot")
        val result =
            AutomationTransfer.import(
                export(),
                existingAutomationIds = setOf("bedtime"),
                existingCommands = listOf(different),
            )
        assertIs<AutomationTransfer.Result.Imported>(result)
        assertEquals(listOf("reset-onboarding", "bedtime-2"), result.automations.map { it.id })
        assertEquals(listOf("cmd-reset-2"), result.commands.map { it.id })
        assertEquals(
            listOf(
                Action.RunCommand("cmd-reset-2"),
                Action.OpenLink("myapp://onboarding"),
                Action.RunAutomation("bedtime-2"),
            ),
            result.automations.first().actions,
        )
    }

    @Test
    fun aCommandAlreadyHereIsReusedNotCopied() {
        val result =
            AutomationTransfer.import(export(), emptySet(), existingCommands = listOf(reset))
        assertIs<AutomationTransfer.Result.Imported>(result)
        assertEquals(emptyList(), result.commands)
        assertEquals(Action.RunCommand("cmd-reset"), result.automations.first().actions.first())
    }

    @Test
    fun otherFilesAreRefusedWithAReason() {
        listOf(
                "not json",
                """{"format":"something-else","version":1,"automations":[]}""",
                """{"format":"droidective-automations","version":1,"automations":[{"id":"x"}]}""",
            )
            .forEach { text ->
                val result = AutomationTransfer.import(text, emptySet(), emptyList())
                assertIs<AutomationTransfer.Result.Failed>(result, text)
                assertTrue("Droidective automations file" in result.reason, result.reason)
            }
        val newer =
            AutomationTransfer.import(
                """{"format":"droidective-automations","version":2,"automations":[]}""",
                emptySet(),
                emptyList(),
            )
        assertIs<AutomationTransfer.Result.Failed>(newer)
        assertTrue("newer Droidective" in newer.reason)
    }

    @Test
    fun theReviewSpellsOutEveryShellCommand() {
        val automation =
            Automation(
                "review",
                "Review",
                trigger = Trigger.On(Event.Manual),
                actions =
                    listOf(
                        Action.SetToggle("wifi", "off"),
                        Action.SetToggle("stay-awake", "on"),
                        Action.SetToggle("dark-mode", "on"),
                        Action.RunCommand("cmd-vpn"),
                        Action.ForApp("com.example.app", AppAction.ClearData),
                        Action.Permission(
                            "com.example.app",
                            "android.permission.CAMERA",
                            grant = false,
                        ),
                        Action.RunCommand("gone"),
                    ),
            )
        val lines = Review.lines(automation, mapOf(vpn.id to vpn)::get)
        assertEquals(
            listOf(
                ReviewLine("Wi-Fi: Off", listOf("svc wifi disable"), destructive = false),
                ReviewLine(
                    "Stay awake while charging: On",
                    listOf("settings put global stay_on_while_plugged_in '7'"),
                    destructive = false,
                ),
                ReviewLine("Dark theme: On", lines[2].shell, destructive = false),
                ReviewLine("Run “VPN”", listOf("svc vpn up", "svc vpn down"), destructive = false),
                ReviewLine(
                    "Clear data: com.example.app",
                    listOf("pm clear 'com.example.app'"),
                    destructive = true,
                ),
                ReviewLine(
                    "Revoke android.permission.CAMERA from com.example.app",
                    listOf("pm revoke 'com.example.app' 'android.permission.CAMERA'"),
                    destructive = true,
                ),
                ReviewLine(
                    "Run a command that isn’t on this phone",
                    emptyList(),
                    destructive = false,
                ),
            ),
            lines,
        )
    }
}
