package com.droidective.core.apps

import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote

/** A Metro dev server address, as React Native's `debug_http_host` holds it. */
data class DevHost(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"
}

/**
 * React Native's developer support, reached the way the Mac reaches it: the keys its dev support
 * listens for, and the preference it reads its dev server from. The same messages as the Mac's
 * `rn-dev-host`, so the two say the same thing.
 */
object ReactNative {
    /** R twice: RN's dev support reloads the JS bundle from Metro. */
    val reload: ShellCommand = ShellCommand.of("input", "keyevent", "46", "46")

    /** The menu key: RN's dev support opens its dev menu. */
    val devMenu: ShellCommand = ShellCommand.of("input", "keyevent", "82")

    /** Closes the notification shade, so keys reach the app under it, not the shade. */
    val collapseShade: ShellCommand = ShellCommand.of("cmd", "statusbar", "collapse")

    /** Long enough for the shade to finish closing and the app to take focus again. */
    val settle: ShellCommand = ShellCommand.of("sleep", "0.4")

    /** HOME, so the app is in the background, as Android would have it before killing it. */
    val home: ShellCommand = ShellCommand.of("input", "keyevent", "3")

    fun kill(packageName: String): ShellCommand = forApp(packageName, "am", "kill")

    fun pidOf(packageName: String): ShellCommand = forApp(packageName, "pidof")

    fun forceStop(packageName: String): ShellCommand = forApp(packageName, "am", "force-stop")

    /** Starts the app's launcher activity, from the background as well as the foreground. */
    fun launch(packageName: String): ShellCommand {
        require(AppActions.isPackageName(packageName)) { "\"$packageName\" isn’t a package name" }
        return ShellCommand.of(
            "monkey",
            "-p",
            shellQuote(packageName),
            "-c",
            "android.intent.category.LAUNCHER",
            "1",
        )
    }

    /** monkey exits 0 even when it found nothing to start; this reads what it printed. */
    fun launched(stdout: String, stderr: String): Boolean =
        "No activities" !in stdout && "No activities" !in stderr

    /**
     * Reads what someone typed as a dev server: "host:port", a bare port (localhost), or a bare
     * host (Metro's 8081). Returns the problem in words when it can't be one.
     */
    fun parseHost(raw: String): Result<DevHost> {
        val text = raw.trim()
        if (text.isEmpty()) return problem("Enter a host:port, such as 192.168.1.10:8081.")
        if ("://" in text) {
            return problem("Enter just host:port, such as 192.168.1.10:8081, without http://.")
        }
        val colon = text.lastIndexOf(':')
        val (host, portText) =
            when {
                colon >= 0 -> text.substring(0, colon) to text.substring(colon + 1)
                text.all(Char::isDigit) -> "" to text
                else -> text to DEFAULT_PORT.toString()
            }
        val port =
            portText.toIntOrNull()?.takeIf { it in 1..MAX_PORT }
                ?: return problem(
                    "\"$portText\" isn’t a port. Use host:port, such as 192.168.1.10:8081."
                )
        if (':' in host) {
            return problem("IPv6 addresses aren’t supported here. Use an IPv4 address or a name.")
        }
        if (host.any { it.isWhitespace() || it in "<>&'\"" }) {
            return problem("\"$host\" isn’t a host name.")
        }
        return Result.success(DevHost(host.ifEmpty { "localhost" }, port))
    }

    /** The app's default SharedPreferences file, where RN keeps `debug_http_host`. */
    fun preferencesPath(packageName: String): String = "shared_prefs/${packageName}_preferences.xml"

    /**
     * [xml] with `debug_http_host` set to [host], keeping every other value; a new file when the
     * app has none yet.
     */
    fun withDevHost(xml: String?, host: DevHost): String {
        val entry = "<string name=\"debug_http_host\">${escape(host.toString())}</string>"
        if (xml == null || "<map" !in xml) {
            return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    $entry\n</map>\n"
        }
        existing.find(xml)?.let {
            return xml.replaceRange(it.range, entry)
        }
        emptyMap.find(xml)?.let {
            return xml.replaceRange(it.range, "<map>\n    $entry\n</map>")
        }
        val close = xml.indexOf("</map>")
        if (close >= 0) return xml.replaceRange(close, close, "    $entry\n")
        return xml
    }

    /** The dev server [xml] names, or null when the app hasn't been pointed at one. */
    fun devHostIn(xml: String?): String? = xml?.let {
        existing.find(it)?.groupValues?.get(1)?.let(::unescape)
    }

    /** Writes [xml] as the app's preferences, as the app itself, through `run-as`. */
    fun writePreferences(packageName: String, xml: String): ShellCommand {
        require(AppActions.isPackageName(packageName)) { "\"$packageName\" isn’t a package name" }
        val path = preferencesPath(packageName)
        val script = "mkdir -p shared_prefs && printf '%s' ${shellQuote(xml)} > ${shellQuote(path)}"
        return ShellCommand.of("run-as", shellQuote(packageName), "sh", "-c", shellQuote(script))
    }

    /**
     * Reads the app's preferences through `run-as`; it fails for an app that isn't a debug build.
     */
    fun readPreferences(packageName: String): ShellCommand {
        require(AppActions.isPackageName(packageName)) { "\"$packageName\" isn’t a package name" }
        return ShellCommand.of(
            "run-as",
            shellQuote(packageName),
            "cat",
            shellQuote(preferencesPath(packageName)),
        )
    }

    private fun forApp(packageName: String, vararg verb: String): ShellCommand {
        require(AppActions.isPackageName(packageName)) { "\"$packageName\" isn’t a package name" }
        return ShellCommand.of(*verb, shellQuote(packageName))
    }

    private fun problem(message: String): Result<DevHost> =
        Result.failure(IllegalArgumentException(message))

    private fun escape(value: String) =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun unescape(value: String) =
        value.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private val existing = Regex("<string name=\"debug_http_host\">([^<]*)</string>")
    private val emptyMap = Regex("<map\\s*/>")
    private const val DEFAULT_PORT = 8081
    private const val MAX_PORT = 65535
}
