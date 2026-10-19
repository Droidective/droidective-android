package com.droidective.core

import com.droidective.core.ShellProvider.Shizuku
import com.droidective.core.ShellProvider.Su
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrantsTest {
    @Test
    fun grantsTheGrantedLevelAndCrashReportsToTheQuotedPackage() {
        assertEquals(
            listOf(
                "pm grant 'com.droidective.android' android.permission.WRITE_SECURE_SETTINGS",
                "pm grant 'com.droidective.android' android.permission.DUMP",
                "pm grant 'com.droidective.android' android.permission.READ_DROPBOX_DATA",
            ),
            Grants.commands("com.droidective.android", sdk = 37).map { it.line },
        )
    }

    @Test
    fun usageAccessIsAnAppOpAllowedForTheQuotedPackage() {
        assertEquals(
            "appops set 'com.droidective.android' GET_USAGE_STATS allow",
            Grants.usageAccessCommand("com.droidective.android").line,
        )
    }

    @Test
    fun displayOverOtherAppsIsAnAppOpToo() {
        assertEquals(
            "appops set 'com.droidective.android' SYSTEM_ALERT_WINDOW allow",
            Grants.overlayCommand("com.droidective.android").line,
        )
    }

    @Test
    fun crashReportsNeedReadLogsBeforeAndroid15() {
        assertEquals(Grants.READ_LOGS, Grants.crashReports(30))
        assertEquals(Grants.READ_LOGS, Grants.crashReports(34))
        assertEquals(Grants.READ_DROPBOX_DATA, Grants.crashReports(35))
        assertEquals(
            "pm grant 'com.droidective.android' android.permission.READ_LOGS",
            Grants.commands("com.droidective.android", sdk = 34).last().line,
        )
    }

    @Test
    fun aShellCanDoAnythingGrantedCanButOnlyRootIsRoot() {
        assertTrue(Access(granted = false, shell = Shizuku).allows(AccessLevel.Granted))
        assertFalse(Access(granted = false, shell = Shizuku).allows(AccessLevel.Root))
        assertTrue(Access(granted = false, shell = Su).allows(AccessLevel.Root))
        assertTrue(Access(granted = true, shell = null).allows(AccessLevel.Granted))
        assertFalse(Access(granted = true, shell = null).allows(AccessLevel.Shell))
        assertTrue(Access.Nothing.allows(AccessLevel.App))
    }
}
