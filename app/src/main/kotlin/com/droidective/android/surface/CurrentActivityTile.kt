package com.droidective.android.surface

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.droidective.android.droidective
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.usageAccessIntent
import com.droidective.core.apps.CurrentActivity
import com.droidective.core.shell.Parse
import com.droidective.platform.ForegroundApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What looking for the activity in front found. */
sealed interface ActivityLookup {
    data class Found(val component: String) : ActivityLookup

    /** Neither usage access nor Shell. */
    data object NoAccess : ActivityLookup

    data class Failed(val message: String) : ActivityLookup
}

/** Which activity is in front: the Mac's Current Activity, for the tile and the tool. */
object CurrentActivities {
    /**
     * Usage access first, which costs nothing and needs no Shell; then the shell's `dumpsys
     * activity`. [except] skips a package in the usage history, which only that path can do.
     */
    suspend fun find(context: Context, except: String? = null): ActivityLookup {
        ForegroundApp.activityFromUsage(context, except)?.let {
            return ActivityLookup.Found(it)
        }
        if (except != null) return ActivityLookup.NoAccess
        return when (val run = ShellRuns.execute(context, listOf(CurrentActivity.command))) {
            ShellRuns.Result.NoShell -> ActivityLookup.NoAccess
            is ShellRuns.Result.Failed -> ActivityLookup.Failed(run.message)
            is ShellRuns.Result.Ran ->
                Parse.resumedActivity(run.result.stdout)?.let { ActivityLookup.Found(it) }
                    ?: ActivityLookup.Failed("dumpsys didn't say which activity is in front.")
        }
    }

    fun copy(context: Context, component: String) {
        context
            .getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Current activity", component))
    }
}

/**
 * Quick Settings' "Current activity": pull the shade down over any app and tap it to see which
 * activity is on screen, with a button to copy it. The shade doesn't pause the app under it.
 */
class CurrentActivityTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, Symbols.byName.getValue("layers").outlined)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        droidective.scope.launch {
            val found = CurrentActivities.find(this@CurrentActivityTile)
            withContext(Dispatchers.Main) { showDialog(dialog(found)) }
        }
    }

    private fun dialog(found: ActivityLookup): AlertDialog {
        val builder =
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Current activity")
                .setNegativeButton("Close", null)
        when (found) {
            is ActivityLookup.Found ->
                builder.setMessage(found.component).setPositiveButton("Copy") { _, _ ->
                    CurrentActivities.copy(this, found.component)
                }
            ActivityLookup.NoAccess ->
                builder
                    .setMessage(
                        "Droidective needs Usage access, or Shell through Shizuku or root, to " +
                            "see which activity is on screen."
                    )
                    .setPositiveButton("Allow Usage access") { _, _ ->
                        open(usageAccessIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
            is ActivityLookup.Failed -> builder.setMessage(found.message)
        }
        return builder.create()
    }
}
