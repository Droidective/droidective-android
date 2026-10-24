package com.droidective.core.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApkReportTest {
    private val report =
        ApkReport(
            label = "Example",
            packageName = "com.example.app",
            versionName = "2.1",
            versionCode = 21,
            minSdk = 26,
            targetSdk = 36,
            compileSdk = 36,
            installed = true,
            flags = ApkFlags(true, false, true, false, false, false, true),
            certificates =
                listOf(
                    SigningCertificate(
                        "CN=Android Debug,O=Android,C=US",
                        "CN=Android Debug",
                        "AA:BB",
                        "CC",
                        0,
                        1,
                    )
                ),
            rotatedKey = false,
            anatomy =
                ApkAnatomy(
                    1000,
                    10,
                    listOf(
                        DexHeader("classes.dex", 39, 900, 50, 40, 30, 1_200, 25),
                        DexHeader("classes2.dex", 39, 100, 5, 4, 3, 20, 2),
                    ),
                    listOf("arm64-v8a"),
                    listOf(2, 3, 31),
                ),
            files = emptyList(),
            permissions = listOf(ApkPermission("android.permission.CAMERA", "dangerous", true)),
            definedPermissions = emptyList(),
            components =
                listOf(
                    ApkComponent(ApkComponent.Kind.Activity, ".Main", true, null, true),
                    ApkComponent(ApkComponent.Kind.Activity, ".Hidden", false, null, true),
                    ApkComponent(ApkComponent.Kind.Receiver, ".Push", true, "com.x.PERM", true),
                ),
            features = emptyList(),
            metadata = emptyList(),
        )

    @Test
    fun theTextSaysWhatAReviewerAsks() {
        assertEquals(
            """
            Example (com.example.app)
            Version 2.1 (21)
            SDK: min 26, target 36, compiled 36
            Flags: debuggable, backup allowed, profileable
            Signature schemes: v2, v3, v3.1
            Native code: arm64-v8a
            Dex files: 2, 27 classes, 1220 method references
            Certificate: CN=Android Debug,O=Android,C=US (debug key)
              SHA-256 AA:BB
            Permissions: 1
              android.permission.CAMERA (dangerous)
            Activities: 2, 1 exported
              .Main (open)
            Receivers: 1, 1 exported
              .Push needs com.x.PERM
            """
                .trimIndent(),
            report.text(),
        )
    }

    @Test
    fun onlyAnExportedComponentWithoutAPermissionIsOpen() {
        val (main, hidden, push) = report.components
        assertTrue(main.open)
        assertFalse(hidden.open)
        assertFalse(push.open)
    }
}
