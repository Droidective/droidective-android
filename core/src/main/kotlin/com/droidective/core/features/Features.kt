package com.droidective.core.features

import com.droidective.core.AccessLevel

enum class FeatureCategory(val title: String) {
    Developer("Developer"),
    Network("Network"),
    Apps("Apps"),
    Commands("Commands and links"),
    Device("Device"),
}

/**
 * One tool screen. [id] is the Mac's feature id, so a feature means the same thing in both apps.
 *
 * @property level the lowest level any part of the screen needs; parts that need more say so.
 */
data class FeatureDef(
    val id: String,
    val title: String,
    val subtitle: String,
    val keywords: List<String>,
    val category: FeatureCategory,
    val icon: String,
    val level: AccessLevel,
) {
    fun matches(query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() ||
            title.contains(q, ignoreCase = true) ||
            subtitle.contains(q, ignoreCase = true) ||
            keywords.any { it.contains(q, ignoreCase = true) }
    }
}

/** The tool screens the app has, in the order the Tools list shows them. */
object Features {
    val all: List<FeatureDef> =
        listOf(
            FeatureDef(
                "dev-settings",
                "Developer Settings",
                "Layout bounds, taps, animations, GPU bars",
                listOf(
                    "developer",
                    "options",
                    "layout bounds",
                    "overdraw",
                    "taps",
                    "animation",
                    "strict mode",
                ),
                FeatureCategory.Developer,
                "construction",
                AccessLevel.Granted,
            ),
            FeatureDef(
                "logcat",
                "Logcat",
                "The phone's log, live, by app and level",
                listOf("log", "logcat", "logs", "debug", "errors", "tag", "stack trace"),
                FeatureCategory.Developer,
                "receipt_long",
                AccessLevel.Shell,
            ),
            FeatureDef(
                "simulate",
                "Simulate",
                "Fake battery, display density, dark theme, network",
                listOf("battery", "density", "dpi", "dark", "font", "airplane", "offline"),
                FeatureCategory.Developer,
                "tune",
                AccessLevel.Shell,
            ),
            FeatureDef(
                "demo-mode",
                "Demo Mode",
                "A clean status bar for screenshots",
                listOf("demo", "status bar", "screenshot", "clock"),
                FeatureCategory.Developer,
                "auto_awesome",
                AccessLevel.Granted,
            ),
            FeatureDef(
                "system-restrictions",
                "System Restrictions",
                "Package verifier, adb-install checks, hidden APIs, SELinux",
                listOf("verifier", "hidden api", "selinux", "setenforce", "stay awake"),
                FeatureCategory.Developer,
                "lock_open",
                AccessLevel.Granted,
            ),
            FeatureDef(
                "http-proxy",
                "HTTP Proxy",
                "Send traffic through Charles, Proxyman or mitmproxy",
                listOf("proxy", "charles", "proxyman", "mitmproxy"),
                FeatureCategory.Network,
                "lan",
                AccessLevel.Granted,
            ),
            FeatureDef(
                "private-dns",
                "Private DNS",
                "Automatic, off, or your own resolver",
                listOf("dns", "dns-over-tls", "resolver", "adguard"),
                FeatureCategory.Network,
                "dns",
                AccessLevel.Granted,
            ),
            FeatureDef(
                "wireless-adb",
                "Wireless ADB",
                "Turn on wireless debugging and copy the address",
                listOf("adb", "wireless", "connect", "pair", "tcpip"),
                FeatureCategory.Network,
                "adb",
                AccessLevel.Granted,
            ),
            FeatureDef(
                "wifi",
                "Wi-Fi",
                "Network, signal, band and addresses",
                listOf("wifi", "wi-fi", "ssid", "bssid", "signal", "gateway"),
                FeatureCategory.Network,
                "wifi",
                AccessLevel.App,
            ),
            FeatureDef(
                "network-speed",
                "Network Speed",
                "Live download and upload rates",
                listOf("speed", "traffic", "bandwidth", "throughput"),
                FeatureCategory.Network,
                "speed",
                AccessLevel.App,
            ),
            FeatureDef(
                "get-ip",
                "Copy Device IP",
                "The phone’s address on its network",
                listOf("ip", "address", "clipboard"),
                FeatureCategory.Network,
                "content_copy",
                AccessLevel.App,
            ),
            FeatureDef(
                "apps",
                "Apps",
                "Force stop, clear data, disable, permissions",
                listOf(
                    "apps",
                    "force stop",
                    "clear data",
                    "cache",
                    "permissions",
                    "uninstall",
                    "disable",
                ),
                FeatureCategory.Apps,
                "apps",
                AccessLevel.App,
            ),
            FeatureDef(
                "current-activity",
                "Current Activity",
                "Which activity is on screen, from a Quick Settings tile",
                listOf("activity", "current", "top", "screen", "component", "class", "foreground"),
                FeatureCategory.Apps,
                "layers",
                AccessLevel.App,
            ),
            FeatureDef(
                "meminfo",
                "Memory Usage",
                "An app's memory, live: heaps, code, graphics and views",
                listOf("memory", "meminfo", "ram", "pss", "heap", "leak", "views"),
                FeatureCategory.Apps,
                "memory",
                AccessLevel.Shell,
            ),
            FeatureDef(
                "crash-catcher",
                "Crash Catcher",
                "Crashes and ANRs, with alerts for the apps you watch",
                listOf(
                    "crash",
                    "anr",
                    "not responding",
                    "exception",
                    "stack trace",
                    "tombstone",
                    "native crash",
                ),
                FeatureCategory.Apps,
                "bug_report",
                AccessLevel.Granted,
            ),
            FeatureDef(
                "custom-commands",
                "Custom Commands",
                "Your own shell commands, as buttons and toggles",
                listOf("command", "shell", "script", "custom"),
                FeatureCategory.Commands,
                "terminal",
                AccessLevel.Shell,
            ),
            FeatureDef(
                "deep-link",
                "Deep Links",
                "Saved links into your apps",
                listOf("deep link", "url", "intent", "uri"),
                FeatureCategory.Commands,
                "link",
                AccessLevel.App,
            ),
            FeatureDef(
                "device-info",
                "Device Info",
                "Model, build, display, memory, storage, battery",
                listOf("device", "build", "model", "sdk", "abi", "memory", "storage", "battery"),
                FeatureCategory.Device,
                "info",
                AccessLevel.App,
            ),
            FeatureDef(
                "root-status",
                "Root Status",
                "su, Magisk, KernelSU and the bootloader",
                listOf("root", "magisk", "kernelsu", "su", "bootloader"),
                FeatureCategory.Device,
                "verified_user",
                AccessLevel.App,
            ),
        )

    fun byId(id: String): FeatureDef? = all.firstOrNull { it.id == id }
}
