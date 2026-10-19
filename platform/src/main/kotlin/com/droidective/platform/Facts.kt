package com.droidective.platform

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.text.format.Formatter
import com.droidective.core.device.Bootloader
import com.droidective.core.device.RootCheck
import com.droidective.core.device.RootEvidence
import com.droidective.core.shell.Parse
import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.platform.shell.AppShell
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address

/** One labelled value in a group, for the Device Info and Wi-Fi screens. */
data class Fact(val label: String, val value: String?)

data class FactGroup(val title: String, val facts: List<Fact>)

/** The groups as plain text, one "label: value" per line, for pasting into a bug report. */
fun List<FactGroup>.asText(): String =
    joinToString("\n\n") { group ->
        group.title + "\n" + group.facts.joinToString("\n") { "${it.label}: ${it.value ?: "—"}" }
    }

/**
 * Every system property the reader can see. An app's own `getprop` leaves out the ones SELinux
 * keeps from apps, such as `ro.boot.verifiedbootstate`; the shell user sees them all.
 */
private suspend fun properties(shell: Shell?): Map<String, String> {
    val getprop = ShellCommand.of("getprop")
    val output =
        shell?.let {
            try {
                it.run(getprop).stdout
            } catch (_: ShellUnavailableException) {
                null
            }
        } ?: AppShell().run(getprop).stdout
    return Parse.props(output)
}

/** What the phone says about itself. */
object DeviceFacts {
    suspend fun collect(context: Context, shell: Shell?): List<FactGroup> {
        val props = properties(shell)
        val memory =
            ActivityManager.MemoryInfo().also {
                context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
            }
        val storage = StatFs(Environment.getDataDirectory().path)
        val display = context.resources.displayMetrics
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
        return listOf(
            FactGroup(
                "Device",
                listOf(
                    Fact("Model", "${Build.MANUFACTURER} ${Build.MODEL}"),
                    Fact("Device", Build.DEVICE),
                    Fact("Hardware", Build.HARDWARE),
                    Fact("ABIs", Build.SUPPORTED_ABIS.joinToString(", ")),
                ),
            ),
            FactGroup(
                "Software",
                listOf(
                    Fact("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
                    Fact("Security patch", Build.VERSION.SECURITY_PATCH),
                    Fact("Build", Build.DISPLAY),
                    Fact("Fingerprint", Build.FINGERPRINT),
                    Fact("Kernel", System.getProperty("os.version")),
                    Fact("Uptime", uptime(SystemClock.elapsedRealtime())),
                    Fact("Bootloader", Bootloader.version(Build.BOOTLOADER)),
                    Fact("Bootloader lock", Bootloader.lock(props)),
                    Fact(
                        "Verified boot",
                        props["ro.boot.verifiedbootstate"]?.takeIf { it.isNotBlank() },
                    ),
                ),
            ),
            FactGroup(
                "Display",
                listOf(
                    Fact("Resolution", "${display.widthPixels} × ${display.heightPixels}"),
                    Fact("Density", "${display.densityDpi} dpi (${display.density}×)"),
                    Fact("Font scale", context.resources.configuration.fontScale.toString()),
                ),
            ),
            FactGroup(
                "Memory and storage",
                listOf(
                    Fact("RAM", "${size(memory.availMem)} free of ${size(memory.totalMem)}"),
                    Fact(
                        "Storage",
                        "${size(storage.availableBytes)} free of ${size(storage.totalBytes)}",
                    ),
                ),
            ),
            FactGroup(
                "Battery",
                listOf(
                    Fact(
                        "Level",
                        battery?.let { "${it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)}%" },
                    ),
                    Fact(
                        "Charging",
                        battery?.let {
                            chargeState(it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))
                        },
                    ),
                    Fact(
                        "Temperature",
                        battery?.let {
                            "%.1f °C"
                                .format(
                                    java.util.Locale.ROOT,
                                    it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0,
                                )
                        },
                    ),
                ),
            ),
        )
    }

    private fun chargeState(plugged: Int): String =
        when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Plugged in (AC)"
            BatteryManager.BATTERY_PLUGGED_USB -> "Plugged in (USB)"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            0 -> "On battery"
            else -> "Plugged in"
        }

    private fun uptime(millis: Long): String {
        val minutes = millis / 60_000
        return "${minutes / 1_440} d ${(minutes / 60) % 24} h ${minutes % 60} min"
    }
}

/** The phone's addresses on its current network. */
object NetworkFacts {
    data class Addresses(
        val ipv4: String?,
        val ipv6: List<String>,
        val gateway: String?,
        val dns: List<String>,
    )

    fun current(context: Context): Addresses? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return null
        val link: LinkProperties = connectivity.getLinkProperties(network) ?: return null
        val addresses = link.linkAddresses.map { it.address }
        val gateways =
            link.routes.filter { it.isDefaultRoute && it.hasGateway() }.map { it.gateway }
        return Addresses(
            ipv4 = addresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress,
            // A link-local fe80:: address only works with an interface name, never from a computer.
            ipv6 =
                addresses
                    .filterIsInstance<Inet6Address>()
                    .filterNot { it.isLinkLocalAddress }
                    .mapNotNull { it.hostAddress?.substringBefore('%') },
            // The IPv4 router is the one people mean; an IPv6 default route names a fe80:: one.
            gateway =
                (gateways.firstOrNull { it is Inet4Address } ?: gateways.firstOrNull())
                    ?.hostAddress
                    ?.substringBefore('%'),
            dns = link.dnsServers.mapNotNull { it.hostAddress },
        )
    }

    /** Wi-Fi details Android gives an app without location access; the name needs more. */
    fun wifi(context: Context): List<Fact>? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return null
        val caps = connectivity.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val info = caps.transportInfo as? WifiInfo ?: return null
        val band =
            when (info.frequency) {
                in 2_400..2_500 -> "2.4 GHz"
                in 4_900..5_900 -> "5 GHz"
                in 5_925..7_125 -> "6 GHz"
                else -> "${info.frequency} MHz"
            }
        return listOf(
            Fact("Signal", "${info.rssi} dBm"),
            Fact("Band", "$band (${info.frequency} MHz)"),
            Fact("Link speed", "${info.linkSpeed} Mbps"),
            Fact("Standard", wifiStandard(info.wifiStandard)),
        )
    }

    private fun wifiStandard(standard: Int): String =
        when (standard) {
            ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (802.11n)"
            ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (802.11ac)"
            ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (802.11ax)"
            ScanResult.WIFI_STANDARD_11AD -> "WiGig (802.11ad)"
            ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7 (802.11be)"
            else -> "Legacy"
        }
}

/** What points toward root on this phone, gathered without ever asking su. */
object RootProbe {
    /** @param shell the Shizuku shell, if any, which can read the bootloader's properties. */
    suspend fun evidence(context: Context, shell: Shell?): RootEvidence {
        val props = properties(shell)
        return RootEvidence(
            suBinaries = RootCheck.suPaths.filter { File(it).exists() },
            managers =
                RootCheck.managerPackages.filterKeys { isInstalled(context, it) }.values.toList(),
            buildTags = props["ro.build.tags"] ?: Build.TAGS,
            // userdebug and eng builds are debuggable; only user builds aren't.
            debuggable = props["ro.debuggable"]?.let { it == "1" } ?: (Build.TYPE != "user"),
            verifiedBootState = props["ro.boot.verifiedbootstate"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun isInstalled(context: Context, packageName: String): Boolean =
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
}
