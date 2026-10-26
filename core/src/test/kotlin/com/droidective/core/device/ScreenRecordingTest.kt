package com.droidective.core.device

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenRecordingTest {
    private val path =
        "/storage/emulated/0/Android/data/com.droidective.android/files/handover/s.mp4"

    @Test
    fun recordsAtTheChosenRateUntilStoppedByDefault() {
        assertEquals(
            "screenrecord --bit-rate 8000000 --time-limit 0 '$path'",
            ScreenRecording.command(RecordOptions(), path).line,
        )
    }

    @Test
    fun aSizeTimestampsAndALimitAreScreenrecordsOwnFlags() {
        assertEquals(
            "screenrecord --bit-rate 4000000 --size 540x1200 --bugreport --time-limit 180 '$path'",
            ScreenRecording.command(
                    RecordOptions(
                        4,
                        width = 540,
                        height = 1200,
                        timestamps = true,
                        limitSeconds = 180,
                    ),
                    path,
                )
                .line,
        )
    }

    @Test
    fun onlyAnMp4PathIsRecordedTo() {
        assertFailsWith<IllegalArgumentException> {
            ScreenRecording.command(RecordOptions(), "/sdcard/x.txt")
        }
        assertFailsWith<IllegalArgumentException> {
            ScreenRecording.command(RecordOptions(), "/sdcard/../data/x.mp4")
        }
        assertEquals("chmod 644 '$path'", ScreenRecording.readable(path).line)
    }

    @Test
    fun optionsOutOfRangeAreRefused() {
        assertFailsWith<IllegalArgumentException> { RecordOptions(bitRateMbps = 0) }
        assertFailsWith<IllegalArgumentException> { RecordOptions(width = 100) }
        assertFailsWith<IllegalArgumentException> { RecordOptions(limitSeconds = -1) }
    }

    @Test
    fun stopsWithControlCSoTheFileIsFinished() {
        assertEquals("kill -INT 4242", ScreenRecording.stop(4242).line)
        assertTrue(ScreenRecording.isRecording(ScreenRecording.fileName(0L)))
        assertFalse(ScreenRecording.isRecording("ui-inspector.png"))
        assertFalse(ScreenRecording.isRecording("droidective-2026.har"))
    }

    @Test
    fun namesAndHalvesForTheEncoder() {
        assertEquals(
            "screen-2026-10-20-05-40-12.mp4",
            ScreenRecording.fileName(1_792_474_812_000, ZoneId.of("UTC")),
        )
        assertEquals(528 to 1200, ScreenRecording.half(1080, 2400))
        assertEquals(848 to 528, ScreenRecording.half(1700, 1060))
    }
}
