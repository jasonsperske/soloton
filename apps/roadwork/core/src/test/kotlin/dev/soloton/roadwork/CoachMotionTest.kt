package dev.soloton.roadwork

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoachMotionTest {
    private val twoPi = (2 * PI).toFloat()

    private fun angleDiff(a: Float, b: Float): Float {
        val d = CoachMotion.wrap(a - b)
        return if (d > PI) twoPi - d else d
    }

    @Test
    fun wheelAndCrankFollowDistance() {
        assertEquals(1f / CoachMotion.WHEEL_RADIUS, CoachMotion.wheelAngleFor(1f), 1e-6f)
        // One full wheel turn covers its circumference; the crank turns 1 / gear as far.
        val circumference = twoPi * CoachMotion.WHEEL_RADIUS
        assertEquals(twoPi, CoachMotion.wheelAngleFor(circumference), 1e-5f)
        assertEquals(twoPi / CoachMotion.GEAR_RATIO, CoachMotion.crankAngleFor(circumference), 1e-5f)
    }

    @Test
    fun integratedWheelAngleMatchesDistanceRidden() {
        val m = CoachMotion()
        val ts = FixedTimestep()
        var ridden = 0.0
        ts.advanceTo(45.0) { dt ->
            m.step(dt, ts.simulatedSeconds, 2.7f)
            ridden += m.speed * dt
        }
        assertEquals(0f, angleDiff(m.wheelAngle, CoachMotion.wrap((ridden / CoachMotion.WHEEL_RADIUS % (2 * PI)).toFloat())), 2e-3f)
        val crank = (ridden / CoachMotion.WHEEL_RADIUS / CoachMotion.GEAR_RATIO % (2 * PI)).toFloat()
        assertEquals(0f, angleDiff(m.crankAngle, crank), 2e-3f)
        assertTrue(m.wheelAngle in 0f..twoPi && m.crankAngle in 0f..twoPi)
    }

    @Test
    fun pedalCircleBottomTopAndDirection() {
        val p = FloatArray(2)
        CoachMotion.pedal(0f, p)
        assertEquals(0f, p[0], 1e-6f); assertEquals(-CoachMotion.CRANK_LENGTH, p[1], 1e-6f)
        CoachMotion.pedal(PI.toFloat(), p)
        assertEquals(CoachMotion.CRANK_LENGTH, p[1], 1e-6f)
        // Pedalling forward: the top pedal moves toward -z (forward).
        val q = FloatArray(2)
        CoachMotion.pedal(PI.toFloat() + 0.01f, q)
        assertTrue(q[0] < p[0])
        for (i in 0 until 36) {
            CoachMotion.pedal(i * 0.1745f, p)
            assertEquals(CoachMotion.CRANK_LENGTH, sqrt(p[0] * p[0] + p[1] * p[1]), 1e-5f)
        }
    }

    @Test
    fun legIkKeepsBoneLengthsAndBendsKneeForward() {
        val out = FloatArray(6)
        val p = FloatArray(2)
        for (i in 0 until 24) {
            val crank = i * twoPi / 24
            CoachMotion.pedal(crank, p)
            val fy = CoachModel.BB_Y + p[1] + 0.07f
            val fz = CoachModel.BB_Z + p[0] + 0.02f
            CoachMotion.twoBoneIk(0.1f, 1.0f, 0.26f, 0.13f, fy, fz, CoachModel.THIGH, CoachModel.SHIN, 0.15f, 0.2f, -1f, out)
            val thigh = dist(0.1f, 1.0f, 0.26f, out[0], out[1], out[2])
            val shin = dist(out[0], out[1], out[2], out[3], out[4], out[5])
            assertEquals(CoachModel.THIGH, thigh, 1e-4f)
            assertEquals(CoachModel.SHIN, shin, 1e-4f)
            // The foot reaches the pedal at every crank angle (the leg is long enough).
            assertEquals(0f, dist(out[3], out[4], out[5], 0.13f, fy, fz), 1e-3f)
            // Knee ahead of the hip-to-foot line (toward -z).
            assertTrue(out[2] < (0.26f + fz) / 2f, "knee forward at crank $crank")
        }
    }

    @Test
    fun armIkReachesGrips() {
        val out = FloatArray(6)
        CoachMotion.twoBoneIk(0.21f, 1.5f, 0.02f, 0.29f, 1.02f, -0.22f, CoachModel.UPPER_ARM, CoachModel.FOREARM, 1f, -0.4f, 0.6f, out)
        assertEquals(0f, dist(out[3], out[4], out[5], 0.29f, 1.02f, -0.22f), 1e-3f)
        assertTrue(out[0] > 0.21f, "elbow points outward")
    }

    @Test
    fun coachStaysAheadAndGapRateIsTheDerivative() {
        var t = 0.0
        while (t < 7200.0) {
            val g = CoachMotion.gap(t)
            assertTrue(g in 5.5f..9.0f, "gap $g at $t")
            assertTrue(CoachMotion.lateral(t) in 0.4f..1.5f)
            val numeric = (CoachMotion.gap(t + 1e-3) - CoachMotion.gap(t - 1e-3)) / 2e-3
            assertEquals(numeric, CoachMotion.gapRate(t).toDouble(), 2e-3)
            t += 3.7
        }
    }

    @Test
    fun glancesAndWavesHappenAndStayInRange() {
        var glanced = false
        var waved = false
        var t = 0.0
        while (t < 120.0) {
            val g = CoachMotion.glance(t)
            val w = CoachMotion.wave(t)
            assertTrue(g in 0f..1f && w in 0f..1f)
            if (g > 0.99f) glanced = true
            if (w > 0.99f) waved = true
            assertTrue(abs(CoachMotion.headYaw(t)) <= 105f)
            t += 0.05
        }
        assertTrue(glanced && waved)
        assertEquals(0f, CoachMotion.glance(0.0))
    }

    @Test
    fun coachMeshFitsAndHasNoNaN() {
        val w = MeshWriter(CoachModel.MAX_VERTICES)
        val model = CoachModel()
        val m = CoachMotion()
        val ts = FixedTimestep()
        var maxVerts = 0
        ts.advanceTo(90.0) { dt ->
            m.step(dt, ts.simulatedSeconds, 2.7f)
            if (ts.steps % 30 == 0L) {
                model.build(w, ts.simulatedSeconds, m, 1f, -7f)
                maxVerts = maxOf(maxVerts, w.vertexCount)
                for (i in 0 until w.vertexCount * MeshWriter.STRIDE) assertTrue(!w.floats[i].isNaN(), "NaN at float $i")
            }
        }
        assertTrue(maxVerts in 300..CoachModel.MAX_VERTICES)
    }

    private fun dist(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = bx - ax; val dy = by - ay; val dz = bz - az
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
