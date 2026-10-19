package com.droidective.core.toggles

import com.droidective.core.AccessLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rules every toggle has to follow. A new toggle that breaks one fails here, not on a phone. */
class ToggleRegistryTest {
    private val all = Toggles.all

    @Test
    fun hasTheFiftyTogglesThePlanLists() {
        assertEquals(52, all.size)
        assertEquals(
            mapOf(
                ToggleGroup.Network to 15,
                ToggleGroup.Sound to 3,
                ToggleGroup.Display to 11,
                ToggleGroup.Device to 6,
                ToggleGroup.Developer to 17,
            ),
            all.groupingBy { it.group }.eachCount(),
        )
    }

    @Test
    fun idsAreUniqueAndKebabCase() {
        assertEquals(all.size, all.map { it.id }.toSet().size)
        all.forEach { assertTrue(Regex("[a-z0-9]+(-[a-z0-9]+)*").matches(it.id), it.id) }
    }

    @Test
    fun everyToggleHasDistinctStates() {
        all.forEach { toggle ->
            assertTrue(toggle.states.isNotEmpty(), toggle.id)
            assertEquals(toggle.states.size, toggle.states.map { it.id }.toSet().size, toggle.id)
        }
    }

    @Test
    fun everyWriteCoversExactlyTheToggleStates() {
        for (toggle in all) {
            val covered =
                when (val write = toggle.write) {
                    is Write.Settings -> write.values.keys
                    is Write.Commands -> write.commands.keys
                    is Write.Api,
                    is Write.Derived,
                    is Write.Pending -> continue
                }
            assertEquals(toggle.states.map { it.id }.toSet(), covered, toggle.id)
        }
    }

    @Test
    fun shellTogglesHaveAStandInUnlessTheyHaveNone() {
        // Developer toggles have no system screen that does the same thing. Everything else
        // has to name what a tap does without Shell, or it fails silently on most phones.
        val noStandIn =
            setOf(
                "screenshot",
                "lock-screen",
                "show-touches",
                "pointer-location",
                "layout-bounds",
                "gpu-overdraw",
                "gpu-profile",
                "strict-mode",
                "fake-battery",
                "display-density",
                "hidden-api",
                "selinux",
            )
        val missing =
            all.filter { it.level >= AccessLevel.Shell && it.fallback == null }
                .map { it.id }
                .toSet()
        assertEquals(noStandIn, missing)
    }

    @Test
    fun pendingTogglesAreTheKnownOnes() {
        assertEquals(
            setOf("hotspot", "bluetooth-tethering", "screenshot"),
            all.filterNot { it.isBuilt }.map { it.id }.toSet(),
        )
    }

    @Test
    fun appLevelProbeCommandsOnlyRead() {
        // Commands the app runs as itself must only read: the shell is for writes.
        val readOnly = setOf("getprop", "getenforce")
        all.mapNotNull { it.read as? Probe.Command }
            .filter { it.level == AccessLevel.App }
            .forEach {
                assertTrue(it.command.line.substringBefore(' ') in readOnly, it.command.line)
            }
    }

    @Test
    fun keepsTheMacsIdsWhereTheIdeaIsTheSame() {
        val macIds =
            setOf(
                "dark-mode",
                "animation-scale",
                "demo-mode",
                "fake-battery",
                "private-dns",
                "http-proxy",
                "show-touches",
                "pointer-location",
                "layout-bounds",
                "gpu-overdraw",
                "gpu-profile",
                "strict-mode",
                "keep-activities",
            )
        assertEquals(emptySet(), macIds - all.map { it.id }.toSet())
    }

    @Test
    fun everyPlaceholderNamesAKnownParam() {
        val placeholder = Regex("""\{([a-z_]+)}""")
        val used = all.flatMap { toggle ->
            when (val write = toggle.write) {
                is Write.Settings -> write.values.values.flatMap { it.values }
                is Write.Commands -> write.commands.values.flatten().map { it.line }
                else -> emptyList()
            }
        }
        used
            .flatMap { text -> placeholder.findAll(text).map { it.groupValues[1] } }
            .forEach { assertTrue(Param.of(it) != null, "{$it} isn't a Param") }
    }

    @Test
    fun iconsAreMaterialSymbolNames() {
        all.forEach { assertTrue(Regex("[a-z0-9_]+").matches(it.icon), "${it.id}: ${it.icon}") }
    }

    @Test
    fun privateSystemSettingsNeedShell() {
        assertEquals(AccessLevel.Shell, Toggles.byId("show-touches")?.level)
        assertEquals(AccessLevel.Granted, Toggles.byId("animation-scale")?.level)
        assertEquals(AccessLevel.App, Toggles.byId("auto-rotate")?.level)
    }
}
