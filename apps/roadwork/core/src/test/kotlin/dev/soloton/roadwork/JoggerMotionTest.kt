package dev.soloton.roadwork

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JoggerMotionTest {
    private val twoPi = (2 * PI).toFloat()

    /** Rides with a pace that jumps around and checks the jogger's gap every step. */
    @Test
    fun gapStaysInBandAcrossPaceChanges() {
        val ride = RideState(3f)
        val jog = JoggerMotion()
        val ts = FixedTimestep()
        val paces = floatArrayOf(3f, 6f, 1.5f, 4.2f, 6f, 1.5f, 2.3f, 5.5f)
        var minGap = 99f
        var maxGap = 0f
        var i = 0
        ts.advanceTo(3600.0) { dt ->
            val target = paces[(ts.simulatedSeconds / 45.0).toInt() % paces.size]
            ride.step(dt, target)
            jog.step(dt, ride.elapsedSeconds, ride.speed)
            minGap = minOf(minGap, jog.gap)
            maxGap = maxOf(maxGap, jog.gap)
            i++
        }
        assertTrue(minGap >= JoggerMotion.MIN_GAP && maxGap <= JoggerMotion.MAX_GAP, "gap $minGap..$maxGap")
        // It really does drift, rather than sitting at one distance.
        assertTrue(maxGap - minGap > 1f)
    }

    @Test
    fun gapEasesRatherThanSnapsWhenPaceChanges() {
        val ride = RideState(1.5f)
        val jog = JoggerMotion()
        val ts = FixedTimestep()
        ts.advanceTo(60.0) { ride.step(it, 1.5f); jog.step(it, ride.elapsedSeconds, ride.speed) }
        var last = jog.gap
        var maxJump = 0f
        ts.advanceTo(120.0) { dt ->
            ride.step(dt, 6f)
            jog.step(dt, ride.elapsedSeconds, ride.speed)
            maxJump = maxOf(maxJump, abs(jog.gap - last))
            last = jog.gap
        }
        // No step moves the gap faster than the easing allows (0.5 m/s).
        assertTrue(maxJump <= JoggerMotion.MAX_GAP_RATE / 60f + 1e-5f, "jump $maxJump")
        // After settling, the gap sits near its (wider) target for the faster pace.
        assertEquals(JoggerMotion.targetGap(120.0, 6f), jog.gap, 0.35f)
        // And the jogger holds the rider's speed on average.
        assertEquals(ride.speed, jog.speed, 0.6f)
    }

    @Test
    fun feetAlternateAndOnlyTheSwingingFootLifts() {
        val l = FloatArray(2)
        val r = FloatArray(2)
        for (i in 0 until 72) {
            val ph = i * twoPi / 72
            JoggerMotion.foot(ph, -1, 3f, l)
            JoggerMotion.foot(ph, 1, 3f, r)
            // Mirror images: one forward while the other is back.
            assertEquals(-l[0], r[0], 1e-5f)
            assertTrue(!(l[1] > 1e-4f && r[1] > 1e-4f), "both feet in the air at phase $ph")
            assertTrue(l[1] >= 0f && r[1] >= 0f)
            // The arm on each side swings opposite to that side's leg.
            assertTrue(JoggerMotion.armSwing(ph, -1) * l[0] <= 1e-6f)
        }
        // Over a cycle each foot is ahead once and behind once.
        JoggerMotion.foot(PI.toFloat() / 2, -1, 3f, l)
        JoggerMotion.foot(PI.toFloat() / 2, 1, 3f, r)
        assertTrue(l[0] > 0.2f && r[0] < -0.2f)
    }

    @Test
    fun strideRateScalesWithSpeed() {
        assertTrue(JoggerMotion.stepsPerSecond(4f) > JoggerMotion.stepsPerSecond(2f))
        assertTrue(JoggerMotion.stepsPerSecond(6f) <= 3.4f)
        assertEquals(0f, JoggerMotion.stepsPerSecond(0f))
        assertTrue(JoggerMotion.halfStride(5f) > JoggerMotion.halfStride(2f))
        // Integrated over a minute, a faster jogger takes more steps.
        fun steps(speed: Float): Float {
            val j = JoggerMotion()
            var turns = 0f
            var prev = j.phase
            val ts = FixedTimestep()
            ts.advanceTo(60.0) {
                j.step(it, ts.simulatedSeconds, speed)
                val d = JoggerMotion.wrap(j.phase - prev)
                turns += d / twoPi
                prev = j.phase
            }
            return turns * 2f
        }
        val slow = steps(2f)
        val fast = steps(5f)
        assertTrue(fast > slow * 1.1f, "steps per minute $slow vs $fast")
        assertTrue(slow in 150f..200f, "about 170 steps a minute at a jog ($slow)")
    }

    @Test
    fun glancesAndPunchBurstsAreDeterministicAndDoNotOverlap() {
        val d = JoggerMotion(RoadworkGame.DEFAULT_SEED)
        println("default seed: glance at ${d.glanceAt} s into each 20 s, punches at ${d.punchAt} s into each 40 s")
        val a = JoggerMotion(1234L)
        val b = JoggerMotion(1234L)
        val c = JoggerMotion(99L)
        assertEquals(a.glanceAt, b.glanceAt)
        assertTrue(a.glanceAt != c.glanceAt)
        var glances = 0
        var bursts = 0
        var wasGlancing = false
        var wasPunching = false
        val pc = FloatArray(2)
        var sawLeft = false
        var sawRight = false
        var t = 0.0
        while (t < 400.0) {
            val g = a.glance(t)
            val p = a.punching(t)
            assertTrue(g in 0f..1f && p in 0f..1f)
            assertTrue(g < 1e-3f || p < 1e-3f, "glance and punches overlap at $t")
            if (g > 0.99f && !wasGlancing) glances++
            if (p > 0.99f && !wasPunching) bursts++
            wasGlancing = g > 0.99f
            wasPunching = p > 0.99f
            a.punch(t, pc)
            assertTrue(pc[0] in 0f..1f)
            if (pc[0] > 0.9f) { if (pc[1] < 0f) sawLeft = true else sawRight = true }
            assertTrue(abs(a.headYaw(t)) <= 100f)
            t += 0.02
        }
        assertEquals(20, glances) // every 20 s
        assertEquals(10, bursts) // every 40 s
        assertTrue(sawLeft && sawRight, "jabs and crosses")
    }

    @Test
    fun legIkKeepsBoneLengthsAndBendsKneesForward() {
        val out = FloatArray(6)
        val f = FloatArray(2)
        for (i in 0 until 36) {
            val ph = i * twoPi / 36
            JoggerMotion.foot(ph, 1, 4f, f)
            val fy = JoggerMotion.FOOT_Y + f[1]
            val fz = -f[0]
            JoggerMotion.twoBoneIk(0.095f, JoggerMotion.HIP_Y, 0f, 0.11f, fy, fz, JoggerMotion.THIGH, JoggerMotion.SHIN, 0f, 0f, -1f, out)
            assertEquals(JoggerMotion.THIGH, dist(0.095f, JoggerMotion.HIP_Y, 0f, out[0], out[1], out[2]), 1e-4f)
            assertEquals(JoggerMotion.SHIN, dist(out[0], out[1], out[2], out[3], out[4], out[5]), 1e-4f)
            // Knee ahead of the hip-to-foot line (toward -z).
            assertTrue(out[2] < fz / 2f + 1e-3f, "knee forward at phase $ph")
        }
    }

    @Test
    fun joggerMeshFitsAndHasNoNaN() {
        val w = MeshWriter(JoggerModel.MAX_VERTICES)
        val model = JoggerModel()
        val ride = RideState(3f)
        val jog = JoggerMotion()
        val ts = FixedTimestep()
        var maxVerts = 0
        ts.advanceTo(90.0) { dt ->
            ride.step(dt, 3f)
            jog.step(dt, ride.elapsedSeconds, ride.speed)
            if (ts.steps % 20 == 0L) {
                model.build(w, ride.elapsedSeconds, jog, JoggerMotion.lateral(ride.elapsedSeconds), -jog.gap, 0f)
                maxVerts = maxOf(maxVerts, w.vertexCount)
                for (i in 0 until w.vertexCount * MeshWriter.STRIDE) assertTrue(!w.floats[i].isNaN(), "NaN at float $i")
            }
        }
        println("jogger max $maxVerts vertices")
        assertTrue(maxVerts in 300..JoggerModel.MAX_VERTICES, "$maxVerts vertices")
        for (t in doubleArrayOf(0.0, 600.0, 7200.0)) {
            assertTrue(JoggerMotion.lateral(t) in 0.5f..1.4f, "stays in the right half of the lane")
        }
    }

    private fun dist(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = bx - ax; val dy = by - ay; val dz = bz - az
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
