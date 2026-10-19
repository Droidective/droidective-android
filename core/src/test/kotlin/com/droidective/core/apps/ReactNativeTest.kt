package com.droidective.core.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReactNativeTest {
    private fun host(raw: String) = ReactNative.parseHost(raw).getOrThrow()

    private fun problem(raw: String) =
        ReactNative.parseHost(raw).exceptionOrNull()?.message.orEmpty()

    @Test
    fun readsADevServerTheWaysPeopleTypeIt() {
        assertEquals(DevHost("192.168.1.10", 8081), host("192.168.1.10:8081"))
        assertEquals(
            DevHost("192.168.1.10", 8081),
            host(" 192.168.1.10 "),
            "Metro's port by default",
        )
        assertEquals(DevHost("localhost", 19000), host("19000"), "a bare port is this phone")
        assertEquals(DevHost("metro.local", 8088), host("metro.local:8088"))
    }

    @Test
    fun saysWhatsWrongWithADevServerItCantUse() {
        assertTrue("http://" in problem("http://10.0.0.2:8081"))
        assertTrue("isn’t a port" in problem("10.0.0.2:eighty"))
        assertTrue("isn’t a port" in problem("10.0.0.2:70000"))
        assertTrue("IPv6" in problem("fe80::1:8081"))
        assertTrue("isn’t a host" in problem("a<b>:8081"))
        assertTrue("Enter a host" in problem("  "))
    }

    @Test
    fun setsTheDevHostKeepingEveryOtherPreference() {
        val existing =
            """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <boolean name="js_dev_mode_debug" value="true" />
                <string name="debug_http_host">10.0.0.2:8081</string>
            </map>
            """
                .trimIndent()
        val written = ReactNative.withDevHost(existing, DevHost("192.168.1.10", 8088))
        assertTrue("<boolean name=\"js_dev_mode_debug\" value=\"true\" />" in written)
        assertEquals("192.168.1.10:8088", ReactNative.devHostIn(written))
        assertFalse("10.0.0.2" in written)
    }

    @Test
    fun addsTheDevHostToAnEmptyOrMissingFile() {
        val fresh = ReactNative.withDevHost(null, DevHost("10.0.0.2", 8081))
        assertTrue(fresh.startsWith("<?xml"))
        assertEquals("10.0.0.2:8081", ReactNative.devHostIn(fresh))
        val empty = ReactNative.withDevHost("<?xml version='1.0' ?>\n<map />\n", DevHost("h", 1))
        assertEquals("h:1", ReactNative.devHostIn(empty))
        val noHost =
            ReactNative.withDevHost(
                "<map>\n    <int name=\"a\" value=\"1\" />\n</map>",
                DevHost("h", 2),
            )
        assertEquals("h:2", ReactNative.devHostIn(noHost))
        assertTrue("<int name=\"a\" value=\"1\" />" in noHost)
        assertNull(ReactNative.devHostIn("<map />"))
    }

    @Test
    fun writesThePreferencesAsTheAppWithEverythingQuoted() {
        val xml = "<map>\n    <string name=\"k\">it's</string>\n</map>"
        assertEquals(
            "run-as 'com.example.app' sh -c 'mkdir -p shared_prefs && printf '\\''%s'\\'' " +
                "'\\''<map>\n    <string name=\"k\">it'\\''\\'\\'''\\''s</string>\n</map>'\\'' > " +
                "'\\''shared_prefs/com.example.app_preferences.xml'\\'''",
            ReactNative.writePreferences("com.example.app", xml).line,
        )
        assertEquals(
            "run-as 'com.example.app' cat 'shared_prefs/com.example.app_preferences.xml'",
            ReactNative.readPreferences("com.example.app").line,
        )
    }

    @Test
    fun reloadsWithTheKeysTheMacSends() {
        assertEquals("input keyevent 46 46", ReactNative.reload.line)
        assertEquals("input keyevent 82", ReactNative.devMenu.line)
        assertEquals("am kill 'com.example.app'", ReactNative.kill("com.example.app").line)
        assertEquals(
            "monkey -p 'com.example.app' -c android.intent.category.LAUNCHER 1",
            ReactNative.launch("com.example.app").line,
        )
        assertFalse(ReactNative.launched("** No activities found to run, monkey aborted.", ""))
        assertTrue(ReactNative.launched("Events injected: 1", ""))
        assertFailsWith<IllegalArgumentException> { ReactNative.kill("x; reboot") }
    }
}
