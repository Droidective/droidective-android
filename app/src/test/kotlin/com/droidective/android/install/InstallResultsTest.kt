package com.droidective.android.install

import android.content.pm.PackageInstaller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InstallResultsTest {
    private fun failure(status: Int, message: String? = null) =
        assertIs<InstallOutcome.Failed>(InstallResults.describe(status, message)).message

    @Test
    fun saysWhatEachFailureMeansAndWhatToDo() {
        assertIs<InstallOutcome.Installed>(
            InstallResults.describe(PackageInstaller.STATUS_SUCCESS, null)
        )
        assertEquals("The install was cancelled.", failure(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertTrue("Uninstall it first" in failure(PackageInstaller.STATUS_FAILURE_CONFLICT))
        assertEquals(
            "This app doesn't run on this phone. INSTALL_FAILED_NO_MATCHING_ABIS",
            failure(
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                "INSTALL_FAILED_NO_MATCHING_ABIS",
            ),
        )
        assertEquals(
            "There isn't room for it on this phone.",
            failure(PackageInstaller.STATUS_FAILURE_STORAGE),
        )
        assertEquals(
            "Split base-en defined twice",
            failure(PackageInstaller.STATUS_FAILURE_INVALID, "Split base-en defined twice"),
        )
        assertEquals("The install failed (status 99).", failure(99))
    }

    @Test
    fun theLatestOutcomeOfASessionWins() {
        InstallResults.record(41, InstallOutcome.Confirming)
        InstallResults.record(41, InstallOutcome.Installed("com.example"))
        assertEquals(InstallOutcome.Installed("com.example"), InstallResults.all.value[41])
    }
}
