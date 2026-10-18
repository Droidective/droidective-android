package com.droidective.android.surface

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.droidective.android.R
import com.droidective.android.droidective
import com.droidective.android.ui.Symbols
import com.droidective.android.widget.AppIcons
import com.droidective.core.layout.Cell
import com.droidective.core.layout.CellIcon
import com.droidective.core.layout.LayoutKind
import com.droidective.core.layout.Tap
import com.droidective.core.toggles.Outcome
import com.droidective.core.toggles.Toggles
import kotlinx.coroutines.launch

/**
 * Notification-bar layouts, each posted as an ongoing notification with a row of toggles. No
 * foreground service backs it: Play accepts one only while it's needed, and on Android 14+ people
 * can swipe the notification away regardless. It comes back whenever the app refreshes it.
 */
object NotificationBar {
    private const val CHANNEL = "bar"
    private const val TAG = "bar"
    private val slots =
        listOf(R.id.slot0, R.id.slot1, R.id.slot2, R.id.slot3, R.id.slot4, R.id.slot5)

    suspend fun refresh(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Notification bar", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Your notification-bar toggles"
                    setShowBadge(false)
                }
        )
        val layouts =
            context.droidective.layouts.all().filter { it.kind == LayoutKind.Notification }
        val live = layouts.map { it.id }.toSet()
        manager.activeNotifications
            .filter { it.tag?.startsWith("$TAG:") == true && it.tag.removePrefix("$TAG:") !in live }
            .forEach { manager.cancel(it.tag, it.id) }
        layouts.forEach { layout ->
            val cells = Surfaces.cells(context, layout).take(slots.size)
            val notification =
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.sym_toggle_on)
                    .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                    .setCustomContentView(
                        views(context, layout.style.iconOn, layout.style.iconOff, cells)
                    )
                    .setOngoing(true)
                    .setSilent(true)
                    .setShowWhen(false)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
            try {
                manager.notify("$TAG:${layout.id}", 0, notification)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS was revoked between the check and the post.
            }
        }
    }

    private fun views(context: Context, on: Long, off: Long, cells: List<Cell>): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_bar)
        slots.forEachIndexed { index, slot ->
            val cell = cells.getOrNull(index)
            if (cell == null) {
                views.setViewVisibility(slot, android.view.View.GONE)
                return@forEachIndexed
            }
            views.setViewVisibility(slot, android.view.View.VISIBLE)
            views.setContentDescription(slot, cell.label)
            when (val icon = cell.icon) {
                is CellIcon.Symbol -> {
                    val symbol = Symbols.byName[icon.name] ?: Symbols.byName.getValue("toggle_on")
                    views.setImageViewResource(
                        slot,
                        if (cell.active) symbol.filled else symbol.outlined,
                    )
                    views.setInt(slot, "setColorFilter", (if (cell.active) on else off).toInt())
                }
                is CellIcon.AppIcon ->
                    views.setImageViewBitmap(slot, AppIcons.bitmap(context, icon.packageName))
                is CellIcon.File ->
                    views.setImageViewBitmap(slot, AppIcons.file(context, icon.path))
            }
            pendingFor(context, cell)?.let { views.setOnClickPendingIntent(slot, it) }
        }
        return views
    }

    /** Toggles go to a receiver; screens open straight from the tap, never through a trampoline. */
    private fun pendingFor(context: Context, cell: Cell): PendingIntent? =
        when (val tap = cell.tap) {
            is Tap.RunToggle ->
                PendingIntent.getBroadcast(
                    context,
                    tap.toggleId.hashCode(),
                    Intent(context, NotificationTapReceiver::class.java)
                        .putExtra(NotificationTapReceiver.EXTRA_TOGGLE_ID, tap.toggleId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            is Tap.RunCommand ->
                PendingIntent.getBroadcast(
                    context,
                    "command:${tap.commandId}".hashCode(),
                    Intent(context, NotificationTapReceiver::class.java)
                        .putExtra(NotificationTapReceiver.EXTRA_COMMAND_ID, tap.commandId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            is Tap.Open ->
                Launch.intentFor(context, tap.target)?.let { intent ->
                    PendingIntent.getActivity(
                        context,
                        cell.key.hashCode(),
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }
        }
}

/** Runs a toggle tapped in the notification bar. Not exported: only our PendingIntents reach it. */
class NotificationTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val toggle = intent.getStringExtra(EXTRA_TOGGLE_ID)?.let { Toggles.byId(it) }
        val command =
            intent.getStringExtra(EXTRA_COMMAND_ID)?.let { context.droidective.commands.get(it) }
        if (toggle == null && command == null) return
        val pending = goAsync()
        context.droidective.scope.launch {
            try {
                if (toggle != null) {
                    val outcome = ToggleTaps.advance(context, toggle)
                    if (outcome !is Outcome.Applied) Problems.report(context, toggle, outcome)
                }
                if (command != null)
                    Problems.reportCommand(context, command, CommandTaps.run(context, command))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TOGGLE_ID = "com.droidective.android.extra.TOGGLE_ID"
        const val EXTRA_COMMAND_ID = "com.droidective.android.extra.COMMAND_ID"
    }
}
