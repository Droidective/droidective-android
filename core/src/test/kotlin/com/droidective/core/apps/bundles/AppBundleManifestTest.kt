package com.droidective.core.apps.bundles

import com.droidective.core.apps.bundles.AppBundleManifest.Expansion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac's AppBundleManifestTests, case for case. */
class AppBundleManifestTest {
    private fun xapk(json: String) =
        assertNotNull(AppBundleManifest.parse(json, AppPackageFormat.Xapk))

    @Test
    fun readsAnApkPureManifestWithItsSplitsAndExpansions() {
        val manifest =
            xapk(
                """
                {
                  "xapk_version": 2,
                  "package_name": "com.example.game",
                  "name": "Example Game",
                  "version_name": "3.1.0",
                  "version_code": "310",
                  "split_apks": [
                    {"file": "com.example.game.apk", "id": "base"},
                    {"file": "config.arm64_v8a.apk", "id": "config.arm64_v8a"}
                  ],
                  "expansions": [
                    {"file": "Android/obb/com.example.game/main.310.com.example.game.obb",
                     "install_location": "EXTERNAL_STORAGE",
                     "install_path": "Android/obb/com.example.game/main.310.com.example.game.obb"}
                  ]
                }
                """
            )
        assertEquals("com.example.game", manifest.packageName)
        assertEquals("Example Game", manifest.appName)
        assertEquals("3.1.0", manifest.versionName)
        assertEquals("310", manifest.versionCode)
        assertEquals(listOf("com.example.game.apk", "config.arm64_v8a.apk"), manifest.splitFiles)
        val obb = "Android/obb/com.example.game/main.310.com.example.game.obb"
        assertEquals(listOf(Expansion(obb, obb)), manifest.expansions)
    }

    @Test
    fun readsTheVersionCodeAsAStringOrANumber() {
        assertEquals("310", xapk("""{"package_name":"a","version_code":310}""").versionCode)
        assertEquals("310", xapk("""{"package_name":"a","version_code":"310"}""").versionCode)
    }

    @Test
    fun anExpansionWithoutAnInstallPathUsesItsArchivePath() {
        val manifest = xapk("""{"expansions":[{"file":"Android/obb/com.a/main.1.com.a.obb"}]}""")
        assertEquals("Android/obb/com.a/main.1.com.a.obb", manifest.expansions.single().installPath)
    }

    @Test
    fun readsAnApkMirrorInfoFile() {
        val manifest =
            assertNotNull(
                AppBundleManifest.parse(
                    """
                    {"apkm_version": 2, "pname": "com.example.app", "app_name": "Example",
                     "release_version": "8.4.2", "versioncode": 8402, "variant": "arm64-v8a"}
                    """,
                    AppPackageFormat.Apkm,
                )
            )
        assertEquals("com.example.app", manifest.packageName)
        assertEquals("Example", manifest.appName)
        assertEquals("8.4.2", manifest.versionName)
        assertEquals("8402", manifest.versionCode)
        assertTrue(manifest.splitFiles.isEmpty(), "APKMirror doesn't list the splits")
        assertEquals(
            "Titled",
            AppBundleManifest.parse(
                    """{"pname":"com.a","apk_title":"Titled"}""",
                    AppPackageFormat.Apkm,
                )
                ?.appName,
        )
    }

    @Test
    fun malformedOrNonObjectJsonIsNull() {
        assertNull(AppBundleManifest.parse("not json", AppPackageFormat.Xapk))
        assertNull(AppBundleManifest.parse("[1,2,3]", AppPackageFormat.Xapk))
        assertNull(AppBundleManifest.parse("", AppPackageFormat.Apkm))
        assertNull(AppBundleManifest.parse("{}", AppPackageFormat.Apk), "an APK has no manifest")
    }

    @Test
    fun missingBlankAndNullFieldsAreAbsent() {
        val empty = xapk("{}")
        assertNull(empty.packageName)
        assertTrue(empty.splitFiles.isEmpty() && empty.expansions.isEmpty())
        assertNull(xapk("""{"package_name":""}""").packageName)
        assertNull(xapk("""{"package_name":null}""").packageName)
    }

    @Test
    fun refusesArchivePathsThatEscapeTheirDirectory() {
        assertEquals(
            "Android/obb/com.a/main.obb",
            AppBundleManifest.safeRelativePath("Android/obb/com.a/main.obb"),
        )
        listOf(
                "../../etc/passwd",
                "Android/../../evil.obb",
                "/absolute/evil.obb",
                "~/evil.obb",
                "",
                null,
                "Android\\..\\..\\evil.obb",
                "Android//evil.obb",
            )
            .forEach { assertNull(AppBundleManifest.safeRelativePath(it), it.toString()) }
    }

    @Test
    fun aTraversingEntryIsDroppedFromAnOtherwiseGoodManifest() {
        val manifest =
            xapk(
                """
                {"split_apks": [{"file": "base.apk"}, {"file": "../../escape.apk"}],
                 "expansions": [{"file": "../../escape.obb", "install_path": "../../escape.obb"}]}
                """
            )
        assertEquals(listOf("base.apk"), manifest.splitFiles)
        assertTrue(manifest.expansions.isEmpty())
    }
}
