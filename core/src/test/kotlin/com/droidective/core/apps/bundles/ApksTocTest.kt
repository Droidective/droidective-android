package com.droidective.core.apps.bundles

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against a real `.apks` bundletool 1.18.3 made from this app (fixtures/apks/). */
class ApksTocTest {
    private val dir = File(System.getProperty("fixtures.dir"), "apks")
    private val toc = File(dir, "droidective-debug.toc.pb").readBytes()
    private val entries =
        File(dir, "droidective-debug.entries.txt").readLines().filter { it.isNotBlank() }
    private val phone = DeviceSpec(listOf("arm64-v8a"), 420, listOf("en"))

    @Test
    fun readsEachVariantsRangeNumberAndFiles() {
        val variants = assertNotNull(ApksToc.parse(toc))
        assertEquals(listOf(30, 32), variants.map { it.minSdk })
        assertEquals(listOf(0, 1), variants.map { it.number })
        assertTrue("splits/base-master.apk" in variants[0].paths)
        assertTrue("splits/base-master_2.apk" in variants[1].paths)
        assertTrue(variants.all { variant -> variant.paths.all { it in entries } })
    }

    @Test
    fun picksTheVariantWithTheHighestMinimumThePhoneMeets() {
        val variants = assertNotNull(ApksToc.parse(toc))
        assertEquals(0, ApksToc.pick(variants, 30)?.number)
        assertEquals(0, ApksToc.pick(variants, 31)?.number)
        assertEquals(1, ApksToc.pick(variants, 32)?.number)
        assertEquals(1, ApksToc.pick(variants, 37)?.number)
        assertNull(ApksToc.pick(variants, 29))
    }

    @Test
    fun theRealApksInstallsOneVariantsSplitsForThisPhone() {
        val newer =
            InstallPlan.of(AppPackageFormat.Apks, entries, null, phone, 36, ApksToc.parse(toc))
        assertEquals(
            setOf(
                "splits/base-master_2.apk",
                "splits/base-arm64_v8a_2.apk",
                "splits/base-xxhdpi_2.apk",
                "splits/base-en_2.apk",
            ),
            newer.selection.files.toSet(),
        )
        assertEquals("arm64_v8a", newer.selection.chosenAbi)
        assertNull(newer.problem)

        val older =
            InstallPlan.of(AppPackageFormat.Apks, entries, null, phone, 31, ApksToc.parse(toc))
        assertEquals(
            setOf(
                "splits/base-master.apk",
                "splits/base-arm64_v8a.apk",
                "splits/base-xxhdpi.apk",
                "splits/base-en.apk",
            ),
            older.selection.files.toSet(),
        )
    }

    @Test
    fun anX86PhoneKeepsX86_64RatherThanReadingItAsASuffix() {
        val x86 = DeviceSpec(listOf("x86_64", "x86"), 420, listOf("en"))
        val plan = InstallPlan.of(AppPackageFormat.Apks, entries, null, x86, 31, ApksToc.parse(toc))
        assertEquals("x86_64", plan.selection.chosenAbi)
        assertTrue("splits/base-x86_64.apk" in plan.selection.files)
    }

    @Test
    fun noVariantForThePhonesAndroidIsSaid() {
        val plan =
            InstallPlan.of(AppPackageFormat.Apks, entries, null, phone, 29, ApksToc.parse(toc))
        assertTrue(plan.problem!!.contains("API 29"), plan.problem)
    }

    @Test
    fun aTableThatIsNotOneIsNull() {
        assertNull(ApksToc.parse(byteArrayOf(0x08)))
        assertNull(ApksToc.parse("garbage".encodeToByteArray()))
        assertNull(ApksToc.parse(toc.copyOf(toc.size / 2)))
        assertEquals(emptyList(), ApksToc.parse(ByteArray(0)))
    }
}
