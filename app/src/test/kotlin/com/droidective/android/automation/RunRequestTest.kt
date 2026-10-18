package com.droidective.android.automation

import com.droidective.core.automations.Cause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunRequestTest {
    @Test
    fun aRequestSurvivesTheTripThroughWorkManager() {
        val request =
            RunRequest(
                "reset-onboarding",
                RunRequest.Kind.Run,
                cause = "Run by “Morning”",
                chain = Cause(listOf("morning", "commute")),
                startAt = 2,
            )
        assertEquals(request, RunRequest.from(request.toData()))
        RunRequest.Kind.entries.forEach { kind ->
            val each = RunRequest("a", kind, "x")
            assertEquals(each, RunRequest.from(each.toData()))
        }
    }

    @Test
    fun workWithoutAnAutomationIsDropped() {
        assertNull(RunRequest.from(androidx.work.Data.EMPTY))
    }
}
