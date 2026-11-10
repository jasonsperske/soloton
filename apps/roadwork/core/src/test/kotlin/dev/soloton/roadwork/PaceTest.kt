package dev.soloton.roadwork

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaceTest {
    @Test
    fun adjustablePaceStepsAndClamps() {
        val p = AdjustablePace()
        assertEquals(2.7f, p.metersPerSecond, 1e-6f)
        p.faster()
        assertEquals(2.8f, p.metersPerSecond, 1e-5f)
        repeat(100) { p.faster() }
        assertEquals(5f, p.metersPerSecond, 1e-6f)
        assertFalse(p.canGoFaster)
        repeat(100) { p.slower() }
        assertEquals(1f, p.metersPerSecond, 1e-6f)
        assertFalse(p.canGoSlower)
        // Many taps do not accumulate float drift.
        repeat(17) { p.faster() }
        assertEquals(2.7f, p.metersPerSecond, 1e-5f)
        assertTrue(p.canGoSlower && p.canGoFaster)
    }

    @Test
    fun distanceAfterNSecondsAtSteadyPace() {
        val run = RunState(3.0f)
        val ts = FixedTimestep()
        ts.advanceTo(600.0) { run.step(it, 3.0f) }
        assertEquals(600.0, run.elapsedSeconds, 1e-3)
        assertEquals(1800.0, run.distanceMeters, 0.05)
    }

    @Test
    fun speedEasesTowardANewPace() {
        val run = RunState(2.0f)
        val ts = FixedTimestep()
        ts.advanceTo(60.0) { run.step(it, 4.0f) }
        assertEquals(4.0f, run.speed, 1e-3f)
        // Distance lies between all-slow and all-fast, and the shortfall matches the easing:
        // integral of (4 - 2) * exp(-t / tau) = 2 * tau metres.
        val expected = 4.0 * 60 - 2.0 * RunState.SPEED_EASE_SECONDS
        assertEquals(expected, run.distanceMeters, 0.1)
    }

    @Test
    fun hourLongRunStaysPrecise() {
        val run = RunState(2.7f)
        val ts = FixedTimestep()
        ts.advanceTo(3600.0) { run.step(it, 2.7f) }
        assertEquals(2.7 * 3600, run.distanceMeters, 0.5)
    }

    @Test
    fun bobAveragesToZeroAndScalesWithPace() {
        var sum = 0.0
        var maxSlow = 0f
        var maxFast = 0f
        val n = 1000
        for (i in 0 until n) {
            val ph = (i * 2 * Math.PI / n).toFloat()
            sum += RunState.bob(ph, 2.7f)
            maxSlow = maxOf(maxSlow, abs(RunState.bob(ph, 1.5f)))
            maxFast = maxOf(maxFast, abs(RunState.bob(ph, 4.5f)))
        }
        assertEquals(0.0, sum / n, 1e-3)
        assertTrue(maxFast > maxSlow)
        assertEquals(0f, RunState.bob(1f, 0f), 1e-6f)
        assertTrue(maxFast < 0.08f)
    }
}
