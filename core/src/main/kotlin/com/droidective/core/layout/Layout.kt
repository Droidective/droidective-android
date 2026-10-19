package com.droidective.core.layout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One thing a slot can hold. Every surface (home-screen bars, folders, the notification bar, tiles,
 * shortcuts and automations) holds the same items, so a layout made for one works in the others.
 *
 * @property key stable within its layout; it names the slot in widget actions and Compose lists.
 */
@Serializable
sealed interface Item {
    val key: String

    @Serializable
    @SerialName("toggle")
    data class Toggle(override val key: String, val toggleId: String) : Item

    @Serializable
    @SerialName("app")
    data class App(
        override val key: String,
        val packageName: String,
        val activityName: String,
        val label: String,
    ) : Item

    /**
     * A shortcut another app handed over through ACTION_CREATE_SHORTCUT, such as a WhatsApp chat.
     * [intentUri] is that app's intent; it is only ever started as an activity.
     */
    @Serializable
    @SerialName("shortcut")
    data class Shortcut(
        override val key: String,
        val label: String,
        val intentUri: String,
        val iconFile: String? = null,
    ) : Item

    @Serializable
    @SerialName("folder")
    data class Folder(override val key: String, val folderId: String) : Item

    /** A saved custom command: a button, or a toggle when the command has an off line. */
    @Serializable
    @SerialName("command")
    data class Command(override val key: String, val commandId: String) : Item

    @Serializable
    @SerialName("deep-link")
    data class DeepLink(override val key: String, val label: String, val uri: String) : Item

    @Serializable
    @SerialName("settings-screen")
    data class SettingsScreen(override val key: String, val label: String, val action: String) :
        Item

    /** Runs one of the person's automations now, whatever its trigger. */
    @Serializable
    @SerialName("automation")
    data class Automation(override val key: String, val automationId: String) : Item

    /**
     * One automation action on its own, such as setting a toggle to one state, force-stopping an
     * app or granting a permission. [label] is what the slot says.
     */
    @Serializable
    @SerialName("action")
    data class Action(
        override val key: String,
        val action: com.droidective.core.automations.Action,
        val label: String,
    ) : Item

    /** Opens one of Droidective's tools, such as Logcat or Current activity. */
    @Serializable
    @SerialName("tool")
    data class Tool(override val key: String, val featureId: String) : Item
}

/** Where a layout is shown. The kind sets how many items fit. */
@Serializable
enum class LayoutKind(val title: String, val maxItems: Int) {
    @SerialName("bar") Bar("Home-screen widget", maxItems = 30),
    @SerialName("notification") Notification("Notification bar", maxItems = 6),
    @SerialName("folder") Folder("Folder", maxItems = 30),
}

/** How a widget lays its slots out: a grid of icons, or a list of rows that scrolls. */
@Serializable
enum class Display(val title: String) {
    @SerialName("grid") Grid("Grid"),
    @SerialName("list") List("List"),
}

/** What sits behind each icon: nothing, or a chip that fills in while the slot is on. */
@Serializable
enum class CellShape(val title: String) {
    @SerialName("none") None("None"),
    @SerialName("circle") Circle("Circle"),
    @SerialName("rounded") Rounded("Rounded"),
}

/**
 * ARGB colours and options for how a layout draws. The defaults are MicroToggles' dark bar, and
 * every field added later defaults to how layouts drew before it, so old files look the same.
 *
 * @property columns a fixed number of columns, or 0 for as many as fit the widget's width.
 * @property labelColor the labels' colour, or null for [iconOff].
 * @property stateText says each slot's state ("On", "0.5×") under its label.
 * @property dynamicColors takes the colours from the wallpaper (Android 12+) instead of these.
 */
@Serializable
data class Style(
    val background: Long = 0xF0212322,
    val iconOn: Long = 0xFF6ECC1F,
    val iconOff: Long = 0xFF8D948F,
    val labels: Boolean = false,
    val display: Display = Display.Grid,
    val columns: Int = 0,
    val iconSize: Int = 28,
    val cornerRadius: Int = 8,
    val padding: Int = 4,
    val cellShape: CellShape = CellShape.None,
    val labelSize: Int = 10,
    val labelColor: Long? = null,
    val stateText: Boolean = false,
    val dynamicColors: Boolean = false,
) {
    /** The style with every number inside what a widget can draw, for a hand-edited file. */
    fun clamped(): Style =
        copy(
            columns = columns.coerceIn(0, MAX_COLUMNS),
            iconSize = iconSize.coerceIn(ICON_SIZES.first, ICON_SIZES.last),
            cornerRadius = cornerRadius.coerceIn(CORNERS.first, CORNERS.last),
            padding = padding.coerceIn(PADDINGS.first, PADDINGS.last),
            labelSize = labelSize.coerceIn(LABEL_SIZES.first, LABEL_SIZES.last),
        )

    companion object {
        const val MAX_COLUMNS = 8
        val ICON_SIZES = 16..64
        val CORNERS = 0..32
        val PADDINGS = 0..16
        val LABEL_SIZES = 8..16
    }
}

/** One slot's own look: a label, a symbol or an "on" colour of its own; null keeps the default. */
@Serializable
data class Look(val label: String? = null, val icon: String? = null, val color: Long? = null) {
    val isEmpty: Boolean
        get() = label == null && icon == null && color == null
}

/** @property looks each slot's own look, by item key. */
@Serializable
data class Layout(
    val id: String,
    val kind: LayoutKind,
    val name: String,
    val items: List<Item> = emptyList(),
    val style: Style = Style(),
    val editedAt: Long = 0,
    val looks: Map<String, Look> = emptyMap(),
)
