package com.droidective.core.apps.bundles

import com.droidective.core.apps.bundles.SplitApkSelector.Qualifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac's SplitApkSelectorTests, case for case, so both apps pick the same splits. */
class SplitApkSelectorTest {
    private val phone = DeviceSpec(listOf("arm64-v8a", "armeabi-v7a"), 420, listOf("en"))

    private fun select(files: List<String>, spec: DeviceSpec = phone) =
        SplitApkSelector.select(files, spec)

    @Test
    fun classifiesThePlayAndApkPureNamingConvention() {
        assertEquals(Qualifier.Base, SplitApkSelector.qualifier("com.example.apk"))
        assertEquals(Qualifier.Abi("arm64_v8a"), SplitApkSelector.qualifier("config.arm64_v8a.apk"))
        assertEquals(
            Qualifier.Abi("armeabi_v7a"),
            SplitApkSelector.qualifier("split_config.armeabi_v7a.apk"),
        )
        assertEquals(Qualifier.Density("xxhdpi"), SplitApkSelector.qualifier("config.xxhdpi.apk"))
        assertEquals(Qualifier.Language("en"), SplitApkSelector.qualifier("config.en.apk"))
        assertEquals(Qualifier.Language("pt"), SplitApkSelector.qualifier("config.pt_br.apk"))
        assertEquals(
            Qualifier.Density("hdpi"),
            SplitApkSelector.qualifier("myfeature.config.hdpi.apk"),
        )
        assertEquals(Qualifier.Abi("arm64_v8a"), SplitApkSelector.qualifier("config.arm64-v8a.apk"))
    }

    @Test
    fun classifiesBundletoolsNamingConvention() {
        assertEquals(Qualifier.Base, SplitApkSelector.qualifier("base-master.apk"))
        assertEquals(Qualifier.Abi("arm64_v8a"), SplitApkSelector.qualifier("base-arm64_v8a.apk"))
        assertEquals(Qualifier.Density("xxhdpi"), SplitApkSelector.qualifier("base-xxhdpi.apk"))
        assertEquals(Qualifier.Language("en"), SplitApkSelector.qualifier("splits/base-en.apk"))
    }

    @Test
    fun classifiesUnmodelledQualifiersAsOtherAndIgnoresDirectories() {
        assertEquals(Qualifier.Other("tcf_astc"), SplitApkSelector.qualifier("config.tcf_astc.apk"))
        assertEquals(Qualifier.Base, SplitApkSelector.qualifier("/tmp/config.x86/base.apk"))
        assertEquals(
            Qualifier.Abi("x86_64"),
            SplitApkSelector.qualifier("/tmp/out/config.x86_64.apk"),
        )
    }

    @Test
    fun keepsTheBaseTheBestAbiAndTheNearestDensity() {
        val selection =
            select(
                listOf(
                    "base.apk",
                    "config.arm64_v8a.apk",
                    "config.armeabi_v7a.apk",
                    "config.x86.apk",
                    "config.hdpi.apk",
                    "config.xhdpi.apk",
                    "config.xxhdpi.apk",
                    "config.xxxhdpi.apk",
                )
            )
        assertEquals("arm64_v8a", selection.chosenAbi)
        assertFalse(selection.abiUnmatched)
        // 420 dpi: the smallest bucket at or above it is xxhdpi, 480.
        assertEquals(
            listOf("base.apk", "config.arm64_v8a.apk", "config.xxhdpi.apk"),
            selection.files,
        )
    }

    @Test
    fun fallsBackToThePhonesSecondAbiWhenTheFirstIsntShipped() {
        val selection = select(listOf("base.apk", "config.armeabi_v7a.apk", "config.x86.apk"))
        assertEquals("armeabi_v7a", selection.chosenAbi)
        assertEquals(listOf("base.apk", "config.armeabi_v7a.apk"), selection.files)
    }

    @Test
    fun reportsAnUnmatchedAbiRatherThanInstallingABrokenSet() {
        val selection = select(listOf("base.apk", "config.x86.apk", "config.x86_64.apk"))
        assertTrue(selection.abiUnmatched)
        assertNull(selection.chosenAbi)
    }

    @Test
    fun aBundleWithNoAbiSplitsIsNeverUnmatched() {
        val selection = select(listOf("base.apk", "config.en.apk"))
        assertFalse(selection.abiUnmatched)
        assertNull(selection.chosenAbi)
    }

    @Test
    fun picksTheLargestDensityBelowWhenNoneReachThePhone() {
        assertEquals(
            listOf("base.apk", "config.hdpi.apk"),
            select(listOf("base.apk", "config.mdpi.apk", "config.hdpi.apk")).files,
        )
    }

    @Test
    fun picksTheLargestDensityWhenThePhonesIsUnknown() {
        val unknown = DeviceSpec(listOf("arm64-v8a"), 0, emptyList())
        assertEquals(
            listOf("base.apk", "config.xxhdpi.apk"),
            select(listOf("base.apk", "config.hdpi.apk", "config.xxhdpi.apk"), unknown).files,
        )
    }

    @Test
    fun alwaysKeepsDensityAgnosticSplits() {
        val files = listOf("base.apk", "config.nodpi.apk", "config.anydpi.apk", "config.mdpi.apk")
        assertEquals(files, select(files).files)
    }

    @Test
    fun keepsThePhonesLanguagesAndEnglishAsTheFallback() {
        val german = DeviceSpec(listOf("arm64-v8a"), 420, listOf("de"))
        assertEquals(
            listOf("base.apk", "config.de.apk", "config.en.apk"),
            select(
                    listOf(
                        "base.apk",
                        "config.de.apk",
                        "config.en.apk",
                        "config.fr.apk",
                        "config.ja.apk",
                    ),
                    german,
                )
                .files,
        )
    }

    @Test
    fun keepsFeatureModulesAndUnmodelledSplits() {
        val files =
            listOf("base.apk", "split_dynamicfeature.apk", "config.tcf_astc.apk", "config.x86.apk")
        val x86 = DeviceSpec(listOf("x86_64", "x86"), 320, listOf("en"))
        assertEquals(files, select(files, x86).files)
    }

    @Test
    fun returnsPathsUntouchedAndKeepsTheirOrder() {
        val files = listOf("/w/x/config.xxhdpi.apk", "/w/x/base.apk", "/w/x/config.arm64_v8a.apk")
        assertEquals(files, select(files).files)
    }

    @Test
    fun aSingleUnsplitApkPassesThrough() {
        val selection = select(listOf("app-release.apk"))
        assertEquals(listOf("app-release.apk"), selection.files)
        assertFalse(selection.abiUnmatched)
    }

    @Test
    fun detectsEveryFormatByExtensionAndNothingElse() {
        assertEquals(AppPackageFormat.Apk, AppPackageFormat.detect("/d/app.apk"))
        assertEquals(AppPackageFormat.Apks, AppPackageFormat.detect("/d/app.APKS"))
        assertEquals(AppPackageFormat.Xapk, AppPackageFormat.detect("app.XAPK"))
        assertEquals(AppPackageFormat.Apkm, AppPackageFormat.detect("My App v1.2.apkm"))
        assertNull(AppPackageFormat.detect("/d/bundle.aab"))
        assertNull(AppPackageFormat.detect("/d/archive.zip"))
        assertNull(AppPackageFormat.detect("/d/noextension"))
        assertNull(AppPackageFormat.detect("/d/apk"))
        assertEquals(
            listOf(AppPackageFormat.Apks, AppPackageFormat.Xapk, AppPackageFormat.Apkm),
            AppPackageFormat.entries.filter { it.isBundle },
        )
    }
}
