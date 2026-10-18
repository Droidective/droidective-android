package com.droidective.android.automation

import android.app.Activity
import android.os.Bundle

/**
 * What a home-screen shortcut opens: it runs the automation and closes, without drawing anything.
 * Not exported; the launcher starts a pinned shortcut as this app, so it doesn't need to be.
 */
class RunAutomationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_ID)?.let { id ->
            Automations.runNow(this, id, "From a home-screen shortcut")
        }
        finish()
    }

    companion object {
        const val EXTRA_ID = "com.droidective.android.extra.AUTOMATION_ID"
    }
}
