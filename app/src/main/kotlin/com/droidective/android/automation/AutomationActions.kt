package com.droidective.android.automation

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import com.droidective.android.droidective
import com.droidective.android.surface.CommandTaps
import com.droidective.android.surface.Launch
import com.droidective.android.surface.Problems
import com.droidective.android.surface.ShellRuns
import com.droidective.android.surface.ToggleTaps
import com.droidective.android.ui.CommandOutput
import com.droidective.android.ui.OutcomeText
import com.droidective.core.apps.AppActions
import com.droidective.core.apps.ShellStarts
import com.droidective.core.automations.Action
import com.droidective.core.automations.Automation
import com.droidective.core.automations.Cause
import com.droidective.core.automations.Review
import com.droidective.core.automations.Step
import com.droidective.core.shell.ShellCommand
import com.droidective.core.toggles.Outcome
import com.droidective.core.toggles.Toggles

/** Runs one action and says how it went, for History. */
internal class AutomationActions(private val context: Context) {
    private val app = context.droidective

    suspend fun run(action: Action, automation: Automation, chain: Cause): Step {
        val description = Review.describe(action, app.commands::get)
        return try {
            when (action) {
                is Action.SetToggle -> setToggle(action, description, chain.then(automation.id))
                is Action.OpenApp,
                is Action.OpenLink,
                is Action.OpenShortcut,
                is Action.OpenSettings -> open(action, description)
                is Action.RunCommand -> runCommand(action, description)
                is Action.ForApp ->
                    shell(description, AppActions.command(action.action, action.packageName))
                is Action.Permission ->
                    shell(
                        description,
                        AppActions.permission(action.packageName, action.permission, action.grant),
                    )
                is Action.Notify -> {
                    AutomationNotifications.post(context, automation, action)
                    Step(description, ok = true)
                }
                is Action.RunAutomation -> runAutomation(action, automation, chain, description)
                // The runner hands a Wait to a delayed run instead.
                is Action.Wait -> Step(description, ok = true)
            }
        } catch (e: IllegalArgumentException) {
            Step(description, ok = false, e.message)
        }
    }

    private suspend fun setToggle(
        action: Action.SetToggle,
        description: String,
        chain: Cause,
    ): Step {
        val toggle =
            Toggles.byId(action.toggleId)
                ?: return Step(description, ok = false, "There’s no such toggle")
        // Before writing: the service may see the change before this call returns.
        Automations.recentWrites.record(toggle.id, chain, System.currentTimeMillis())
        val outcome = ToggleTaps.set(context, toggle, action.state, refresh = false)
        // The phone can't show a panel from the background; a notification asks for the tap.
        if (outcome !is Outcome.Applied) Problems.report(context, toggle, outcome)
        return Step(description, outcome is Outcome.Applied, OutcomeText.describe(toggle, outcome))
    }

    private suspend fun runCommand(action: Action.RunCommand, description: String): Step {
        val command =
            app.commands.get(action.commandId)
                ?: return Step(description, ok = false, "The command was deleted")
        val result = CommandTaps.execute(context, command)
        val ok = result is ShellRuns.Result.Ran && result.result.succeeded
        return Step(description, ok, CommandOutput.describe(command.name, result).takeUnless { ok })
    }

    private suspend fun shell(description: String, command: ShellCommand): Step {
        val result = ShellRuns.execute(context, listOf(command))
        val ok = result is ShellRuns.Result.Ran && result.result.succeeded
        return Step(description, ok, CommandOutput.describe(description, result).takeUnless { ok })
    }

    private suspend fun runAutomation(
        action: Action.RunAutomation,
        automation: Automation,
        chain: Cause,
        description: String,
    ): Step {
        val request =
            RunRequest(
                action.automationId,
                RunRequest.Kind.Run,
                cause = "Run by “${automation.name}”",
                chain = chain.then(automation.id),
            )
        val record =
            AutomationRunner(context).fire(request)
                ?: return Step(description, ok = false, "It was deleted")
        return Step(description, record.ok, record.skipped)
    }

    /**
     * Android blocks an app that isn't on screen from opening activities. Display over other apps
     * lifts that, and the shell isn't held to it; with neither, a notification opens it on a tap.
     */
    private suspend fun open(action: Action, description: String): Step {
        val intent =
            openIntent(context, action)
                ?: return Step(description, ok = false, "It isn’t installed, or can’t be opened")
        if (Settings.canDrawOverlays(context) || onScreen()) {
            return try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Step(description, ok = true)
            } catch (_: ActivityNotFoundException) {
                Step(description, ok = false, "Nothing on this phone opens it")
            }
        }
        val line = shellStart(action, intent)
        if (line != null && app.access.shell() != null) return shell(description, line)
        AutomationNotifications.postOpen(context, description, intent)
        return Step(
            description,
            ok = false,
            "Android doesn’t let an app open screens from the background, so a notification " +
                "opens it on a tap. Allow Display over other apps to skip that.",
        )
    }

    private fun shellStart(action: Action, intent: Intent): ShellCommand? =
        when (action) {
            is Action.OpenApp ->
                intent.component?.let { ShellStarts.activity(it.packageName, it.className) }
            is Action.OpenLink -> ShellStarts.link(action.uri)
            is Action.OpenSettings -> ShellStarts.settingsScreen(action.settingsAction)
            // Another app's shortcut never runs with the shell's reach.
            else -> null
        }

    private fun onScreen(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    companion object {
        /** The activity an open action starts, or null when nothing here can open it. */
        fun openIntent(context: Context, action: Action): Intent? =
            when (action) {
                is Action.OpenApp ->
                    context.packageManager.getLaunchIntentForPackage(action.packageName)
                is Action.OpenLink -> Intent(Intent.ACTION_VIEW, action.uri.trim().toUri())
                is Action.OpenShortcut -> Launch.shortcutIntent(context, action.intentUri)
                is Action.OpenSettings -> Intent(action.settingsAction)
                else -> null
            }
    }
}
