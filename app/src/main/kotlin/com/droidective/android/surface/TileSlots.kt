package com.droidective.android.surface

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.droidective.android.MainActivity
import com.droidective.android.R
import com.droidective.android.droidective
import com.droidective.android.ui.Symbols
import com.droidective.core.device.Rate
import com.droidective.core.device.RateMeter
import com.droidective.core.layout.Cell
import com.droidective.core.layout.CellIcon
import com.droidective.core.layout.Item
import com.droidective.core.layout.Layout
import com.droidective.core.layout.LayoutKind
import com.droidective.core.layout.Tap
import com.droidective.core.layout.Target
import com.droidective.core.store.JsonFileStore
import com.droidective.core.toggles.Outcome
import com.droidective.core.toggles.Toggles
import com.droidective.platform.TrafficCounters
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Which item each of the twelve tile slots holds, kept in `tile-slots.json`. */
object TileSlots {
    const val COUNT = 12

    private fun store(context: Context) =
        JsonFileStore(
            File(context.filesDir, "tile-slots.json"),
            MapSerializer(Int.serializer(), Item.serializer()),
        ) {
            emptyMap()
        }

    fun item(context: Context, slot: Int): Item? = store(context).load()[slot]

    fun bind(context: Context, slot: Int, item: Item?) {
        val current = store(context).load()
        store(context).save(if (item == null) current - slot else current + (slot to item))
        requestRefresh(context)
    }

    val services: List<Class<out TileService>> =
        listOf(
            TileSlot1::class.java,
            TileSlot2::class.java,
            TileSlot3::class.java,
            TileSlot4::class.java,
            TileSlot5::class.java,
            TileSlot6::class.java,
            TileSlot7::class.java,
            TileSlot8::class.java,
            TileSlot9::class.java,
            TileSlot10::class.java,
            TileSlot11::class.java,
            TileSlot12::class.java,
            LayoutBoundsTile::class.java,
            ShowTapsTile::class.java,
            AnimationScaleTile::class.java,
            DemoModeTile::class.java,
            WirelessDebuggingTile::class.java,
        )

    fun requestRefresh(context: Context) {
        services.forEach { TileService.requestListeningState(context, ComponentName(context, it)) }
    }
}

/**
 * A Quick Settings tile that shows one item. Slots read their item from [TileSlots]; the named
 * developer tiles hold one toggle each.
 */
abstract class ItemTileService : TileService() {
    private var scope: CoroutineScope? = null

    protected abstract fun item(): Item?

    override fun onStartListening() {
        super.onStartListening()
        val listening = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = listening
        listening.launch { draw() }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val item = item() ?: return open(bindIntent())
        val run =
            scope
                ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        run.launch {
            val cell = cell(item) ?: return@launch
            when (val tap = cell.tap) {
                is Tap.RunToggle -> {
                    val toggle = Toggles.byId(tap.toggleId) ?: return@launch
                    val outcome = ToggleTaps.advance(this@ItemTileService, toggle)
                    if (outcome !is Outcome.Applied)
                        Problems.report(this@ItemTileService, toggle, outcome)
                    draw()
                }
                is Tap.RunCommand -> {
                    val command = droidective.commands.get(tap.commandId) ?: return@launch
                    Problems.reportCommand(
                        this@ItemTileService,
                        command,
                        CommandTaps.run(this@ItemTileService, command),
                    )
                    draw()
                }
                is Tap.Open -> Launch.intentFor(this@ItemTileService, tap.target)?.let(::open)
            }
        }
    }

    private suspend fun cell(item: Item): Cell? =
        Surfaces.cells(
                this,
                Layout(id = "tile", kind = LayoutKind.Bar, name = "Tile", items = listOf(item)),
            )
            .firstOrNull()

    private suspend fun draw() {
        val tile = qsTile ?: return
        val cell = item()?.let { cell(it) }
        if (cell == null) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "Choose a toggle"
            tile.icon = Icon.createWithResource(this, Symbols.byName.getValue("toggle_on").outlined)
        } else {
            tile.state = if (cell.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = cell.label
            val symbol = (cell.icon as? CellIcon.Symbol)?.let { Symbols.byName[it.name] }
            tile.icon =
                Icon.createWithResource(
                    this,
                    symbol?.let { if (cell.active) it.filled else it.outlined }
                        ?: Symbols.byName.getValue("toggle_on").outlined,
                )
            if (cell.degraded) tile.subtitle = "Opens a system screen"
        }
        tile.updateTile()
    }

    private fun bindIntent(): Intent =
        Intent(this, MainActivity::class.java)
            .putExtra(Launch.EXTRA_DESTINATION, Launch.DESTINATION_TILES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Opens [intent] and collapses Quick Settings. Android 14 removed the Intent overload. */
// Before Android 14 the Intent overload is the only one there is.
@android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
internal fun TileService.open(intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
    } else {
        @Suppress("DEPRECATION") // The only overload before Android 14.
        startActivityAndCollapse(intent)
    }
}

abstract class SlotTileService(private val slot: Int) : ItemTileService() {
    override fun item(): Item? = TileSlots.item(this, slot)
}

abstract class FixedTileService(private val toggleId: String) : ItemTileService() {
    override fun item(): Item = Item.Toggle(key = toggleId, toggleId = toggleId)
}

class TileSlot1 : SlotTileService(1)

class TileSlot2 : SlotTileService(2)

class TileSlot3 : SlotTileService(3)

class TileSlot4 : SlotTileService(4)

class TileSlot5 : SlotTileService(5)

class TileSlot6 : SlotTileService(6)

class TileSlot7 : SlotTileService(7)

class TileSlot8 : SlotTileService(8)

class TileSlot9 : SlotTileService(9)

class TileSlot10 : SlotTileService(10)

class TileSlot11 : SlotTileService(11)

class TileSlot12 : SlotTileService(12)

class LayoutBoundsTile : FixedTileService("layout-bounds")

class ShowTapsTile : FixedTileService("show-touches")

class AnimationScaleTile : FixedTileService("animation-scale")

class DemoModeTile : FixedTileService("demo-mode")

class WirelessDebuggingTile : FixedTileService("wireless-debugging")

/** Live network speed while Quick Settings is open; a tap opens the Network Speed tool. */
class NetworkSpeedTile : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val listening = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = listening
        listening.launch {
            val meter = RateMeter()
            while (true) {
                val total = TrafficCounters.total()
                val rate = total?.let { (down, up) ->
                    meter.sample(down, up, SystemClock.elapsedRealtime())
                }
                draw(rate, reported = total != null)
                delay(1_000)
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        Launch.intentFor(this, Target.Tool("network-speed"))?.let(::open)
    }

    private fun draw(rate: Rate?, reported: Boolean) {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_ACTIVE
        tile.icon = Icon.createWithResource(this, Symbols.byName.getValue("speed").outlined)
        when {
            !reported -> {
                tile.label = getString(R.string.tile_network_speed)
                tile.subtitle = "Not reported"
            }
            rate == null -> {
                tile.label = getString(R.string.tile_network_speed)
                tile.subtitle = "Measuring…"
            }
            else -> {
                tile.label = "↓ ${RateMeter.format(rate.downPerSecond)}"
                tile.subtitle = "↑ ${RateMeter.format(rate.upPerSecond)}"
            }
        }
        tile.updateTile()
    }
}
