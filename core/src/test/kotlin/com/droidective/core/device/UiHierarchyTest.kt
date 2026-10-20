package com.droidective.core.device

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against `uiautomator dump` of Settings' home screen on an API 36 emulator (fixtures/ui/). */
class UiHierarchyTest {
    private val settings =
        UiHierarchy.parse(
            File(System.getProperty("fixtures.dir"), "ui/settings-api36.xml").readText() +
                "\nUI hierchary dumped to: /dev/stdout\n"
        )

    @Test
    fun readsEveryNodeInOrderWithItsDepth() {
        assertEquals(72, settings.size)
        val root = settings.first()
        assertEquals("android.widget.FrameLayout", root.className)
        assertEquals("com.android.settings", root.packageName)
        assertEquals(0, root.depth)
        assertEquals(-1, root.parent)
        assertEquals(Bounds(0, 0, 1080, 2400), root.bounds)
        settings.drop(1).forEach { node ->
            assertTrue(node.parent in settings.indices, "every node but the root has a parent")
            assertEquals(settings[node.parent].depth + 1, node.depth)
        }
    }

    @Test
    fun unescapesWhatTheXmlEscaped() {
        assertTrue(settings.any { it.text == "Network & internet" })
        assertTrue(settings.any { it.text == "Sound & vibration" })
    }

    @Test
    fun labelsAViewTheWayAPersonWould() {
        val title = settings.first { it.text == "Apps" }
        assertEquals("Apps", title.label)
        assertEquals("android:id/title", title.resourceId)
        val frame = settings.first { it.resourceId == "com.android.settings:id/text_frame" }
        assertEquals("text_frame", frame.label)
    }

    @Test
    fun aTapLandsOnTheDeepestViewUnderIt() {
        val apps = settings.first { it.text == "Apps" }
        val bounds = apps.bounds!!
        val hit = UiHierarchy.at(settings, bounds.left + 1, bounds.top + 1)!!
        assertEquals("Apps", settings[hit].text)
        assertNull(UiHierarchy.at(settings, -5, -5))
    }

    @Test
    fun selfClosingNodesAndFlags() {
        val nodes =
            UiHierarchy.parse(
                """<hierarchy rotation="0"><node index="0" class="a.B" clickable="true" """ +
                    """checkable="true" checked="false" bounds="[0,0][10,10]">""" +
                    """<node index="0" class="a.C" text="&lt;Hi&#33;&gt;" bounds="[1,1][5,5]"/>""" +
                    """<node index="1" class="a.D" content-desc="x &quot;y&quot;" """ +
                    """bounds="[5,5][9,9]"/></node></hierarchy>"""
            )
        assertEquals(listOf(0, 1, 1), nodes.map { it.depth })
        assertEquals(listOf(-1, 0, 0), nodes.map { it.parent })
        assertEquals("<Hi!>", nodes[1].text)
        assertEquals("x \"y\"", nodes[2].contentDescription)
        assertEquals(false, nodes[0].checked)
        assertNull(nodes[1].checked, "not checkable, so neither checked nor unchecked")
    }

    @Test
    fun saysWhyADumpFailed() {
        assertTrue("Another UI-automation client" in UiHierarchy.failure(137, "Killed"))
        assertTrue(
            "kept changing" in
                UiHierarchy.failure(1, "ERROR: null root node returned by UiTestAutomationBridge.")
        )
        assertEquals("boom", UiHierarchy.failure(2, "boom\n"))
        assertEquals(emptyList(), UiHierarchy.parse("ERROR: could not get idle state."))
    }

    @Test
    fun aClickableViewIsLabelledByWhatItHolds() {
        val nodes =
            UiHierarchy.parse(
                """<node class="row" clickable="true" bounds="[0,0][9,9]">""" +
                    """<node class="title" text="Apps" bounds="[0,0][9,9]"/></node>""" +
                    """<node class="icon" clickable="true" bounds="[0,0][9,9]">""" +
                    """<node class="img" bounds="[0,0][9,9]"/></node>""" +
                    """<node class="fab" clickable="true" content-desc="Add" bounds="[0,0][9,9]"/>"""
            )
        assertEquals(listOf(2), UiHierarchy.unlabelled(nodes))
        assertEquals(
            emptyList(),
            UiHierarchy.unlabelled(settings),
            "Settings' rows read their titles, and its clipped last row isn't on screen",
        )
    }

    @Test
    fun findsTargetsTooSmallToTap() {
        val nodes =
            UiHierarchy.parse(
                """<node class="a" clickable="true" bounds="[0,0][200,200]"><node class="b" """ +
                    """clickable="true" bounds="[0,0][90,200]"/><node class="c" """ +
                    """bounds="[0,0][10,10]"/></node>"""
            )
        assertEquals(listOf(1), UiHierarchy.smallTargets(nodes, minPx = 126))
        assertEquals(emptyList(), UiHierarchy.smallTargets(settings, minPx = 126))
    }

    @Test
    fun offersTheSelectorsATestWouldUse() {
        val node =
            UiHierarchy.parse(
                    """<node class="android.widget.Button" package="com.example" """ +
                        """resource-id="com.example:id/save" text="Save &quot;all&quot;" """ +
                        """content-desc="" bounds="[0,0][9,9]"/>"""
                )
                .single()
        assertEquals(
            listOf(
                "UI Automator" to "By.res(\"com.example:id/save\")",
                "Espresso" to "onView(withId(R.id.save))",
                "UI Automator" to "By.text(\"Save \\\"all\\\"\")",
                "Compose" to "onNodeWithText(\"Save \\\"all\\\"\")",
                "Maestro" to "tapOn:\n    id: \"save\"",
            ),
            UiHierarchy.selectors(node),
        )
        val framework = node.copy(resourceId = "android:id/title", text = "")
        assertEquals(
            listOf(
                "UI Automator" to "By.res(\"android:id/title\")",
                "Maestro" to "tapOn:\n    id: \"title\"",
            ),
            UiHierarchy.selectors(framework),
            "Espresso's R.id is the app's own, so a framework id gets no withId line",
        )
    }

    @Test
    fun anIdManyViewsShareComesAfterTheText() {
        val apps = settings.first { it.text == "Apps" }
        assertEquals(
            listOf(
                "UI Automator" to "By.text(\"Apps\")",
                "Compose" to "onNodeWithText(\"Apps\")",
                "UI Automator" to "By.res(\"android:id/title\")",
                "Maestro" to "tapOn: \"Apps\"",
            ),
            UiHierarchy.selectors(apps, settings),
        )
    }

    @Test
    fun takesTheScreenshotReadable() {
        assertEquals(
            "screencap -p '/x/ui.png' && chmod 644 '/x/ui.png'",
            UiHierarchy.screenshot("/x/ui.png").line,
        )
    }
}
