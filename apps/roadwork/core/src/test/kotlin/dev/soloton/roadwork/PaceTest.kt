package dev.soloton.roadwork

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaceTest {
    @Test
    fun adjustablePaceStepsAndClampsForCycling() {
        val p = AdjustablePace()
        assertEquals(3.0f, p.metersPerSecond, 1e-6f)
        p.faster()
        assertEquals(3.1f, p.metersPerSecond, 1e-5f)
        repeat(100) { p.faster() }
        assertEquals(6f, p.metersPerSecond, 1e-6f)
        assertFalse(p.canGoFaster)
        repeat(100) { p.slower() }
        assertEquals(1.5f, p.metersPerSecond, 1e-6f)
        assertFalse(p.canGoSlower)
        // Many taps do not accumulate float drift.
        repeat(15) { p.faster() }
        assertEquals(3.0f, p.metersPerSecond, 1e-5f)
        assertTrue(p.canGoSlower && p.canGoFaster)
    }

    @Test
    fun distanceAfterNSecondsAtSteadyPace() {
        val ride = RideState(3.0f)
        val ts = FixedTimestep()
        ts.advanceTo(600.0) { ride.step(it, 3.0f) }
        assertEquals(600.0, ride.elapsedSeconds, 1e-3)
        assertEquals(1800.0, ride.distanceMeters, 0.05)
    }

    @Test
    fun speedEasesTowardANewPace() {
        val ride = RideState(2.0f)
        val ts = FixedTimestep()
        ts.advanceTo(60.0) { ride.step(it, 4.0f) }
        assertEquals(4.0f, ride.speed, 1e-3f)
        // Distance lies between all-slow and all-fast, and the shortfall matches the easing:
        // integral of (4 - 2) * exp(-t / tau) = 2 * tau metres.
        val expected = 4.0 * 60 - 2.0 * RideState.SPEED_EASE_SECONDS
        assertEquals(expected, ride.distanceMeters, 0.1)
    }

    @Test
    fun hourLongRideStaysPrecise() {
        val ride = RideState(6f)
        val ts = FixedTimestep()
        ts.advanceTo(3600.0) { ride.step(it, 6f) }
        assertEquals(6.0 * 3600, ride.distanceMeters, 0.5)
    }

    @Test
    fun wheelAndCranksFollowDistance() {
        val ride = RideState(3f)
        val ts = FixedTimestep()
        ts.advanceTo(45.0) { ride.step(it, 3f) }
        val twoPi = 2 * Math.PI
        val wheel = (ride.distanceMeters / RideState.WHEEL_RADIUS % twoPi).toFloat()
        val crank = (ride.distanceMeters / RideState.WHEEL_RADIUS / RideState.GEAR_RATIO % twoPi).toFloat()
        assertEquals(0f, angleDiff(ride.wheelAngle, wheel), 5e-3f)
        assertEquals(0f, angleDiff(ride.crankAngle, crank), 5e-3f)
        // A comfortable cadence across the pace range.
        assertTrue(RideState.cadenceRpm(3f) in 45f..70f)
        assertTrue(RideState.cadenceRpm(6f) in 90f..130f)
    }

    @Test
    fun saddleSwayIsGentleSlowAndCentred() {
        var sum = 0.0
        var bobSum = 0.0
        var maxSway = 0f
        var maxBob = 0f
        val n = 1000
        for (i in 0 until n) {
            val c = (i * 2 * Math.PI / n).toFloat()
            sum += RideState.sway(c, 3f)
            bobSum += RideState.bob(c, 3f)
            maxSway = maxOf(maxSway, abs(RideState.sway(c, 6f)))
            maxBob = maxOf(maxBob, abs(RideState.bob(c, 6f)))
        }
        assertEquals(0.0, sum / n, 1e-4)
        assertEquals(0.0, bobSum / n, 1e-4)
        // Centimetres at most: far smoother than a running bob.
        assertTrue(maxSway < 0.025f && maxBob < 0.01f)
        assertEquals(0f, RideState.sway(1f, 0f), 1e-6f)
        // One sway per crank turn: at 3 m/s that is under one per second.
        assertTrue(RideState.cadenceRpm(3f) / 60f < 1f)
    }

    private fun angleDiff(a: Float, b: Float): Float {
        val twoPi = (2 * Math.PI).toFloat()
        val d = RideState.wrap(a - b)
        return if (d > Math.PI) twoPi - d else d
    }
}
