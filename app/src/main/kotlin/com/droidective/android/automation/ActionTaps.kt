package com.droidective.android.automation

import android.content.Context
import com.droidective.android.surface.Surfaces
import com.droidective.core.automations.Action
import com.droidective.core.automations.Automation
import com.droidective.core.automations.Cause
import com.droidective.core.automations.Event
import com.droidective.core.automations.Step
import com.droidective.core.automations.Trigger

/**
 * One automation action run from a slot: a widget, a tile, the notification bar or a folder. It
 * runs through the automation engine, so it does exactly what the same action does in an
 * automation, and every surface redraws after.
 */
object ActionTaps {
    private const val SLOT_ID = "slot"

    suspend fun run(context: Context, action: Action, label: String): Step {
        val slot =
            Automation(
                id = SLOT_ID,
                name = label,
                trigger = Trigger.On(Event.Manual),
                actions = listOf(action),
            )
        val step = AutomationActions(context).run(action, slot, Cause())
        Surfaces.refresh(context)
        return step
    }
}
