package com.droidective.core.apps.bundles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstallPlanTest {
    private val phone = DeviceSpec(listOf("arm64-v8a", "armeabi-v7a"), 420, listOf("en"))

    @Test
    fun bundletoolSplitsComeFromSplitsAndNeverStandalones() {
        val plan =
            InstallPlan.of(
                AppPackageFormat.Apks,
                listOf(
                    "toc.pb",
                    "splits/base-master.apk",
                    "splits/base-arm64_v8a.apk",
                    "splits/base-x86.apk",
                    "splits/base-xxhdpi.apk",
                    "splits/base-ldpi.apk",
                    "splits/base-en.apk",
                    "splits/base-fr.apk",
                    "standalones/standalone-arm64_v8a_xxhdpi.apk",
                ),
                manifest = null,
                spec = phone,
                sdk = 36,
            )
        assertEquals(7, plan.candidates.size)
        assertEquals(
            listOf(
                "splits/base-master.apk",
                "splits/base-arm64_v8a.apk",
                "splits/base-xxhdpi.apk",
                "splits/base-en.apk",
            ),
            plan.selection.files,
        )
        assertNull(plan.problem)
    }

    @Test
    fun anSaiApksIsItsRootApks() {
        val plan =
            InstallPlan.of(
                AppPackageFormat.Apks,
                listOf(
                    "base.apk",
                    "split_config.arm64_v8a.apk",
                    "split_config.x86_64.apk",
                    "icon.png",
                ),
                manifest = null,
                spec = phone,
                sdk = 36,
            )
        assertEquals(listOf("base.apk", "split_config.arm64_v8a.apk"), plan.selection.files)
    }

    @Test
    fun anXapkFollowsItsManifestAndNamesItsObbs() {
        val manifest =
            AppBundleManifest(
                packageName = "com.example.game",
                splitFiles = listOf("com.example.game.apk", "config.arm64_v8a.apk", "missing.apk"),
            )
        val plan =
            InstallPlan.of(
                AppPackageFormat.Xapk,
                listOf(
                    "manifest.json",
                    "icon.png",
                    "com.example.game.apk",
                    "config.arm64_v8a.apk",
                    "config.armeabi_v7a.apk",
                    "Android/obb/com.example.game/main.310.com.example.game.obb",
                ),
                manifest,
                phone,
                36,
            )
        // The manifest's list, minus what the archive doesn't hold.
        assertEquals(listOf("com.example.game.apk", "config.arm64_v8a.apk"), plan.candidates)
        assertEquals(
            listOf("Android/obb/com.example.game/main.310.com.example.game.obb"),
            plan.obbFiles,
        )
    }

    @Test
    fun anXapkWithoutAManifestListScansItsRoot() {
        val plan =
            InstallPlan.of(
                AppPackageFormat.Xapk,
                listOf("base.apk", "config.xxhdpi.apk", "config.hdpi.apk"),
                AppBundleManifest(packageName = "com.a"),
                phone,
                36,
            )
        assertEquals(listOf("base.apk", "config.xxhdpi.apk"), plan.selection.files)
    }

    @Test
    fun saysWhyABundleCantGoIn() {
        val empty = InstallPlan.of(AppPackageFormat.Apkm, listOf("info.json"), null, phone, 36)
        assertEquals("There's no APK in this APKM.", empty.problem)
        val x86Only =
            InstallPlan.of(
                AppPackageFormat.Apkm,
                listOf("base.apk", "split_config.x86.apk"),
                null,
                phone,
                36,
            )
        assertTrue(x86Only.problem!!.contains("no native code for this phone"), x86Only.problem)
    }
}
