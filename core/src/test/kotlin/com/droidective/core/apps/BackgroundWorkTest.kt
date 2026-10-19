package com.droidective.core.apps

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Against `dumpsys jobscheduler <package>` from an API 36 emulator (fixtures/jobs/): Droidective's
 * own WorkManager jobs, and Permission Controller's, which have no namespace.
 */
class BackgroundWorkTest {
    private val dir = File(System.getProperty("fixtures.dir"), "jobs")

    @Test
    fun readsWorkManagersJobsWithTheirNamespaceAndWorker() {
        val jobs = Jobs.parse(File(dir, "workmanager-api36.txt").readText())
        assertEquals(listOf(1, 3), jobs.map { it.id })
        val session = jobs.last()
        assertEquals("androidx.work.systemjobscheduler", session.namespace)
        assertEquals(
            "com.droidective.android/androidx.work.impl.background.systemjob.SystemJobService",
            session.service,
        )
        assertEquals("SessionWorker", session.tag)
        assertEquals(listOf("CHARGING", "TIMING_DELAY", "FLEXIBILITY"), session.required)
        assertEquals(listOf("TIMING_DELAY"), session.waitingFor)
        assertFalse(session.ready)
        assertEquals("+3649d22h38m20s325ms", session.runsAt)
        assertNull(jobs.first().tag)
    }

    @Test
    fun readsJobsWithNoNamespace() {
        val jobs = Jobs.parse(File(dir, "plain-ids-api36.txt").readText())
        assertEquals(8, jobs.size)
        assertEquals(null, jobs.first().namespace)
        assertEquals(0, jobs.first().id)
        assertEquals(
            "com.google.android.permissioncontroller/com.android.permissioncontroller.permission" +
                ".service.LocationAccessCheck\$LocationAccessCheckJobService",
            jobs.first().service,
        )
    }

    @Test
    fun nothingRegisteredIsNoJobs() {
        assertEquals(emptyList(), Jobs.parse("Settings:\n  x=1\n"))
        assertEquals(emptyList(), Jobs.parse("Registered 3 jobs:\n\nPrefetchController:\n"))
    }

    @Test
    fun runsAJobInItsNamespaceForced() {
        val job =
            ScheduledJob(
                3,
                "androidx.work.systemjobscheduler",
                "s",
                null,
                emptyList(),
                emptyList(),
                false,
                null,
            )
        assertEquals(
            "cmd jobscheduler run -f -n 'androidx.work.systemjobscheduler' 'com.example.app' 3",
            Jobs.run("com.example.app", job).line,
        )
        assertEquals(
            "cmd jobscheduler run -f 'com.example.app' 7",
            Jobs.run("com.example.app", job.copy(id = 7, namespace = null)).line,
        )
        assertFailsWith<IllegalArgumentException> { Jobs.run("x;y", job) }
    }

    @Test
    fun dozeAndStandbyUseAndroidsTestingCommands() {
        assertEquals("dumpsys deviceidle force-idle deep", Doze.forceDeep.line)
        assertEquals("dumpsys deviceidle unforce", Doze.unforce.line)
        assertEquals(
            "am get-standby-bucket 'com.example.app'",
            Doze.getBucket("com.example.app").line,
        )
        assertEquals(
            "am set-standby-bucket 'com.example.app' working_set",
            Doze.setBucket("com.example.app", StandbyBucket.WorkingSet).line,
        )
        assertFailsWith<IllegalArgumentException> {
            Doze.setBucket("com.example.app", StandbyBucket.Exempted)
        }
    }

    @Test
    fun readsDozeStatesAndBuckets() {
        assertEquals("ACTIVE", Doze.state("ACTIVE\n"))
        assertEquals("IDLE_MAINTENANCE", Doze.state("IDLE_MAINTENANCE\r\n"))
        assertNull(Doze.state("Unknown command: get"))
        assertEquals(StandbyBucket.Active, Doze.bucket("10\n"))
        assertEquals(StandbyBucket.Restricted, Doze.bucket("45"))
        assertNull(Doze.bucket("No such package"))
    }
}
