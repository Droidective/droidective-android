package com.droidective.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.droidective.android.droidective
import com.droidective.android.surface.Surfaces
import kotlinx.coroutines.launch

/**
 * The launcher's word that a widget the app asked it to place is on the home screen: it shows the
 * layout that was asked for. The widget may have drawn first, as that layout or as a starter, so
 * this is the last word.
 */
class PlacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val widgetId =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        val layoutId = intent.getStringExtra(EXTRA_LAYOUT) ?: return
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        if (context.droidective.layouts.get(layoutId) == null) return
        ToggleBarWidget.show(context, widgetId, layoutId)
        val pending = goAsync()
        context.droidective.scope.launch {
            try {
                Surfaces.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_LAYOUT = "com.droidective.android.extra.LAYOUT"

        /** Mutable: the launcher adds the new widget's id. */
        fun callback(context: Context, layoutId: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                layoutId.hashCode(),
                Intent(context, PlacedReceiver::class.java).putExtra(EXTRA_LAYOUT, layoutId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
    }
}
