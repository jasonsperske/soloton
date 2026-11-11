package dev.soloton.roadwork

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResistanceTest {
    @Test
    fun adjustableResistanceMovesInStepsBetweenZeroAndOne() {
        val r = AdjustableResistance()
        assertEquals(0f, r.fraction)
        r.lower()
        assertEquals(0f, r.fraction, "never below zero")
        repeat(3) { r.raise() }
        assertEquals(0.15f, r.fraction, 1e-6f)
        repeat(40) { r.raise() }
        assertEquals(1f, r.fraction, "never above one")
        r.lower()
        assertEquals(0.95f, r.fraction, 1e-6f)
        r.set(0.52f)
        assertEquals(0.5f, r.fraction, 1e-6f)
        r.set(Float.NaN)
        assertEquals(0f, r.fraction)
        assertEquals(0.7f, AdjustableResistance(0.7f).fraction, 1e-6f)
        assertFailsWith<IllegalArgumentException> { AdjustableResistance(step = 0f) }
    }

    @Test
    fun theRideEasesTowardTheResistanceItIsGiven() {
        val ride = RideState(3f)
        assertEquals(0f, ride.resistance)
        ride.step(1f / 60f, 3f)
        assertEquals(0f, ride.resistance, "no resistance unless one is passed")

        repeat(30) { ride.step(1f / 60f, 3f, 0.8f) }
        assertTrue(ride.resistance > 0.3f && ride.resistance < 0.8f, "half a second in: ${ride.resistance}")
        repeat(600) { ride.step(1f / 60f, 3f, 0.8f) }
        assertEquals(0.8f, ride.resistance)

        // Out of range or not a number: clamped, never passed on to a scene.
        repeat(600) { ride.step(1f / 60f, 3f, 7f) }
        assertEquals(1f, ride.resistance)
        repeat(600) { ride.step(1f / 60f, 3f, Float.NaN) }
        assertEquals(0f, ride.resistance)
        repeat(600) { ride.step(1f / 60f, 3f, -2f) }
        assertEquals(0f, ride.resistance)
    }
}
