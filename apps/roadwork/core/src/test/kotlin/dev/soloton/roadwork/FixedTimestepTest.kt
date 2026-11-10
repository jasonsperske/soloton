package dev.soloton.roadwork

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FixedTimestepTest {
    @Test
    fun runsWholeStepsAndCarriesRemainder() {
        val t = FixedTimestep(stepSeconds = 0.1f)
        var calls = 0
        assertEquals(0, t.advance(0.05f) { calls++ })
        assertEquals(1, t.advance(0.07f) { calls++ }) // 0.12 accumulated
        assertEquals(2, t.advance(0.19f) { calls++ }) // 0.02 + 0.19 = 0.21
        assertEquals(3, calls)
        assertEquals(3L, t.steps)
    }

    @Test
    fun clampsLongFrames() {
        val t = FixedTimestep(stepSeconds = 0.1f, maxFrameSeconds = 0.25f)
        assertEquals(2, t.advance(10f) {})
    }

    @Test
    fun ignoresNegativeFrameTime() {
        val t = FixedTimestep(stepSeconds = 0.1f)
        assertEquals(0, t.advance(-1f) {})
        assertEquals(0L, t.steps)
    }

    @Test
    fun advanceToIsExactAndDeterministic() {
        val a = FixedTimestep()
        assertEquals(120, a.advanceTo(2.0) {})
        assertEquals(2.0, a.simulatedSeconds, 1e-6)
        assertEquals(0, a.advanceTo(1.0) {}) // never goes backwards

        val b = FixedTimestep()
        var sum = 0.0
        b.advanceTo(30.0) { sum += it }
        assertEquals(1800L, b.steps)
        assertEquals(30.0, sum, 1e-3)
    }

    @Test
    fun rejectsBadStep() {
        assertFailsWith<IllegalArgumentException> { FixedTimestep(stepSeconds = 0f) }
    }
}
