package com.droidective.core.shell

/**
 * Pure parsers for command output. Split on [lines], never on "\n": adb and OEM tools emit CRLF.
 */
object Parse {
    private val newline = Regex("\r\n|\r|\n")

    fun lines(output: String): List<String> = output.split(newline)

    /** `settings get` prints the value, or "null" when the key is unset. */
    fun settingValue(output: String): String? {
        val value = lines(output).firstOrNull()?.trim().orEmpty()
        return if (value.isEmpty() || value == "null") null else value
    }

    private val javaException = Regex("""^(?:[\w$]+\.)*[\w$]*(?:Exception|Error): (.+)$""")

    /**
     * Why a command failed, in one line. `cmd`, `am` and `device_config` print a failure as
     * "Exception occurred while executing" over a Java stack trace, so the exception's own message
     * is the reason; anything else is the first line the command said.
     */
    fun failure(vararg outputs: String): String? {
        val said = outputs.flatMap(::lines).map { it.trim() }.filter { it.isNotEmpty() }
        return said.firstNotNullOfOrNull { javaException.find(it)?.groupValues?.get(1) }
            ?: said.firstOrNull()
    }

    private val propLine = Regex("""^\[([^\]]+)\]: \[(.*)\]$""")

    /** Plain `getprop` prints every property as `[key]: [value]`, one per line. */
    fun props(output: String): Map<String, String> =
        lines(output)
            .mapNotNull { propLine.find(it.trim())?.destructured }
            .associate { (key, value) -> key to value }

    private val connectedTo = Regex("""^Wifi is connected to "(.*)"$""")
    private val wifiInfoSsid = Regex("""WifiInfo: SSID: "(.*?)",""")

    /**
     * The network `cmd wifi status` says the phone is on, or null when it isn't on one. Android
     * 11's output has only the WifiInfo line, so that's the fallback.
     */
    fun wifiSsid(output: String): String? {
        val lines = lines(output).map { it.trim() }
        return lines.firstNotNullOfOrNull { connectedTo.find(it)?.groupValues?.get(1) }
            ?: lines
                .firstNotNullOfOrNull { wifiInfoSsid.find(it)?.groupValues?.get(1) }
                ?.takeUnless { it == "<unknown ssid>" }
    }

    private val dumpedSsid = Regex("""mWifiInfo SSID: "(.*?)",""")

    /**
     * The network `dumpsys wifi` says the phone is on, or null. Its first `mWifiInfo` line is the
     * primary connection; others are for networks it isn't on, and say `<unknown ssid>`.
     */
    fun wifiSsidFromDump(output: String): String? =
        lines(output)
            .firstNotNullOfOrNull { dumpedSsid.find(it)?.groupValues?.get(1) }
            ?.takeUnless { it.isEmpty() || it == "<unknown ssid>" }

    private val resumed =
        Regex(
            """(?:topResumedActivity|mResumedActivity|ResumedActivity)[=:]\s*ActivityRecord\{\S+ u\d+ ([\w.]+)/([\w.$]+)"""
        )

    /** The package in front, from `dumpsys activity activities`. */
    fun resumedPackage(output: String): String? =
        lines(output).firstNotNullOfOrNull { resumed.find(it)?.groupValues?.get(1) }

    /**
     * The activity in front, as `package/.Activity` the way `am start -n` takes it, from `dumpsys
     * activity activities`.
     */
    fun resumedActivity(output: String): String? =
        lines(output).firstNotNullOfOrNull { line ->
            resumed.find(line)?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }
        }

    /**
     * The newest generation `cmd phone get-allowed-network-types-for-users` allows: its `|`-joined
     * radio names hold NR for 5G and LTE for 4G; anything else is 3G or older.
     */
    fun networkGeneration(output: String): String? {
        val types = lines(output).firstOrNull().orEmpty().split('|').map { it.trim() }
        return when {
            "NR" in types -> "5g"
            "LTE" in types || "LTE_CA" in types -> "4g"
            // An error message isn't a list of radios; only a real list reads as 3G.
            types.any { it in olderRadios } -> "3g"
            else -> null
        }
    }

    private val olderRadios =
        setOf("GSM", "GPRS", "EDGE", "UMTS", "HSDPA", "HSUPA", "HSPA", "HSPA+", "TD_SCDMA", "CDMA")

    /** `getprop <key>` prints the value or an empty line. */
    fun prop(output: String): String? = lines(output).firstOrNull()?.trim()?.ifEmpty { null }

    /** `cmd uimode night` prints "Night mode: yes", "no", "auto" or a custom schedule name. */
    fun nightMode(output: String): String? =
        lines(output).firstNotNullOfOrNull { line ->
            line.trim().removePrefix("Night mode:").takeIf { it != line.trim() }?.trim()
        }

    /**
     * `wm density` prints "Physical density: 420" and, when overridden, "Override density: 480".
     *
     * @return the physical density and the override, or null when the output has no physical line.
     */
    fun wmDensity(output: String): Density? {
        var physical: Int? = null
        var override: Int? = null
        for (line in lines(output)) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Physical density:") ->
                    physical = trimmed.substringAfter(':').trim().toIntOrNull()
                trimmed.startsWith("Override density:") ->
                    override = trimmed.substringAfter(':').trim().toIntOrNull()
            }
        }
        return physical?.let { Density(physical = it, override = override) }
    }

    /**
     * Whether two settings values mean the same thing. Settings stores numbers as text, so "1" and
     * "1.0" are the same animation scale.
     */
    fun sameSettingValue(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == b
        val x = a.trim().toDoubleOrNull()
        val y = b.trim().toDoubleOrNull()
        return if (x != null && y != null) x == y else a.trim() == b.trim()
    }

    data class Density(val physical: Int, val override: Int?)
}
