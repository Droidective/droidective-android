package com.droidective.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.droidective.android.droidective
import com.droidective.android.surface.Copies
import com.droidective.android.surface.Surfaces
import com.droidective.android.surface.TileSlots
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.copy
import com.droidective.core.layout.Item
import com.droidective.core.network.NetworkCard
import com.droidective.core.network.NetworkCards
import com.droidective.core.network.NetworkNow
import com.droidective.core.network.Transport
import com.droidective.core.store.JsonFileStore
import com.droidective.platform.NetworkReader
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * A home-screen widget with the phone's network: the Wi-Fi name, its address here and the address
 * servers see, as each widget's settings choose. A tap copies an address.
 */
class NetworkWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val card = NetworkWidgets.card(context, widgetId)
        val now = NetworkWidgets.read(context, card)
        provideContent { GlanceTheme { NetworkCardView(now, card) } }
    }
}

/** The widget as it draws: the network's name, then each address, the first largest. */
@Composable
private fun NetworkCardView(now: NetworkNow, card: NetworkCard) {
    val colors = GlanceTheme.colors
    val lines = NetworkCards.lines(now, card)
    // One cell tall has room for the first address alone.
    val roomy = LocalSize.current.height >= ROOMY_HEIGHT
    val copied = NetworkCards.copied(now, card)
    Column(
        GlanceModifier.fillMaxSize()
            .background(colors.widgetBackground)
            .cornerRadius(CORNER)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clickable(
                actionRunCallback<CopyAddressAction>(
                    actionParametersOf(CopyAddressAction.addressKey to copied.orEmpty())
                )
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (card.showName && roomy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    ImageProvider(Symbols.byName.getValue(symbolOf(now.transport)).outlined),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colors.primary),
                    modifier = GlanceModifier.size(16.dp),
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    NetworkCards.title(now),
                    maxLines = 1,
                    style =
                        TextStyle(
                            color = colors.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                )
            }
            Spacer(GlanceModifier.height(4.dp))
        }
        lines
            .filter { roomy || it.main }
            .forEach { line ->
                if (line.main) {
                    Text(
                        line.value,
                        maxLines = 1,
                        style =
                            TextStyle(
                                color = colors.onSurface,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            ),
                    )
                } else {
                    // Its own line under a small label: a public IPv6 address is long.
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        line.label.uppercase(),
                        style =
                            TextStyle(
                                color = colors.onSurfaceVariant,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                    )
                    Text(
                        line.value,
                        maxLines = 1,
                        style =
                            TextStyle(
                                color = colors.onSurface,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                    )
                }
            }
    }
}

internal fun symbolOf(transport: Transport): String =
    when (transport) {
        Transport.Wifi -> "wifi"
        Transport.Cellular -> "signal_cellular_alt"
        Transport.Ethernet -> "lan"
        Transport.Vpn -> "vpn_key"
        Transport.Other -> "public"
        Transport.None -> "wifi_off"
    }

/** Copies the address a widget or a slot showed, then redraws them with what's true now. */
class CopyAddressAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val address = parameters[addressKey].orEmpty()
        if (address.isNotEmpty()) Copies.copy(context, address)
        NetworkWidgets.refresh(context)
        Surfaces.refresh(context)
    }

    companion object {
        val addressKey = ActionParameters.Key<String>("address")
    }
}

/** The network widgets in the launcher's list. Removing one forgets its settings. */
class NetworkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NetworkWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        NetworkWidgets.watch(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        NetworkWidgets.unwatch(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        NetworkWidgets.forget(context, appWidgetIds.toList())
    }
}

/** Android's word that the phone joined a network: the widgets redraw with its name and address. */
class NetworkChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.droidective.scope.launch {
            try {
                NetworkWidgets.refresh(context)
                // Network slots on bars, notification bars and tiles say the new address too.
                Surfaces.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Each network widget's settings, by widget id, and keeping them up to date. */
object NetworkWidgets {
    private fun store(context: Context) =
        JsonFileStore(
            File(context.filesDir, FILE),
            MapSerializer(String.serializer(), NetworkCard.serializer()),
        ) {
            emptyMap()
        }

    fun card(context: Context, widgetId: Int): NetworkCard =
        store(context).load()[widgetId.toString()] ?: NetworkCard()

    fun save(context: Context, widgetId: Int, card: NetworkCard) {
        val kept = store(context)
        kept.save(kept.load() + (widgetId.toString() to card))
    }

    fun forget(context: Context, widgetIds: List<Int>) {
        val kept = store(context)
        kept.save(kept.load() - widgetIds.map(Int::toString).toSet())
    }

    /** Every placed network widget. */
    fun placedIds(context: Context): List<Int> =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, NetworkWidgetReceiver::class.java))
            .toList()

    /**
     * The network now, as [card] shows it: the Wi-Fi name through the shell once access settles.
     */
    suspend fun read(context: Context, card: NetworkCard): NetworkNow {
        val access = context.droidective.access
        access.settled()
        return NetworkReader.now(context, public = card.showPublic, shell = access.shell())
    }

    suspend fun refresh(context: Context) = NetworkWidget().updateAll(context)

    /**
     * Asks Android to say whenever the phone joins a network, even with the app stopped, so a
     * network widget or slot follows the phone from Wi-Fi to mobile data and back. Asking again
     * replaces it.
     */
    fun watch(context: Context) {
        val slots =
            context.droidective.layouts.all().any { layout ->
                layout.items.any { it is Item.Network }
            } || TileSlots.all(context).values.any { it is Item.Network }
        if (placedIds(context).isEmpty() && !slots) return
        val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        context
            .getSystemService(ConnectivityManager::class.java)
            .registerNetworkCallback(request, changes(context))
    }

    fun unwatch(context: Context) {
        context
            .getSystemService(ConnectivityManager::class.java)
            .unregisterNetworkCallback(changes(context))
    }

    // Mutable: Android adds the network to it.
    private fun changes(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, NetworkChangedReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    private const val FILE = "network-widgets.json"
}

private val ROOMY_HEIGHT = 80.dp
private val CORNER = 20.dp
