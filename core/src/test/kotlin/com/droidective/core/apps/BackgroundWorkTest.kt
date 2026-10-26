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
            listOf("dumpsys deviceidle unforce", "dumpsys battery reset"),
            Doze.putBack.lines,
            "what the next start runs if the app went before Wake up",
        )
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

    @Test
    fun readsAnAppsAlarmsFromTheWholeDump() {
        val dump =
            File(System.getProperty("fixtures.dir"), "alarms/dumpsys-alarm-api36.txt").readText()
        val gms = Alarms.parse(dump, "com.google.android.gms")
        assertEquals(19, gms.size)
        val activity = gms.first()
        assertEquals("ELAPSED_WAKEUP", activity.type)
        assertEquals("com.google.android.location.ALARM_WAKEUP_ACTIVITY_DETECTION", activity.tag)
        assertEquals("+1m52s585ms", activity.inTime)
        assertEquals(false, activity.exact)
        assertEquals(true, activity.wakesThePhone)
        val heartbeat = gms[1]
        assertEquals("com.google.android.gms.gcm.HEARTBEAT_ALARM", heartbeat.tag)
        assertEquals(true, heartbeat.exact)
        assertEquals("allow-listed", heartbeat.exactReason)
        val settings = Alarms.parse(dump, "com.android.settings").single()
        assertEquals("RTC_WAKEUP", settings.type)
        assertEquals("policy_permission", settings.exactReason)
        assertEquals(emptyList(), Alarms.parse(dump, "com.example.none"))
        assertEquals(17, Alarms.parse(dump, "android").size, "android isn't a prefix match")
    }

    @Test
    fun saysWhenAnAlarmFiresTheWayPeopleDo() {
        assertEquals("1m 52s", Alarms.readable("+1m52s585ms"))
        assertEquals("2h 5m", Alarms.readable("+2h5m12s0ms"))
        assertEquals("1d 3h", Alarms.readable("+1d3h0m0s"))
        assertEquals("now", Alarms.readable("+0ms"))
        assertEquals("overdue", Alarms.readable("-5s2ms"))
        assertEquals("more than a year away", Alarms.readable("+3648d15h2m1s0ms"))
    }
}
