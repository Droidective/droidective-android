package com.droidective.android.surface

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.net.toUri

/**
 * Opens another app from a slot, looked up when it's tapped: a developer uninstalls and reinstalls
 * builds, so the app a slot was made with may be gone, back again, or launching from a renamed
 * activity. When it isn't there, a toast says so and nothing else opens. Shows nothing itself.
 */
class LaunchAppActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName != null) {
            val label = intent.getStringExtra(EXTRA_LABEL) ?: packageName
            open(packageName, intent.getStringExtra(EXTRA_ACTIVITY).orEmpty(), label)
            // A build installed since the slot last drew shows its own icon from now on.
            Surfaces.refreshBehind(this)
        }
        finish()
    }

    private fun open(packageName: String, activityName: String, label: String) {
        val problem =
            when (val found = AppLaunches.find(this, packageName, activityName)) {
                is AppLaunches.Found ->
                    try {
                        startActivity(found.intent)
                        null
                    } catch (_: ActivityNotFoundException) {
                        "$label has no screen to open"
                    } catch (_: SecurityException) {
                        "$label won't let another app open it"
                    }
                AppLaunches.NotInstalled -> "$label isn't installed"
                AppLaunches.Disabled -> "$label is disabled"
                AppLaunches.NothingToOpen -> "$label has no screen to open"
            }
        problem?.let { Toast.makeText(applicationContext, it, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val EXTRA_PACKAGE = "com.droidective.android.extra.APP_PACKAGE"
        private const val EXTRA_ACTIVITY = "com.droidective.android.extra.APP_ACTIVITY"
        private const val EXTRA_LABEL = "com.droidective.android.extra.APP_LABEL"

        fun intent(context: Context, packageName: String, activityName: String, label: String) =
            Intent(context, LaunchAppActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_ACTIVITY, activityName)
                .putExtra(EXTRA_LABEL, label)
                // One per app, so two app slots' PendingIntents aren't taken for the same one.
                .setData("droidective://app/$packageName".toUri())
    }
}

/** What opens an app now, if anything does. */
object AppLaunches {
    sealed interface Result

    data class Found(val intent: Intent) : Result

    data object NotInstalled : Result

    data object Disabled : Result

    data object NothingToOpen : Result

    /**
     * The activity the slot was made with while the app still has it, else whatever the app
     * launches from now: a new build may have moved its launcher activity.
     */
    fun find(context: Context, packageName: String, activityName: String): Result {
        val packages = context.packageManager
        val info =
            try {
                packages.getApplicationInfo(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                return NotInstalled
            }
        if (!info.enabled) return Disabled
        val saved =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(packageName, activityName))
        val intent =
            saved.takeIf { activityName.isNotEmpty() && packages.resolveActivity(it, 0) != null }
                ?: packages.getLaunchIntentForPackage(packageName)
                ?: return NothingToOpen
        return Found(
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        )
    }
}
