package com.droidective.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.droidective.android.automation.Automations

/**
 * Runs an automation from the Mac or adb: `adb shell am broadcast -n
 * com.droidective.android/.RunAutomation --es id <id>`.
 *
 * Exported, but only the shell gets in: the sender must hold WRITE_SECURE_SETTINGS (the manifest
 * says so), and Android sets [FLAG_RECEIVER_FROM_SHELL] only on what `am` sends and clears it on
 * anything else, so an app granted that permission still can't pretend to be the shell. It's the
 * guard Remote v2 designed for enrollment.
 */
class RunAutomation : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.flags and FLAG_RECEIVER_FROM_SHELL == 0) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        Automations.runNow(context, id, Automations.CAUSE_SHELL)
    }

    companion object {
        const val EXTRA_ID = "id"

        /** `Intent.FLAG_RECEIVER_FROM_SHELL`, which the SDK hides. */
        const val FLAG_RECEIVER_FROM_SHELL = 0x00400000
    }
}
