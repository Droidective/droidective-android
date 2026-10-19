package com.droidective.core.toggles

import com.droidective.core.AccessLevel
import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand

/**
 * Every toggle the app offers, in catalog order. Commands match the ones ADBKit sends where the Mac
 * has the same toggle, so both apps change a phone the same way.
 */
object Toggles {
    /**
     * Pokes running apps to re-read UI debug sysprops, the way the Developer options screen does.
     */
    val syspropsPoke = ShellCommand.of("service", "call", "activity", "1599295570")

    private val onOff = listOf(ToggleState("on", "On"), ToggleState("off", "Off"))

    private fun cmd(vararg words: String) = ShellCommand.of(*words)

    private fun action(label: String) = listOf(ToggleState("run", label))

    private fun exact(byState: Map<String, String>): (String?) -> String? = { stored ->
        byState.entries.firstOrNull { Parse.sameSettingValue(it.value, stored) }?.key
    }

    /**
     * An on/off toggle backed by one settings key.
     *
     * @param unset the state Android behaves as when the key was never written, which a fresh phone
     *   shows for most developer settings; null when Android always writes it.
     */
    private fun setting(
        id: String,
        title: String,
        group: ToggleGroup,
        icon: String,
        scope: SettingScope,
        key: String,
        unset: String?,
        on: String = "1",
        off: String = "0",
        fallback: Fallback? = null,
    ) =
        ToggleDef(
            id = id,
            title = title,
            group = group,
            icon = icon,
            states = onOff,
            read =
                Probe.Setting(scope, key) { stored ->
                    if (stored == null) unset else exact(mapOf("on" to on, "off" to off))(stored)
                },
            write =
                Write.Settings(scope, mapOf("on" to mapOf(key to on), "off" to mapOf(key to off))),
            fallback = fallback,
        )

    /** A cycle through [values] (state id to stored value) of one settings key. */
    private fun cycle(
        id: String,
        title: String,
        group: ToggleGroup,
        icon: String,
        scope: SettingScope,
        key: String,
        values: List<Triple<String, String, String>>,
        alsoWrite: Map<String, String> = emptyMap(),
    ) =
        ToggleDef(
            id = id,
            title = title,
            group = group,
            icon = icon,
            states = values.map { (state, label, _) -> ToggleState(state, label) },
            read = Probe.Setting(scope, key, exact(values.associate { (s, _, v) -> s to v })),
            write =
                Write.Settings(
                    scope,
                    values.associate { (s, _, v) -> s to (alsoWrite + (key to v)) },
                ),
        )

    /** An on/off toggle written with shell commands and read through an Android API. */
    private fun shellToggle(
        id: String,
        title: String,
        group: ToggleGroup,
        icon: String,
        read: Probe,
        on: List<ShellCommand>,
        off: List<ShellCommand>,
        fallback: Fallback? = null,
    ) =
        ToggleDef(
            id = id,
            title = title,
            group = group,
            icon = icon,
            states = onOff,
            read = read,
            write = Write.Commands(mapOf("on" to on, "off" to off)),
            fallback = fallback,
        )

    /**
     * Stay awake while charging. The value is a bitmask of chargers (AC 1, USB 2, wireless 4, dock
     * 8), so any nonzero value is on: Developer options writes 15 from Android 13, and this writes
     * 7, the three every phone has.
     */
    private fun stayAwake() =
        ToggleDef(
            id = "stay-awake",
            title = "Stay awake while charging",
            group = ToggleGroup.Developer,
            icon = "power",
            states = onOff,
            read =
                Probe.Setting(SettingScope.Global, "stay_on_while_plugged_in") { stored ->
                    if (stored == null || Parse.sameSettingValue("0", stored)) "off" else "on"
                },
            write =
                Write.Settings(
                    SettingScope.Global,
                    mapOf(
                        "on" to mapOf("stay_on_while_plugged_in" to "7"),
                        "off" to mapOf("stay_on_while_plugged_in" to "0"),
                    ),
                ),
        )

    /**
     * A UI debug sysprop: set, then poke running apps so they redraw. [readLevel] is Shell for a
     * property apps may not read, such as `persist.sys.*` (`system_prop`): read as the app it comes
     * back empty, and the toggle would always say off.
     */
    private fun sysprop(
        id: String,
        title: String,
        icon: String,
        key: String,
        on: String,
        off: String,
        readLevel: AccessLevel = AccessLevel.App,
    ) =
        shellToggle(
            id = id,
            title = title,
            group = ToggleGroup.Developer,
            icon = icon,
            read =
                Probe.Command(
                    cmd("getprop", key),
                    { out -> if (Parse.prop(out) == on) "on" else "off" },
                    readLevel,
                ),
            on = listOf(cmd("setprop", key, on), syspropsPoke),
            off = listOf(cmd("setprop", key, off), syspropsPoke),
        )

    private fun api(
        id: String,
        title: String,
        group: ToggleGroup,
        icon: String,
        api: PlatformApi,
        states: List<ToggleState> = onOff,
        level: AccessLevel = AccessLevel.App,
    ) =
        ToggleDef(
            id = id,
            title = title,
            group = group,
            icon = icon,
            states = states,
            read = if (states.size == 1) Probe.None else Probe.Api(api),
            write = Write.Api(api, level),
        )

    /**
     * The network generations the modem may use, through the phone service's own shell command. The
     * bitmasks are its help text's (NetworkTypeBitMask, NR leftmost); 3G is 4G without the LTE
     * bits. It names SIM slot 0: without `-s` it looks up the default voice SIM, which an emulator
     * reports as none (spike 6).
     */
    private fun preferredNetwork(): ToggleDef {
        val masks =
            mapOf(
                "5g" to "11001111101111111111",
                "4g" to "01001111101111111111",
                "3g" to "00001110101111111111",
            )
        return ToggleDef(
            id = "preferred-network",
            title = "Preferred network type",
            group = ToggleGroup.Network,
            icon = "signal_cellular_alt",
            states =
                listOf(ToggleState("5g", "5G"), ToggleState("4g", "4G"), ToggleState("3g", "3G")),
            read =
                Probe.Command(
                    cmd("cmd", "phone", "get-allowed-network-types-for-users", "-s", "0"),
                    Parse::networkGeneration,
                    level = AccessLevel.Shell,
                ),
            write =
                Write.Commands(
                    masks.mapValues { (_, mask) ->
                        listOf(
                            cmd(
                                "cmd",
                                "phone",
                                "set-allowed-network-types-for-users",
                                "-s",
                                "0",
                                mask,
                            )
                        )
                    }
                ),
            fallback =
                Fallback(
                    "android.settings.NETWORK_OPERATOR_SETTINGS",
                    "Opens mobile network settings",
                ),
        )
    }

    private fun pending(
        id: String,
        title: String,
        group: ToggleGroup,
        icon: String,
        reason: String,
        level: AccessLevel = AccessLevel.Shell,
        states: List<ToggleState> = onOff,
        fallback: Fallback? = null,
    ) =
        ToggleDef(
            id = id,
            title = title,
            group = group,
            icon = icon,
            states = states,
            read = Probe.None,
            write = Write.Pending(reason, level),
            fallback = fallback,
        )

    private val network =
        listOf(
            shellToggle(
                    "wifi",
                    "Wi-Fi",
                    ToggleGroup.Network,
                    "wifi",
                    read = Probe.Api(PlatformApi.WifiEnabled),
                    on = listOf(cmd("svc", "wifi", "enable")),
                    off = listOf(cmd("svc", "wifi", "disable")),
                    fallback =
                        Fallback("android.settings.panel.action.WIFI", "Opens the Wi-Fi panel"),
                )
                .copy(hardware = Hardware.Wifi),
            shellToggle(
                    "mobile-data",
                    "Mobile data",
                    ToggleGroup.Network,
                    "swap_vert",
                    read = Probe.Api(PlatformApi.MobileData),
                    on = listOf(cmd("svc", "data", "enable")),
                    off = listOf(cmd("svc", "data", "disable")),
                    fallback =
                        Fallback(
                            "android.settings.panel.action.INTERNET_CONNECTIVITY",
                            "Opens the internet panel",
                        ),
                )
                .copy(hardware = Hardware.Telephony),
            preferredNetwork().copy(hardware = Hardware.Telephony),
            shellToggle(
                "airplane-mode",
                "Airplane mode",
                ToggleGroup.Network,
                "airplanemode_active",
                read = Probe.Api(PlatformApi.AirplaneMode),
                on = listOf(cmd("cmd", "connectivity", "airplane-mode", "enable")),
                off = listOf(cmd("cmd", "connectivity", "airplane-mode", "disable")),
                fallback =
                    Fallback(
                        "android.settings.AIRPLANE_MODE_SETTINGS",
                        "Opens airplane mode settings",
                    ),
            ),
            shellToggle(
                    "bluetooth",
                    "Bluetooth",
                    ToggleGroup.Network,
                    "bluetooth",
                    read = Probe.Api(PlatformApi.BluetoothEnabled),
                    on = listOf(cmd("svc", "bluetooth", "enable")),
                    off = listOf(cmd("svc", "bluetooth", "disable")),
                    fallback =
                        Fallback("android.settings.BLUETOOTH_SETTINGS", "Opens Bluetooth settings"),
                )
                .copy(hardware = Hardware.Bluetooth),
            api(
                    "bluetooth-discoverable",
                    "Bluetooth discoverable",
                    ToggleGroup.Network,
                    "bluetooth_searching",
                    PlatformApi.BluetoothDiscoverable,
                    states = action("Make discoverable"),
                )
                .copy(hardware = Hardware.Bluetooth),
            shellToggle(
                    "nfc",
                    "NFC",
                    ToggleGroup.Network,
                    "nfc",
                    read = Probe.Api(PlatformApi.NfcEnabled),
                    on = listOf(cmd("svc", "nfc", "enable")),
                    off = listOf(cmd("svc", "nfc", "disable")),
                    fallback = Fallback("android.settings.panel.action.NFC", "Opens the NFC panel"),
                )
                .copy(hardware = Hardware.Nfc),
            pending(
                "hotspot",
                "Wi-Fi hotspot",
                ToggleGroup.Network,
                "wifi_tethering",
                reason =
                    "No shell command on Android 16: cmd wifi has no start-softap and cmd " +
                        "tethering none (spike 6)",
                fallback = Fallback("android.settings.TETHER_SETTINGS", "Opens hotspot settings"),
            ),
            shellToggle(
                "usb-tethering",
                "USB tethering",
                ToggleGroup.Network,
                "usb",
                read =
                    Probe.Command(
                        cmd("svc", "usb", "getFunctions"),
                        { out ->
                            if (out.contains("rndis") || out.contains("ncm")) "on" else "off"
                        },
                        level = AccessLevel.Shell,
                    ),
                on = listOf(cmd("svc", "usb", "setFunctions", "rndis")),
                off = listOf(cmd("svc", "usb", "setFunctions")),
                fallback = Fallback("android.settings.TETHER_SETTINGS", "Opens tethering settings"),
            ),
            pending(
                "bluetooth-tethering",
                "Bluetooth tethering",
                ToggleGroup.Network,
                "bluetooth_connected",
                reason =
                    "No shell command: cmd bluetooth_manager only turns Bluetooth on and off (spike 6)",
                fallback = Fallback("android.settings.TETHER_SETTINGS", "Opens tethering settings"),
            ),
            api("auto-sync", "Auto-sync", ToggleGroup.Network, "sync", PlatformApi.AutoSync),
            api(
                "sync-now",
                "Sync now",
                ToggleGroup.Network,
                "cloud_sync",
                PlatformApi.SyncNow,
                states = action("Sync now"),
            ),
            shellToggle(
                "data-saver",
                "Data Saver",
                ToggleGroup.Network,
                "data_saver_on",
                read = Probe.Api(PlatformApi.DataSaver),
                on = listOf(cmd("cmd", "netpolicy", "set", "restrict-background", "true")),
                off = listOf(cmd("cmd", "netpolicy", "set", "restrict-background", "false")),
                fallback =
                    Fallback("android.settings.DATA_SAVER_SETTINGS", "Opens Data Saver settings"),
            ),
            ToggleDef(
                id = "private-dns",
                title = "Private DNS",
                group = ToggleGroup.Network,
                icon = "dns",
                states =
                    listOf(
                        ToggleState("automatic", "Automatic"),
                        ToggleState("hostname", "Hostname"),
                        ToggleState("off", "Off"),
                    ),
                read =
                    Probe.Setting(SettingScope.Global, "private_dns_mode") { stored ->
                        when (stored) {
                            null,
                            "opportunistic" -> "automatic"
                            "hostname" -> "hostname"
                            "off" -> "off"
                            else -> null
                        }
                    },
                write =
                    Write.Settings(
                        SettingScope.Global,
                        mapOf(
                            "automatic" to mapOf("private_dns_mode" to "opportunistic"),
                            // The hostname first, so the mode never points at a stale one.
                            "hostname" to
                                linkedMapOf(
                                    "private_dns_specifier" to "{dns_host}",
                                    "private_dns_mode" to "hostname",
                                ),
                            "off" to mapOf("private_dns_mode" to "off"),
                        ),
                    ),
            ),
            ToggleDef(
                id = "http-proxy",
                title = "HTTP proxy",
                group = ToggleGroup.Network,
                icon = "lan",
                states = onOff,
                read =
                    Probe.Setting(SettingScope.Global, "http_proxy") { stored ->
                        if (stored.isNullOrBlank() || stored == ":0") "off" else "on"
                    },
                // ":0" is how Android spells "no proxy"; clearing the key needs the shell.
                write =
                    Write.Settings(
                        SettingScope.Global,
                        mapOf(
                            "on" to mapOf("http_proxy" to "{proxy}"),
                            "off" to mapOf("http_proxy" to ":0"),
                        ),
                    ),
            ),
        )

    private val sound =
        listOf(
            api(
                "media-volume",
                "Media volume",
                ToggleGroup.Sound,
                "volume_up",
                PlatformApi.MediaVolume,
                states =
                    listOf(
                        ToggleState("0", "Muted"),
                        ToggleState("33", "33%"),
                        ToggleState("66", "66%"),
                        ToggleState("100", "100%"),
                    ),
            ),
            api(
                "ringer-mode",
                "Ringer mode",
                ToggleGroup.Sound,
                "vibration",
                PlatformApi.RingerMode,
                states =
                    listOf(
                        ToggleState("ring", "Ring"),
                        ToggleState("vibrate", "Vibrate"),
                        ToggleState("silent", "Silent"),
                    ),
            ),
            api(
                "do-not-disturb",
                "Do Not Disturb",
                ToggleGroup.Sound,
                "do_not_disturb_on",
                PlatformApi.DoNotDisturb,
            ),
        )

    private val display =
        listOf(
            cycle(
                "brightness",
                "Brightness",
                ToggleGroup.Display,
                "brightness_6",
                SettingScope.SystemPublic,
                "screen_brightness",
                values =
                    listOf(
                        Triple("10", "10%", "26"),
                        Triple("40", "40%", "102"),
                        Triple("70", "70%", "179"),
                        Triple("100", "100%", "255"),
                    ),
                alsoWrite = mapOf("screen_brightness_mode" to "0"),
            ),
            setting(
                "auto-brightness",
                "Auto brightness",
                ToggleGroup.Display,
                "brightness_auto",
                SettingScope.SystemPublic,
                "screen_brightness_mode",
                unset = null,
            ),
            api(
                "brightness-slider",
                "Brightness slider",
                ToggleGroup.Display,
                "tune",
                PlatformApi.BrightnessSlider,
                states = action("Show slider"),
            ),
            cycle(
                "screen-timeout",
                "Screen timeout",
                ToggleGroup.Display,
                "timer",
                SettingScope.SystemPublic,
                "screen_off_timeout",
                values =
                    listOf(
                        Triple("15s", "15 s", "15000"),
                        Triple("30s", "30 s", "30000"),
                        Triple("1m", "1 min", "60000"),
                        Triple("5m", "5 min", "300000"),
                        Triple("10m", "10 min", "600000"),
                    ),
            ),
            setting(
                "auto-rotate",
                "Auto-rotate",
                ToggleGroup.Display,
                "screen_rotation",
                SettingScope.SystemPublic,
                "accelerometer_rotation",
                unset = null,
            ),
            api(
                "keep-screen-on",
                "Keep screen on",
                ToggleGroup.Display,
                "coffee",
                PlatformApi.KeepScreenOn,
            ),
            shellToggle(
                "dark-mode",
                "Dark theme",
                ToggleGroup.Display,
                "dark_mode",
                read = Probe.Api(PlatformApi.DarkTheme),
                on = listOf(cmd("cmd", "uimode", "night", "yes")),
                off = listOf(cmd("cmd", "uimode", "night", "no")),
                fallback = Fallback("android.settings.DISPLAY_SETTINGS", "Opens display settings"),
            ),
            setting(
                "night-light",
                "Night Light",
                ToggleGroup.Display,
                "nightlight",
                SettingScope.Secure,
                "night_display_activated",
                unset = "off",
            ),
            setting(
                "extra-dim",
                "Extra dim",
                ToggleGroup.Display,
                "brightness_low",
                SettingScope.Secure,
                "reduce_bright_colors_activated",
                unset = "off",
            ),
            ToggleDef(
                id = "grayscale",
                title = "Grayscale",
                group = ToggleGroup.Display,
                icon = "filter_b_and_w",
                states = onOff,
                read =
                    Probe.Setting(
                        SettingScope.Secure,
                        "accessibility_display_daltonizer_enabled",
                    ) { stored ->
                        if (stored == null) "off"
                        else exact(mapOf("on" to "1", "off" to "0"))(stored)
                    },
                write =
                    Write.Settings(
                        SettingScope.Secure,
                        mapOf(
                            "on" to
                                mapOf(
                                    "accessibility_display_daltonizer_enabled" to "1",
                                    "accessibility_display_daltonizer" to "0",
                                ),
                            "off" to mapOf("accessibility_display_daltonizer_enabled" to "0"),
                        ),
                    ),
            ),
            cycle(
                "font-size",
                "Font size",
                ToggleGroup.Display,
                "format_size",
                SettingScope.SystemPublic,
                "font_scale",
                values =
                    listOf(
                        Triple("0.85", "Small", "0.85"),
                        Triple("1", "Default", "1.0"),
                        Triple("1.15", "Large", "1.15"),
                        Triple("1.3", "Largest", "1.3"),
                    ),
            ),
        )

    private val device =
        listOf(
            api(
                    "flashlight",
                    "Flashlight",
                    ToggleGroup.Device,
                    "flashlight_on",
                    PlatformApi.Flashlight,
                )
                .copy(hardware = Hardware.CameraFlash),
            setting(
                "location",
                "Location",
                ToggleGroup.Device,
                "location_on",
                SettingScope.Secure,
                "location_mode",
                unset = null,
                on = "3",
                off = "0",
                fallback =
                    Fallback(
                        "android.settings.LOCATION_SOURCE_SETTINGS",
                        "Opens location settings",
                    ),
            ),
            shellToggle(
                "battery-saver",
                "Battery Saver",
                ToggleGroup.Device,
                "battery_saver",
                read = Probe.Api(PlatformApi.BatterySaver),
                on = listOf(cmd("cmd", "power", "set-mode", "1")),
                off = listOf(cmd("cmd", "power", "set-mode", "0")),
                fallback =
                    Fallback(
                        "android.settings.BATTERY_SAVER_SETTINGS",
                        "Opens Battery Saver settings",
                    ),
            ),
            ToggleDef(
                id = "lock-screen",
                title = "Lock screen",
                group = ToggleGroup.Device,
                icon = "lock",
                states = action("Lock"),
                read = Probe.None,
                write =
                    Write.Commands(
                        mapOf("run" to listOf(cmd("input", "keyevent", "KEYCODE_SLEEP")))
                    ),
            ),
            ToggleDef(
                id = "screenshot",
                title = "Screenshot",
                group = ToggleGroup.Device,
                icon = "screenshot",
                states = action("Take screenshot"),
                read = Probe.None,
                write = Write.Commands(mapOf("run" to listOf(Screenshot.command))),
            ),
            api(
                "switch-keyboard",
                "Switch keyboard",
                ToggleGroup.Device,
                "keyboard",
                PlatformApi.SwitchKeyboard,
                states = action("Next keyboard"),
                level = AccessLevel.Granted,
            ),
        )

    private val developer =
        listOf(
            setting(
                "wireless-debugging",
                "Wireless debugging",
                ToggleGroup.Developer,
                "adb",
                SettingScope.Global,
                "adb_wifi_enabled",
                unset = "off",
                fallback =
                    Fallback(
                        "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
                        "Opens Developer options",
                    ),
            ),
            stayAwake(),
            setting(
                "show-touches",
                "Show taps",
                ToggleGroup.Developer,
                "touch_app",
                SettingScope.SystemPrivate,
                "show_touches",
                unset = "off",
            ),
            setting(
                "pointer-location",
                "Pointer location",
                ToggleGroup.Developer,
                "my_location",
                SettingScope.SystemPrivate,
                "pointer_location",
                unset = "off",
            ),
            sysprop(
                "layout-bounds",
                "Layout bounds",
                "border_outer",
                "debug.layout",
                "true",
                "false",
            ),
            sysprop(
                "gpu-overdraw",
                "GPU overdraw",
                "layers",
                "debug.hwui.overdraw",
                "show",
                "false",
            ),
            sysprop(
                "gpu-profile",
                "Profile GPU rendering",
                "bar_chart",
                "debug.hwui.profile",
                "visual_bars",
                "false",
            ),
            sysprop(
                "strict-mode",
                "Strict mode flash",
                "warning",
                "persist.sys.strictmode.visual",
                "1",
                "0",
                readLevel = AccessLevel.Shell,
            ),
            setting(
                "keep-activities",
                "Don’t keep activities",
                ToggleGroup.Developer,
                "delete_sweep",
                SettingScope.Global,
                "always_finish_activities",
                unset = "off",
            ),
            ToggleDef(
                id = "animation-scale",
                title = "Animation scale",
                group = ToggleGroup.Developer,
                icon = "animation",
                states =
                    listOf(
                        ToggleState("1", "1×"),
                        ToggleState("0.5", "0.5×"),
                        ToggleState("0", "Off"),
                    ),
                read =
                    Probe.Setting(
                        SettingScope.Global,
                        "window_animation_scale",
                        exact(mapOf("1" to "1", "0.5" to "0.5", "0" to "0")),
                    ),
                write =
                    Write.Settings(
                        SettingScope.Global,
                        listOf("1", "0.5", "0").associateWith { scale -> animationScales(scale) },
                    ),
            ),
            api(
                "demo-mode",
                "Demo mode",
                ToggleGroup.Developer,
                "auto_awesome",
                PlatformApi.DemoMode,
                level = AccessLevel.Granted,
            ),
            ToggleDef(
                id = "fake-battery",
                title = "Fake battery",
                group = ToggleGroup.Developer,
                icon = "battery_5_bar",
                states =
                    listOf(
                        ToggleState("real", "Real"),
                        ToggleState("5", "5%"),
                        ToggleState("15", "15%"),
                        ToggleState("50", "50%"),
                    ),
                read = Probe.None,
                write =
                    Write.Commands(
                        mapOf("real" to FakeBattery.reset) +
                            listOf(5, 15, 50).associate { level ->
                                level.toString() to FakeBattery.set(level, unplugged = true)
                            }
                    ),
            ),
            ToggleDef(
                id = "display-density",
                title = "Display density",
                group = ToggleGroup.Developer,
                icon = "density_medium",
                states =
                    listOf(
                        ToggleState("default", "Default"),
                        ToggleState("smaller", "Smaller"),
                        ToggleState("larger", "Larger"),
                    ),
                read = Probe.Command(DensitySteps.probe, DensitySteps::state, AccessLevel.Shell),
                write = Write.Derived(DensitySteps.probe, DensitySteps::commands),
            ),
            ToggleDef(
                id = "hidden-api",
                title = "Hidden API checks",
                group = ToggleGroup.Developer,
                icon = "visibility_off",
                states =
                    listOf(ToggleState("enforced", "Enforced"), ToggleState("relaxed", "Relaxed")),
                read =
                    Probe.Setting(SettingScope.Global, "hidden_api_policy") { stored ->
                        if (Parse.sameSettingValue(stored, "1")) "relaxed" else "enforced"
                    },
                write =
                    Write.Commands(
                        mapOf(
                            "enforced" to
                                listOf(cmd("settings", "delete", "global", "hidden_api_policy")),
                            "relaxed" to
                                listOf(cmd("settings", "put", "global", "hidden_api_policy", "1")),
                        )
                    ),
            ),
            setting(
                "verify-adb-installs",
                "Verify adb installs",
                ToggleGroup.Developer,
                "verified_user",
                SettingScope.Global,
                "verifier_verify_adb_installs",
                unset = "on",
            ),
            setting(
                "package-verifier",
                "Package verifier",
                ToggleGroup.Developer,
                "policy",
                SettingScope.Global,
                "package_verifier_enable",
                unset = "on",
            ),
            ToggleDef(
                id = "selinux",
                title = "SELinux",
                group = ToggleGroup.Developer,
                icon = "security",
                states =
                    listOf(
                        ToggleState("enforcing", "Enforcing"),
                        ToggleState("permissive", "Permissive"),
                    ),
                // As the app, SELinux refuses the read on a real phone (an emulator allows it); the
                // shell may read it.
                read =
                    Probe.Command(
                        cmd("getenforce"),
                        { out ->
                            when (Parse.lines(out).firstOrNull()?.trim()) {
                                "Enforcing" -> "enforcing"
                                "Permissive" -> "permissive"
                                else -> null
                            }
                        },
                        AccessLevel.Shell,
                    ),
                write =
                    Write.Commands(
                        mapOf(
                            "enforcing" to listOf(cmd("setenforce", "1")),
                            "permissive" to listOf(cmd("setenforce", "0")),
                        ),
                        level = AccessLevel.Root,
                    ),
            ),
        )

    private fun animationScales(scale: String) =
        listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
            .associateWith { scale }

    val all: List<ToggleDef> = network + sound + display + device + developer

    private val byId: Map<String, ToggleDef> = all.associateBy { it.id }

    fun byId(id: String): ToggleDef? = byId[id]
}
