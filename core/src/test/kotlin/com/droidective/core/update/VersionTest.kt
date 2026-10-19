package com.droidective.core.update

import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class VersionTest {
    @Test
    fun ordersTheWaySemverSays() {
        // The precedence example from semver.org, section 11.
        val ordered =
            listOf(
                    "1.0.0-alpha",
                    "1.0.0-alpha.1",
                    "1.0.0-alpha.beta",
                    "1.0.0-beta",
                    "1.0.0-beta.2",
                    "1.0.0-beta.11",
                    "1.0.0-rc.1",
                    "1.0.0",
                    "1.0.1",
                    "1.1.0",
                    "2.0.0",
                    "10.0.0",
                )
                .map { Version.parse(it)!! }
        // Every pair both ways: a sort can hide a comparator that says "greater" in both orders.
        for ((i, lower) in ordered.withIndex()) {
            for (higher in ordered.drop(i + 1)) {
                assertTrue(lower < higher, "$lower < $higher")
                assertTrue(higher > lower, "$higher > $lower")
            }
            assertEquals(0, lower.compareTo(lower))
        }
    }

    @Test
    fun readsATagAndDropsBuildMetadata() {
        assertEquals(Version(0, 2, 0), Version.parse("v0.2.0"))
        assertEquals(Version(0, 2, 0), Version.parse(" 0.2.0+build.7 "))
        assertEquals(Version(1, 0, 0, listOf("beta", "1")), Version.parse("1.0.0-beta.1"))
        assertEquals(0, Version.parse("1.0.0+a")!!.compareTo(Version.parse("1.0.0+b")!!))
    }

    @Test
    fun rejectsWhatIsNotAVersion() {
        listOf(
                "",
                "v",
                "1.2",
                "1.2.3.4",
                "01.2.3",
                "1.2.3-",
                "1.2.3-beta..1",
                "1.2.3 beta",
                "latest",
                "99999999999.0.0",
            )
            .forEach { assertNull(Version.parse(it), it) }
    }

    @Test
    fun anyVersionRoundTripsThroughItsText() = runTest {
        val identifier =
            Arb.choice(
                Arb.int(0..500).map { it.toString() },
                Arb.string(1..6, "aZz-"),
            )
        checkAll(
            Arb.int(0..2_000),
            Arb.int(0..2_000),
            Arb.int(0..2_000),
            Arb.list(identifier, 0..3),
        ) { major, minor, patch, pre ->
            val version = Version(major, minor, patch, pre)
            assertEquals(version, Version.parse(version.toString()))
            assertEquals(version, Version.parse("v$version"))
        }
    }

    @Test
    fun aReleaseOutranksEveryPreReleaseOfIt() = runTest {
        checkAll(Arb.int(0..50), Arb.list(Arb.int(0..9).map { it.toString() }, 1..3)) { minor, pre
            ->
            assertTrue(Version(1, minor, 0) > Version(1, minor, 0, pre))
        }
    }
}
