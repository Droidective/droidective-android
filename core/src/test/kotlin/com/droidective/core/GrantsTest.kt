package com.droidective.core

import com.droidective.core.ShellProvider.Shizuku
import com.droidective.core.ShellProvider.Su
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrantsTest {
    @Test
    fun grantsBothPermissionsToTheQuotedPackage() {
        assertEquals(
            listOf(
                "pm grant 'com.droidective.android' android.permission.WRITE_SECURE_SETTINGS",
                "pm grant 'com.droidective.android' android.permission.DUMP",
            ),
            Grants.commands("com.droidective.android").map { it.line },
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
