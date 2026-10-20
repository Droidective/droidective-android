package com.droidective.core.toggles

import com.droidective.core.AccessLevel
import com.droidective.core.shell.ShellCommand

enum class ToggleGroup(val title: String) {
    Network("Network"),
    Sound("Sound"),
    Display("Display"),
    Device("Device"),
    Developer("Developer"),
}

/** Where a Settings provider key lives, which decides who may write it. */
enum class SettingScope(val namespace: String, val writeLevel: AccessLevel) {
    Global("global", AccessLevel.Granted),
    Secure("secure", AccessLevel.Granted),
    /** System keys any app may write once the user allows Modify system settings. */
    SystemPublic("system", AccessLevel.App),
    /**
     * System keys Android keeps from apps even with WRITE_SECURE_SETTINGS, such as `show_touches`.
     * Only the shell writes them.
     */
    SystemPrivate("system", AccessLevel.Shell),
}

/**
 * Hardware a toggle drives, named by its PackageManager feature. A phone without it hides the
 * toggle: a tablet with no modem has no mobile data to switch.
 */
enum class Hardware(val feature: String) {
    Wifi("android.hardware.wifi"),
    Bluetooth("android.hardware.bluetooth"),
    Nfc("android.hardware.nfc"),
    Telephony("android.hardware.telephony"),
    CameraFlash("android.hardware.camera.flash"),
}

/** One position a toggle can be in. A toggle with a single state is an action. */
data class ToggleState(val id: String, val label: String)

/**
 * Android APIs the platform module implements by name, for toggles that are neither a setting nor a
 * shell command.
 *
 * @property opensScreen the toggle shows UI, so a widget or tile launches it as an activity instead
 *   of running it in the background.
 */
enum class PlatformApi(val opensScreen: Boolean = false) {
    WifiEnabled,
    MobileData,
    AirplaneMode,
    BluetoothEnabled,
    BluetoothDiscoverable(opensScreen = true),
    NfcEnabled,
    AutoSync,
    SyncNow,
    MediaVolume,
    RingerMode,
    DoNotDisturb,
    BrightnessSlider(opensScreen = true),
    KeepScreenOn,
    Flashlight,
    DarkTheme,
    DataSaver,
    BatterySaver,
    DemoMode,
    SwitchKeyboard,

    /** The app's own Screen Record, started and stopped like a switch. */
    ScreenRecording,

    /** The Performance Monitor's overlay over other apps. */
    PerformanceOverlay,

    /** A UI Inspector capture of whatever is on screen in a few seconds. */
    CaptureViews,
}

/** How a toggle's current state is read. */
sealed interface Probe {
    /** A settings key, read through the Settings API; [parse] maps the stored value to a state. */
    data class Setting(val scope: SettingScope, val key: String, val parse: (String?) -> String?) :
        Probe

    /** A command and a pure parser from its output to a state id. */
    data class Command(
        val command: ShellCommand,
        val parse: (String) -> String?,
        val level: AccessLevel = AccessLevel.App,
    ) : Probe

    data class Api(val api: PlatformApi) : Probe

    /** Actions have no state to read. */
    data object None : Probe
}

/** How a toggle changes state, and therefore the level it needs. */
sealed interface Write {
    val level: AccessLevel

    /** Writes every key in `values[state]`. Global and Secure keys fall back to the shell. */
    data class Settings(val scope: SettingScope, val values: Map<String, Map<String, String>>) :
        Write {
        override val level: AccessLevel
            get() = scope.writeLevel
    }

    /**
     * Runs `commands[state]` in order, as the shell user, or as root for [AccessLevel.Root]. A
     * `{name}` in a line is replaced with that parameter, shell-quoted.
     */
    data class Commands(
        val commands: Map<String, List<ShellCommand>>,
        override val level: AccessLevel = AccessLevel.Shell,
    ) : Write

    /**
     * For steps relative to the phone's own value, such as display density: runs [probe], then the
     * commands [derive] builds from its output for the state. Null from [derive] means the output
     * didn't say enough to go on.
     */
    data class Derived(
        val probe: ShellCommand,
        val derive: (output: String, state: String) -> List<ShellCommand>?,
        override val level: AccessLevel = AccessLevel.Shell,
    ) : Write

    data class Api(val api: PlatformApi, override val level: AccessLevel) : Write

    /** Not built yet. The registry keeps it so the catalog is complete, and the UI hides it. */
    data class Pending(val reason: String, override val level: AccessLevel) : Write
}

/** What a tap does when the toggle's level is missing. With none, the tap opens Setup. */
data class Fallback(val settingsAction: String, val label: String)

/**
 * One toggle, as every surface sees it: widgets, tiles, the notification bar and automations.
 *
 * @property id stable across releases; it keeps the Mac's id where the idea is the same.
 * @property icon a Material Symbols name.
 * @property states the order a tap moves through. Two for on and off, more for a cycle, one for an
 *   action.
 */
data class ToggleDef(
    val id: String,
    val title: String,
    val group: ToggleGroup,
    val icon: String,
    val states: List<ToggleState>,
    val read: Probe,
    val write: Write,
    val fallback: Fallback? = null,
    /** What the phone needs for this toggle to mean anything; null when every phone has it. */
    val hardware: Hardware? = null,
) {
    val level: AccessLevel
        get() = write.level

    val isAction: Boolean
        get() = states.size == 1

    val isBuilt: Boolean
        get() = write !is Write.Pending

    /**
     * Whether [state] should draw highlighted: anything but a resting state. Actions and unknown
     * states never are.
     */
    fun isActive(state: String?): Boolean = !isAction && state != null && state !in restingStates

    /** The state a tap moves to from [current]; an unknown current state starts the cycle. */
    fun next(current: String?): ToggleState {
        val index = states.indexOfFirst { it.id == current }
        return states[(index + 1) % states.size]
    }

    companion object {
        /** States that mean "as Android ships it", which a widget draws in the off colour. */
        val restingStates =
            setOf("off", "enforced", "enforcing", "automatic", "real", "default", "1")
    }
}
