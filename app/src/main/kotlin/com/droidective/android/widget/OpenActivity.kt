package com.droidective.android.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.droidective.android.ui.openSafely

/**
 * Opens an implicit intent for a widget button. Glance gives every activity action a
 * `glance-action:` data URI to keep its PendingIntents apart, and on an implicit intent like the
 * Wi-Fi panel that URI stops it from resolving at all (START_INTENT_NOT_RESOLVED, -91), so the tap
 * does nothing. Glance's URI lands here instead and the real intent goes out untouched.
 *
 * Not exported, and it draws nothing.
 */
class OpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_TARGET, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") // The typed overload arrived in Android 13.
                intent.getParcelableExtra(EXTRA_TARGET)
            }
        target?.let(::openSafely)
        finish()
    }

    companion object {
        private const val EXTRA_TARGET = "com.droidective.android.extra.TARGET"

        /** Wraps [target] when it's implicit; an explicit intent needs no help and goes as is. */
        fun wrap(context: Context, target: Intent): Intent =
            if (target.component != null) target
            else Intent(context, OpenActivity::class.java).putExtra(EXTRA_TARGET, target)
    }
}
