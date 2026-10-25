package com.droidective.core.crashes

/**
 * Takes out of a crash report's free text what could say whose phone it came from: URLs, email
 * addresses, IP addresses, absolute paths, and dotted names, which are other apps' packages and
 * classes, host names and file names. An exception's message routinely carries them; the app
 * picker's duplicate-key crash named Chrome's launcher activity.
 *
 * Names under the platform's packages and this app's own stay, because they explain the crash and
 * identify nobody.
 */
object CrashScrub {
    fun text(text: String): String {
        var scrubbed = URL.replace(text, "<url>")
        scrubbed = EMAIL.replace(scrubbed, "<email>")
        scrubbed = IPV4.replace(scrubbed, "<ip>")
        scrubbed = IPV6.replace(scrubbed) { if (isIpv6(it.value)) "<ip>" else it.value }
        scrubbed = PATH.replace(scrubbed, "<path>")
        return DOTTED.replace(scrubbed) { match ->
            if (KEPT.any { match.value.startsWith(it) }) match.value else "<name>"
        }
    }

    // The candidate pattern also matches clock times; only "::" or all eight groups make an
    // address.
    private fun isIpv6(candidate: String) =
        "::" in candidate || candidate.count { it == ':' } == IPV6_GROUPS - 1

    private const val IPV6_GROUPS = 8

    private val KEPT =
        listOf(
            "java.",
            "javax.",
            "kotlin.",
            "kotlinx.",
            "android.",
            "androidx.",
            "dalvik.",
            "libcore.",
            "com.android.internal.",
            "com.droidective.",
            "io.sentry.",
        )

    private val URL = Regex("""\b[A-Za-z][A-Za-z0-9+.-]*://\S+""")
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")
    private val IPV4 = Regex("""(?<![\w.])\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?(?![\w.])""")
    private val IPV6 = Regex("""(?<![\w:.])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?![\w:.])""")
    // Not after a word character, so "and/or" and "1/2" stay.
    private val PATH = Regex("""(?<![\w.~:/])/[\w.@+~=,-]+(?:/[\w.@+~=,-]*)*""")
    // Lower-case first, so a Kotlin type's member ("String.format") stays. A path after the name
    // goes with it: "example.com/v1/users" is a host and its route.
    private val DOTTED = Regex("""(?<![\w.$])[a-z][\w$-]*(?:\.[\w$-]+)+(?:/[\w.@+~=,-]*)*""")
}
