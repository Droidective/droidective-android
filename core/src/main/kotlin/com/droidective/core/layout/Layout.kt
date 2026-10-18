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

    @Serializable
    @SerialName("deep-link")
    data class DeepLink(override val key: String, val label: String, val uri: String) : Item

    @Serializable
    @SerialName("settings-screen")
    data class SettingsScreen(override val key: String, val label: String, val action: String) :
        Item
}

/** Where a layout is shown. The kind sets how many items fit. */
@Serializable
enum class LayoutKind(val title: String, val maxItems: Int) {
    @SerialName("bar") Bar("Home-screen bar", maxItems = 30),
    @SerialName("notification") Notification("Notification bar", maxItems = 6),
    @SerialName("folder") Folder("Folder", maxItems = 30),
}

/** ARGB colours and options for how a layout draws. Defaults match MicroToggles' dark bar. */
@Serializable
data class Style(
    val background: Long = 0xF0212322,
    val iconOn: Long = 0xFF6ECC1F,
    val iconOff: Long = 0xFF8D948F,
    val labels: Boolean = false,
)

@Serializable
data class Layout(
    val id: String,
    val kind: LayoutKind,
    val name: String,
    val items: List<Item> = emptyList(),
    val style: Style = Style(),
    val editedAt: Long = 0,
)
