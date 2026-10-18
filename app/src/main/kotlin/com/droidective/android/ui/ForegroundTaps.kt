package com.droidective.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import com.droidective.android.surface.ToggleTaps
import com.droidective.core.toggles.Outcome
import com.droidective.core.toggles.SpecialAccess
import com.droidective.core.toggles.ToggleDef

/**
 * A tap on a screen someone is looking at. Unlike a widget in the background, it can open whatever
 * the outcome needs straight away: the stand-in panel or the special-access page.
 *
 * @return the message to show, or null when the result speaks for itself.
 */
suspend fun runInForeground(context: Context, toggle: ToggleDef): Pair<Outcome, String?> {
    val outcome = ToggleTaps.advance(context, toggle)
    when (outcome) {
        is Outcome.UseFallback -> context.openSafely(Intent(outcome.fallback.settingsAction))
        is Outcome.NeedsSpecialAccess ->
            context.openSafely(specialAccessIntent(context, outcome.access))
        else -> Unit
    }
    return outcome to OutcomeText.describe(toggle, outcome)
}

fun specialAccessIntent(context: Context, access: SpecialAccess): Intent =
    when (access) {
        SpecialAccess.ModifySystemSettings ->
            Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                "package:${context.packageName}".toUri(),
            )
        SpecialAccess.DoNotDisturb -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    }

/** Opens a system screen; OEMs drop some actions, so a missing one falls back to Settings. */
fun Context.openSafely(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
