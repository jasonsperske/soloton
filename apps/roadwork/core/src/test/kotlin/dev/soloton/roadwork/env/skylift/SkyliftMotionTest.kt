package dev.soloton.roadwork.env.skylift

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkyliftMotionTest {
    private val dt = 1f / 60f

    private fun run(m: SkyliftMotion, seconds: Float, speed: Float, resistance: Float, each: (SkyliftMotion) -> Unit = {}) {
        repeat((seconds * 60).toInt()) {
            m.step(dt, speed, resistance)
            each(m)
        }
    }

    @Test
    fun lowResistanceStaysOnTheGroundAndMoreResistanceLiftsHigher() {
        assertEquals(0f, SkyliftMotion.liftFor(0f))
        assertEquals(0f, SkyliftMotion.liftFor(SkyliftMotion.GROUND_BAND))
        assertEquals(0f, SkyliftMotion.liftFor(-1f))
        assertEquals(0f, SkyliftMotion.liftFor(Float.NaN))
        assertEquals(SkyliftMotion.MAX_ALTITUDE, SkyliftMotion.liftFor(1f), 1e-3f)
        assertEquals(SkyliftMotion.MAX_ALTITUDE, SkyliftMotion.liftFor(3f), 1e-3f)
        var last = 0f
        for (i in 16..100) {
            val lift = SkyliftMotion.liftFor(i / 100f)
            assertTrue(lift > last, "lift must rise with resistance at $i%")
            last = lift
        }
        // A middling setting is well clear of the ground but far from the top.
        assertTrue(SkyliftMotion.liftFor(0.5f) in 5f..25f, "lift at 50% is ${SkyliftMotion.liftFor(0.5f)}")
    }

    @Test
    fun altitudeEasesToTheLiftAndBackDown() {
        val m = SkyliftMotion(1L)
        run(m, 1f, 3f, 0.8f)
        val target = SkyliftMotion.liftFor(0.8f)
        assertTrue(m.altitude > 1f && m.altitude < target, "one second in the bike is still climbing: ${m.altitude}")
        run(m, 15f, 3f, 0.8f)
        assertEquals(target, m.altitude, 0.01f)
        run(m, 30f, 3f, 0f)
        assertEquals(0f, m.altitude, "back on the ground")

        val started = SkyliftMotion(1L)
        started.start(0.8f)
        assertEquals(target, started.altitude, "a scene created part way through a ride starts at height")
    }

    @Test
    fun trafficComesMoreOftenWithMoreResistanceAndNotAtAllWhenStopped() {
        assertEquals(0f, SkyliftMotion.spawnRate(0f, 1f))
        assertEquals(SkyliftMotion.BASE_RATE, SkyliftMotion.spawnRate(3f, 0f), 1e-6f)
        assertEquals(SkyliftMotion.FULL_RATE, SkyliftMotion.spawnRate(3f, 1f), 1e-6f)
        assertTrue(SkyliftMotion.spawnRate(0.5f, 1f) < SkyliftMotion.spawnRate(3f, 1f), "thinner when barely moving")

        val counts = listOf(0f, 0.5f, 1f).map { r -> SkyliftMotion(7L).also { run(it, 120f, 3f, r) }.spawned }
        assertTrue(counts[0] in 8..24, "about one every eight seconds on the ground: ${counts[0]}")
        assertTrue(counts[1] > counts[0] * 3, "$counts")
        assertTrue(counts[2] > counts[1] * 1.5, "$counts")

        val stopped = SkyliftMotion(7L).also { run(it, 120f, 0f, 1f) }
        assertEquals(0L, stopped.spawned)
    }

    @Test
    fun trafficStartsFarAheadPassesAndIsRetired() {
        val m = SkyliftMotion(3L)
        var maxLive = 0
        run(m, 300f, 4f, 1f) {
            maxLive = maxOf(maxLive, it.liveCount)
            var live = 0
            for (i in 0 until SkyliftMotion.MAX_TRAFFIC) {
                if (!it.alive[i]) continue
                live++
                assertTrue(it.z[i] >= -SkyliftMotion.SPAWN_DISTANCE - 0.01f && it.z[i] <= SkyliftMotion.PASSED, "z ${it.z[i]}")
                assertTrue(it.y[i] > 0f, "nothing is underground")
                if (it.kind[i] == SkyliftMotion.DRIFTER) assertTrue(it.y[i] >= SkyliftMotion.DRIFTER_FLOOR)
            }
            assertEquals(it.liveCount, live)
        }
        assertTrue(maxLive in 6..SkyliftMotion.MAX_TRAFFIC, "busy at full resistance, within the pool: $maxLive")
        // Stop sending more (the rider stops) and what is out there flies past and goes.
        run(m, 60f, 0f, 1f)
        assertEquals(0, m.liveCount)
    }

    @Test
    fun rollersOnlyComeWhileTheBikeIsOnTheGround() {
        val ground = SkyliftMotion(5L)
        var rollers = 0
        var drifters = 0
        val seen = HashSet<Long>()
        run(ground, 600f, 3f, 0f) {
            for (i in 0 until SkyliftMotion.MAX_TRAFFIC) {
                if (!it.alive[i] || it.z[i] > -SkyliftMotion.SPAWN_DISTANCE + 2f) continue
                if (seen.add(it.spawned * 100 + i)) if (it.kind[i] == SkyliftMotion.ROLLER) rollers++ else drifters++
            }
        }
        assertTrue(rollers > 5 && drifters > 5, "both kinds on the ground: $rollers rollers, $drifters drifters")

        val air = SkyliftMotion(5L)
        air.start(1f)
        run(air, 120f, 3f, 1f) {
            for (i in 0 until SkyliftMotion.MAX_TRAFFIC) {
                if (it.alive[i]) assertEquals(SkyliftMotion.DRIFTER, it.kind[i], "no rollers in the air")
            }
        }
    }

    @Test
    fun theRiderSwervesClearOfWhatIsInTheWayAndComesBack() {
        val m = SkyliftMotion(11L)
        var swerved = false
        var closest = Float.MAX_VALUE
        run(m, 600f, 4f, 0.6f) {
            assertTrue(abs(it.dodge) <= SkyliftMotion.MAX_DODGE + 1e-3f, "dodge ${it.dodge}")
            if (abs(it.dodge) > 1f) swerved = true
            // Whatever is level with the rider right now: how near does it come?
            for (i in 0 until SkyliftMotion.MAX_TRAFFIC) {
                if (!it.alive[i] || abs(it.z[i]) > 0.4f) continue
                if (abs(it.y[i] - it.riderHeight) > 1f) continue
                closest = minOf(closest, abs(it.x[i] - it.dodge))
            }
        }
        assertTrue(swerved, "ten minutes of traffic made the rider swerve at least once")
        assertTrue(closest > 1.2f, "something passed within $closest m of the rider")

        // With nothing left to avoid, the rider returns to the middle of the trail.
        run(m, 60f, 0f, 0.6f)
        assertEquals(0f, m.dodge)
        assertEquals(0f, m.dodgeSpeed)
    }

    @Test
    fun theSameSeedGivesTheSameRide() {
        fun trace(seed: Long): FloatArray {
            val m = SkyliftMotion(seed)
            val out = ArrayList<Float>()
            run(m, 90f, 3.5f, 0.7f)
            out += m.altitude
            out += m.dodge
            out += m.spawned.toFloat()
            for (i in 0 until SkyliftMotion.MAX_TRAFFIC) if (m.alive[i]) { out += m.x[i]; out += m.y[i]; out += m.z[i] }
            return out.toFloatArray()
        }
        assertContentEquals(trace(42L), trace(42L))
        assertTrue(!trace(42L).contentEquals(trace(43L)), "another seed gives other traffic")
    }
}
