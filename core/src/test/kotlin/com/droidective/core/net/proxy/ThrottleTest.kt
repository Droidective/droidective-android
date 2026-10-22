package com.droidective.core.net.proxy

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThrottleTest {
    /** A clock that only moves when the stream sleeps, so the test never waits. */
    private class Clock {
        var nanos = 0L
        val slept = mutableListOf<Long>()

        fun sleep(millis: Long) {
            slept += millis
            nanos += millis * 1_000_000
        }
    }

    @Test
    fun bytesGoNoFasterThanTheRate() {
        val clock = Clock()
        val out = ByteArrayOutputStream()
        val paced =
            Paced(out, bytesPerSecond = 1_000, nanos = { clock.nanos }, sleep = clock::sleep)
        val data = ByteArray(2_000) { it.toByte() }

        paced.write(data, 0, data.size)

        assertContentEquals(data, out.toByteArray())
        // Two thousand bytes at a thousand a second is two seconds, give or take a piece.
        assertTrue(clock.slept.sum() in 1_950L..2_000L, "slept ${clock.slept.sum()} ms")
    }

    @Test
    fun theyArriveAsATrickleNotABurst() {
        val clock = Clock()
        val paced = Paced(ByteArrayOutputStream(), 1_000, { clock.nanos }, clock::sleep)
        paced.write(ByteArray(1_000), 0, 1_000)
        // Twenty pieces a second, each followed by its own short wait.
        assertEquals(20, clock.slept.size)
        assertTrue(clock.slept.all { it in 40L..60L }, clock.slept.toString())
    }

    @Test
    fun timeAlreadyTakenIsNotWaitedAgain() {
        val clock = Clock()
        val paced = Paced(ByteArrayOutputStream(), 1_000, { clock.nanos }, clock::sleep)
        clock.nanos += 5_000_000_000 // The bytes took longer than the rate to arrive.
        paced.write(ByteArray(1_000), 0, 1_000)
        assertEquals(emptyList(), clock.slept)
    }

    @Test
    fun noRateIsTheStreamItself() {
        val out = ByteArrayOutputStream()
        assertSame(out, Paced.of(out, 0))
    }

    @Test
    fun thePresetsAreChromesInBytes() {
        val presets = Throttle.presets.associate { it.name to it.throttle }
        assertTrue(presets.getValue("Unthrottled").isOff)
        // 400 kbit/s is 50 000 bytes a second, behind two seconds of latency.
        assertEquals(Throttle(2_000, 50_000, 50_000), presets.getValue("3G"))
        assertEquals(Throttle(150, 200_000, 93_750), presets.getValue("Slow 4G"))
    }
}
