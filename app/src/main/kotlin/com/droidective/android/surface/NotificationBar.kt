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

    /** Beside each slot, the text it shows in place of its icon when its text is the point. */
    private val texts =
        listOf(R.id.text0, R.id.text1, R.id.text2, R.id.text3, R.id.text4, R.id.text5)

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
            val text = texts[index]
            views.setViewVisibility(text, android.view.View.GONE)
            if (cell == null) {
                views.setViewVisibility(slot, android.view.View.GONE)
                return@forEachIndexed
            }
            if (cell.readout) {
                views.setViewVisibility(slot, android.view.View.GONE)
                views.setViewVisibility(text, android.view.View.VISIBLE)
                views.setTextViewText(text, cell.label)
                pendingFor(context, cell)?.let { views.setOnClickPendingIntent(text, it) }
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
                    views.setInt(slot, "setColorFilter", cell.tint(on, off).toInt())
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

    /**
     * What runs in place goes to a receiver; screens open straight from the tap, never through a
     * trampoline.
     */
    private fun pendingFor(context: Context, cell: Cell): PendingIntent? =
        when (val tap = cell.tap) {
            is Tap.RunToggle ->
                broadcast(context, "toggle:${tap.toggleId}") {
                    putExtra(NotificationTapReceiver.EXTRA_TOGGLE_ID, tap.toggleId)
                }
            is Tap.RunCommand ->
                broadcast(context, "command:${tap.commandId}") {
                    putExtra(NotificationTapReceiver.EXTRA_COMMAND_ID, tap.commandId)
                }
            is Tap.RunAutomation ->
                broadcast(context, "automation:${tap.automationId}") {
                    putExtra(NotificationTapReceiver.EXTRA_AUTOMATION_ID, tap.automationId)
                }
            is Tap.RunAction ->
                broadcast(context, "action:${cell.key}") {
                    putExtra(NotificationTapReceiver.EXTRA_ACTION, SlotActions.encode(tap.action))
                    putExtra(NotificationTapReceiver.EXTRA_LABEL, tap.label)
                }
            is Tap.Copy ->
                broadcast(context, "copy:${cell.key}") {
                    putExtra(NotificationTapReceiver.EXTRA_COPY, tap.text)
                }
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

    /** A tap for [NotificationTapReceiver]; [id] keeps each slot's PendingIntent its own. */
    private fun broadcast(context: Context, id: String, extras: Intent.() -> Unit): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            Intent(context, NotificationTapReceiver::class.java).apply(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** Runs what's tapped in the notification bar. Not exported: only our PendingIntents reach it. */
class NotificationTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tap = tapFrom(intent) ?: return
        val pending = goAsync()
        context.droidective.scope.launch {
            try {
                BackgroundTaps.run(context, tap)
            } finally {
                pending.finish()
            }
        }
    }

    private fun tapFrom(intent: Intent): Tap? {
        intent.getStringExtra(EXTRA_TOGGLE_ID)?.let {
            return Tap.RunToggle(it)
        }
        intent.getStringExtra(EXTRA_COMMAND_ID)?.let {
            return Tap.RunCommand(it)
        }
        intent.getStringExtra(EXTRA_AUTOMATION_ID)?.let {
            return Tap.RunAutomation(it)
        }
        intent.getStringExtra(EXTRA_COPY)?.let {
            return Tap.Copy(it)
        }
        val action = intent.getStringExtra(EXTRA_ACTION)?.let(SlotActions::decode) ?: return null
        return Tap.RunAction(action, intent.getStringExtra(EXTRA_LABEL).orEmpty())
    }

    companion object {
        const val EXTRA_TOGGLE_ID = "com.droidective.android.extra.TOGGLE_ID"
        const val EXTRA_COMMAND_ID = "com.droidective.android.extra.COMMAND_ID"
        const val EXTRA_AUTOMATION_ID = "com.droidective.android.extra.AUTOMATION_ID"
        const val EXTRA_ACTION = "com.droidective.android.extra.ACTION"
        const val EXTRA_LABEL = "com.droidective.android.extra.LABEL"
        const val EXTRA_COPY = "com.droidective.android.extra.COPY"
    }
}
