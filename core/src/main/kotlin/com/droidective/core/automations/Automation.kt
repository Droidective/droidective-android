package com.droidective.core.automations

import com.droidective.core.AccessLevel
import com.droidective.core.apps.AppAction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A trigger, optional conditions and a list of actions. A While automation sets toggles while its
 * state holds and puts them back when it stops holding.
 */
@Serializable
data class Automation(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: Trigger,
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action>,
) {
    val isWhile: Boolean
        get() = trigger is Trigger.While

    companion object {
        val idPattern = Regex("[a-z0-9][a-z0-9-]{0,63}")
    }
}

/** What has to be running for a trigger to reach the app. */
enum class Wake(val title: String) {
    /** Someone runs it: a tap, a shortcut, or the Mac. */
    Tap("When you run it"),
    /** Android starts the app for it, even when it isn't running. */
    Android("Android wakes the app"),
    /** The automation service, a foreground service that runs only while one of these is on. */
    Service("The automation service"),
}

@Serializable
sealed interface Trigger {
    /**
     * What reaches the app when it fires; null for a state that can't trigger, which is refused.
     */
    val wakes: Wake?
    val level: AccessLevel

    fun describe(): String

    /** An event happens; the actions run once. */
    @Serializable
    @SerialName("on")
    data class On(val event: Event) : Trigger {
        override val wakes: Wake?
            get() = event.wakes

        override val level: AccessLevel
            get() = event.level

        override fun describe() = event.describe()
    }

    /** A state starts holding; the actions run once. */
    @Serializable
    @SerialName("starts")
    data class Starts(val state: State) : Trigger {
        override val wakes: Wake?
            get() = state.wakes

        override val level: AccessLevel
            get() = state.level

        override fun describe() = "When ${state.describe(holds = true)}"
    }

    /** A state stops holding; the actions run once. */
    @Serializable
    @SerialName("ends")
    data class Ends(val state: State) : Trigger {
        override val wakes: Wake?
            get() = state.wakes

        override val level: AccessLevel
            get() = state.level

        override fun describe() = "When ${state.describe(holds = false)}"
    }

    /** While a state holds, the toggles stay set; when it ends they go back. */
    @Serializable
    @SerialName("while")
    data class While(val state: State) : Trigger {
        override val wakes: Wake?
            get() = state.wakes

        override val level: AccessLevel
            get() = state.level

        override fun describe() = "While ${state.describe(holds = true)}"
    }
}

/** Something that happens at a moment, with nothing to hold. */
@Serializable
sealed interface Event {
    val wakes: Wake
    val level: AccessLevel
        get() = AccessLevel.App

    fun describe(): String

    /** Run from the app, a widget, a home-screen shortcut, or the Mac. */
    @Serializable
    @SerialName("manual")
    data object Manual : Event {
        override val wakes: Wake
            get() = Wake.Tap

        override fun describe() = "When you run it"
    }

    /**
     * @property minuteOfDay minutes after midnight, local time.
     * @property days the days it runs; every day when all seven are listed.
     */
    @Serializable
    @SerialName("schedule")
    data class Schedule(val minuteOfDay: Int, val days: Set<Day> = Day.entries.toSet()) : Event {
        override val wakes: Wake
            get() = Wake.Android

        override fun describe() = "${Day.describe(days)} at ${clock(minuteOfDay)}"
    }

    @Serializable
    @SerialName("restarted")
    data object Restarted : Event {
        override val wakes: Wake
            get() = Wake.Android

        override fun describe() = "When the phone restarts"
    }

    /** An app is installed or updated; [packageName] null means any app. */
    @Serializable
    @SerialName("app-installed")
    data class AppInstalled(val packageName: String? = null) : Event {
        override val wakes: Wake
            get() = Wake.Service

        override fun describe() =
            if (packageName == null) "When an app is installed or updated"
            else "When $packageName is installed or updated"
    }

    @Serializable
    @SerialName("unlocked")
    data object Unlocked : Event {
        override val wakes: Wake
            get() = Wake.Service

        override fun describe() = "When the phone is unlocked"
    }
}

/**
 * Something that's true for a while. States trigger Starts, Ends and While automations, and are the
 * Only if conditions.
 */
@Serializable
sealed interface State {
    /** What reaches the app when this starts or stops holding; null when it can't trigger. */
    val wakes: Wake?
    val level: AccessLevel
        get() = AccessLevel.App

    /**
     * How long a change has to hold before it counts, so a Bluetooth device reconnecting or a cable
     * wiggling doesn't fire an automation twice.
     */
    val settleMillis: Long
        get() = 0

    fun describe(holds: Boolean): String

    /**
     * On a Wi-Fi network: [network] by name, or any network when null. As a trigger it needs the
     * automation service: Android's PendingIntent network callback says "joined" once and never
     * "left" (see docs/spikes.md), so only a listener that's running sees both.
     */
    @Serializable
    @SerialName("wifi")
    data class OnWifi(val network: String? = null) : State {
        override val wakes: Wake
            get() = Wake.Service

        override val level
            get() = if (network == null) AccessLevel.App else AccessLevel.Shell

        override val settleMillis: Long
            get() = SETTLE_MILLIS

        override fun describe(holds: Boolean): String {
            val where = if (network == null) "Wi-Fi" else "the Wi-Fi network “$network”"
            return if (holds) "on $where" else "off $where"
        }
    }

    /** A Bluetooth device is connected: [device] by name, or any device when null. */
    @Serializable
    @SerialName("bluetooth")
    data class BluetoothConnected(val device: String? = null) : State {
        override val wakes: Wake
            get() = Wake.Android

        override val settleMillis: Long
            get() = SETTLE_MILLIS

        override fun describe(holds: Boolean): String {
            val what = if (device == null) "a Bluetooth device" else "“$device”"
            return if (holds) "$what is connected" else "$what disconnects"
        }
    }

    @Serializable
    @SerialName("app-open")
    data class AppOpen(val packageName: String) : State {
        override val wakes: Wake
            get() = Wake.Service

        override val level: AccessLevel
            get() = AccessLevel.Shell

        override fun describe(holds: Boolean) =
            if (holds) "$packageName is open" else "$packageName closes"
    }

    @Serializable
    @SerialName("charging")
    data object Charging : State {
        override val wakes: Wake
            get() = Wake.Service

        override val settleMillis: Long
            get() = SETTLE_MILLIS

        override fun describe(holds: Boolean) = if (holds) "charging" else "not charging"
    }

    @Serializable
    @SerialName("screen-on")
    data object ScreenOn : State {
        override val wakes: Wake
            get() = Wake.Service

        override fun describe(holds: Boolean) =
            if (holds) "the screen is on" else "the screen is off"
    }

    @Serializable
    @SerialName("headphones")
    data object Headphones : State {
        override val wakes: Wake
            get() = Wake.Service

        override val settleMillis: Long
            get() = SETTLE_MILLIS

        override fun describe(holds: Boolean) =
            if (holds) "headphones are plugged in" else "headphones are unplugged"
    }

    @Serializable
    @SerialName("battery-below")
    data class BatteryBelow(val percent: Int) : State {
        override val wakes: Wake
            get() = Wake.Service

        override val settleMillis: Long
            get() = SETTLE_MILLIS

        override fun describe(holds: Boolean) =
            if (holds) "the battery is below $percent%" else "the battery is at $percent% or more"
    }

    @Serializable
    @SerialName("battery-above")
    data class BatteryAbove(val percent: Int) : State {
        override val wakes: Wake
            get() = Wake.Service

        override val settleMillis: Long
            get() = SETTLE_MILLIS

        override fun describe(holds: Boolean) =
            if (holds) "the battery is above $percent%" else "the battery is at $percent% or less"
    }

    /** A toggle is in [state]. */
    @Serializable
    @SerialName("toggle")
    data class ToggleIs(val toggleId: String, val state: String) : State {
        override val wakes: Wake
            get() = Wake.Service

        override fun describe(holds: Boolean) =
            if (holds) "$toggleId is $state" else "$toggleId isn’t $state"
    }

    /**
     * Between two times of day on [days]. A window that ends before it starts runs past midnight,
     * and belongs to the day it starts on.
     */
    @Serializable
    @SerialName("time")
    data class TimeBetween(
        val startMinute: Int,
        val endMinute: Int,
        val days: Set<Day> = Day.entries.toSet(),
    ) : State {
        override val wakes: Wake
            get() = Wake.Android

        override fun describe(holds: Boolean): String {
            val window = "${clock(startMinute)}–${clock(endMinute)}, ${Day.describe(days)}"
            return if (holds) "it’s $window" else "it’s outside $window"
        }
    }

    /** Shell or root is available. A condition only: nothing announces it changing. */
    @Serializable
    @SerialName("shell")
    data object ShellAvailable : State {
        override val wakes: Wake?
            get() = null

        override fun describe(holds: Boolean) =
            if (holds) "Shell is available" else "Shell isn’t available"
    }

    companion object {
        const val SETTLE_MILLIS = 10_000L
    }
}

/** Only if [state] holds, or only if it doesn't when [negate] is set. */
@Serializable
data class Condition(val state: State, val negate: Boolean = false) {
    fun describe() = "Only if ${state.describe(holds = !negate)}"
}

@Serializable
sealed interface Action {
    /** Sets a toggle to [state]: set, never flip, so firing twice changes nothing more. */
    @Serializable
    @SerialName("set-toggle")
    data class SetToggle(val toggleId: String, val state: String) : Action

    @Serializable
    @SerialName("open-app")
    data class OpenApp(val packageName: String, val label: String) : Action

    @Serializable @SerialName("open-link") data class OpenLink(val uri: String) : Action

    /** Another app's shortcut, such as a chat; [intentUri] is only ever started as an activity. */
    @Serializable
    @SerialName("open-shortcut")
    data class OpenShortcut(val label: String, val intentUri: String) : Action

    @Serializable
    @SerialName("open-settings")
    data class OpenSettings(val label: String, val settingsAction: String) : Action

    @Serializable @SerialName("run-command") data class RunCommand(val commandId: String) : Action

    @Serializable
    @SerialName("app")
    data class ForApp(val packageName: String, val action: AppAction) : Action

    @Serializable
    @SerialName("permission")
    data class Permission(val packageName: String, val permission: String, val grant: Boolean) :
        Action

    /** A notification; each button runs its action when tapped. */
    @Serializable
    @SerialName("notify")
    data class Notify(
        val title: String,
        val text: String = "",
        val buttons: List<Button> = emptyList(),
    ) : Action

    @Serializable @SerialName("wait") data class Wait(val seconds: Int) : Action

    @Serializable
    @SerialName("run-automation")
    data class RunAutomation(val automationId: String) : Action
}

@Serializable data class Button(val label: String, val action: Action)

@Serializable
enum class Day(val short: String, val iso: Int) {
    @SerialName("mon") Monday("Mon", 1),
    @SerialName("tue") Tuesday("Tue", 2),
    @SerialName("wed") Wednesday("Wed", 3),
    @SerialName("thu") Thursday("Thu", 4),
    @SerialName("fri") Friday("Fri", 5),
    @SerialName("sat") Saturday("Sat", 6),
    @SerialName("sun") Sunday("Sun", 7);

    companion object {
        private val weekdays = setOf(Monday, Tuesday, Wednesday, Thursday, Friday)

        fun ofIso(iso: Int): Day = entries.first { it.iso == iso }

        /** "Every day", "Weekdays", "Weekends", or the days in order, such as "Mon, Wed". */
        fun describe(days: Set<Day>): String =
            when (days) {
                entries.toSet() -> "Every day"
                weekdays -> "Weekdays"
                setOf(Saturday, Sunday) -> "Weekends"
                else -> days.sortedBy { it.iso }.joinToString(", ") { it.short }
            }
    }
}

/** "09:41", "23:30": a 24-hour clock, the way the trigger was set. */
internal fun clock(minuteOfDay: Int): String =
    "%02d:%02d".format(java.util.Locale.ROOT, minuteOfDay / 60, minuteOfDay % 60)
