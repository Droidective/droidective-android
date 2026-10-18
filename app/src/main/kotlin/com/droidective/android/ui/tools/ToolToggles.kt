package com.droidective.android.ui.tools

/**
 * The toggles each tool shows, by section. ToggleRows skips an id it can't find, so every list
 * lives here, where ToolScreensTest checks each id is a real toggle that's built.
 */
object ToolToggles {
    val drawing = listOf("layout-bounds", "gpu-overdraw", "gpu-profile")
    val input = listOf("show-touches", "pointer-location")
    val appBehaviour = listOf("animation-scale", "strict-mode", "keep-activities", "stay-awake")
    val display = listOf("display-density", "font-size", "dark-mode")
    val network = listOf("airplane-mode", "wifi", "mobile-data", "data-saver")
    val power = listOf("battery-saver")
    val demoMode = listOf("demo-mode")
    val installs = listOf("verify-adb-installs", "package-verifier")
    val runtime = listOf("hidden-api", "selinux")
    val proxy = listOf("http-proxy")
    val privateDns = listOf("private-dns")
    val wirelessDebugging = listOf("wireless-debugging")
    val wifi = listOf("wifi")
}
