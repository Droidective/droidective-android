package com.droidective.platform

import android.content.Context
import com.droidective.core.toggles.ToggleDef

/** Whether this phone has the hardware [toggle] drives; a toggle that needs none is everywhere. */
fun Context.hasHardwareFor(toggle: ToggleDef): Boolean =
    toggle.hardware?.let { packageManager.hasSystemFeature(it.feature) } ?: true
