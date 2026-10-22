package com.droidective.core.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PermissionGroupsTest {
    private val camera = "android.permission.CAMERA"
    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"
    private val background = "android.permission.ACCESS_BACKGROUND_LOCATION"

    private val maps =
        AppGrants(
            "com.example.maps",
            "Maps",
            false,
            mapOf(fine to true, coarse to true, background to false),
        )
    private val shop =
        AppGrants("com.example.shop", "shop", false, mapOf(camera to false, coarse to false))
    private val scanner = AppGrants("com.example.scan", "Barcodes", true, mapOf(camera to true))

    private fun group(id: String, sdk: Int = 36) = PermissionGroups.on(sdk).single { it.id == id }

    @Test
    fun aGroupHasOnlyWhatThatAndroidHas() {
        assertNull(PermissionGroups.on(28).find { it.id == "notifications" })
        assertEquals(
            listOf("Precise", "Approximate"),
            group("location", 28).members.map { it.short },
        )
        assertEquals(3, group("location", 29).members.size)
        assertFalse(group("media", 36).members.any { it.name.endsWith("WRITE_EXTERNAL_STORAGE") })
        assertTrue(group("media", 28).members.any { it.name.endsWith("WRITE_EXTERNAL_STORAGE") })
    }

    @Test
    fun theAppsThatAskAreTheOnesListedInOrder() {
        val holders = PermissionGroups.holders(group("camera"), listOf(maps, shop, scanner))

        assertEquals(listOf("Barcodes", "shop"), holders.map { it.app.label })
        assertTrue(holders[0].allowed)
        assertFalse(holders[1].allowed)
    }

    @Test
    fun anAppIsAllowedAGroupWhenItHoldsAnyOfIt() {
        val holders = PermissionGroups.holders(group("location"), listOf(maps, shop))
        val forMaps = holders.single { it.app == maps }

        assertTrue(forMaps.allowed)
        assertEquals(
            listOf("Precise" to true, "Approximate" to true, "All the time" to false),
            forMaps.permissions.map { it.first.short to it.second },
        )
        assertFalse(holders.single { it.app == shop }.allowed)
    }

    @Test
    fun eachGroupIsCounted() {
        val counts =
            PermissionGroups.counts(PermissionGroups.on(36), listOf(maps, shop, scanner))
                .associateBy { it.group.id }

        assertEquals(2, counts.getValue("camera").asking)
        assertEquals(1, counts.getValue("camera").allowed)
        assertEquals(2, counts.getValue("location").asking)
        assertEquals(1, counts.getValue("location").allowed)
        assertEquals(0, counts.getValue("sms").asking)
    }

    @Test
    fun noPermissionIsInTwoGroups() {
        val names = PermissionGroups.all.flatMap { group -> group.members.map { it.name } }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it.startsWith("android.permission.") })
    }
}
