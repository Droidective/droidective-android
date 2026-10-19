package com.droidective.android.surface

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.droidective.android.MainActivity
import com.droidective.android.R
import com.droidective.android.ui.OutcomeText
import com.droidective.android.ui.specialAccessIntent
import com.droidective.core.AccessLevel
import com.droidective.core.commands.CustomCommand
import com.droidective.core.layout.Target
import com.droidective.core.shell.Parse
import com.droidective.core.toggles.Outcome
import com.droidective.core.toggles.ToggleDef

/**
 * A tap from a widget, tile or the notification bar runs in the background, where Android won't let
 * the app open a screen. When that tap needs a person (a stand-in panel, setup, a refusal), this
 * posts a notification whose tap does the job.
 */
object Problems {
    private const val CHANNEL = "problems"

    fun report(context: Context, toggle: ToggleDef, outcome: Outcome) {
        val text = OutcomeText.describe(toggle, outcome) ?: fallbackText(toggle, outcome) ?: return
        val intent = intentFor(context, outcome) ?: return
        post(context, toggle.id, toggle.title, text, intent)
    }

    /** A custom command tapped in the background that couldn't run, or exited non-zero. */
    fun reportCommand(context: Context, command: CustomCommand, result: ShellRuns.Result) {
        val (text, intent) =
            when (result) {
                ShellRuns.Result.NoShell ->
                    "${command.name} needs Shell, which isn’t running." to
                        Launch.intentFor(context, Target.Setup(AccessLevel.Shell))
                is ShellRuns.Result.Failed ->
                    "${command.name} didn’t run: ${result.message}" to
                        Launch.intentFor(context, Target.Tool("custom-commands"))
                is ShellRuns.Result.Ran -> {
                    if (result.result.succeeded) return
                    val reason =
                        Parse.lines(result.result.stderr).firstOrNull { it.isNotBlank() }?.trim()
                            ?: "exit code ${result.result.exitCode}"
                    "${command.name} failed: $reason" to
                        Launch.intentFor(context, Target.Tool("custom-commands"))
                }
            }
        post(context, "command:${command.id}", command.name, text, intent ?: return)
    }

    /** An action tapped in the background that didn't work, with what went wrong. */
    fun reportStep(context: Context, label: String, detail: String?) {
        val intent = Intent(context, MainActivity::class.java)
        post(context, "action:$label", label, detail ?: "It didn’t work.", intent)
    }

    private fun post(context: Context, tag: String, title: String, text: String, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                    CHANNEL,
                    "Toggles that need you",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply {
                    description =
                        "A toggle tapped from a widget or tile that needs a screen to finish"
                }
        )
        val pending =
            PendingIntent.getActivity(
                context,
                tag.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            android.app.Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.sym_toggle_on)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        try {
            manager.notify(tag, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notifications are off for the app; Setup says so, and the widget shows the badge.
        }
    }

    /** UseFallback has no message in the app, where the panel just opens; here it needs one. */
    private fun fallbackText(toggle: ToggleDef, outcome: Outcome): String? =
        (outcome as? Outcome.UseFallback)?.let {
            "${toggle.title} needs Shell, which isn’t running. Tap to use the system screen instead."
        }

    private fun intentFor(context: Context, outcome: Outcome): Intent? =
        when (outcome) {
            is Outcome.Applied -> null
            is Outcome.UseFallback ->
                Launch.intentFor(context, Target.SystemScreen(outcome.fallback.settingsAction))
            is Outcome.NeedsLevel -> Launch.intentFor(context, Target.Setup(outcome.level))
            is Outcome.NeedsSpecialAccess -> specialAccessIntent(context, outcome.access)
            is Outcome.NeedsValue -> Launch.intentFor(context, Target.Tool(outcome.param.featureId))
            is Outcome.DidNotStick,
            is Outcome.Failed -> Intent(context, MainActivity::class.java)
        }

    private const val NOTIFICATION_ID = 1
}
