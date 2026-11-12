package dev.soloton.roadwork.env.nightstreets

import dev.soloton.roadwork.RoadworkGame
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CityTest {
    private val seed = RoadworkGame.DEFAULT_SEED
    private val dt = 1f / 60f
    private val turns = listOf(CityGrid.LEFT, CityGrid.STRAIGHT, CityGrid.RIGHT)

    /** Distance from the nearest street centre line, in metres. */
    private fun fromCentreLine(v: Float): Float {
        val m = ((v % CityGrid.PERIOD) + CityGrid.PERIOD) % CityGrid.PERIOD
        return minOf(m, CityGrid.PERIOD - m)
    }

    /** True if (x, z) is on tarmac: within the half width of a street running either way. */
    private fun onStreet(x: Float, z: Float, margin: Float = 0f) =
        fromCentreLine(x) <= CityGrid.HALF_STREET - margin || fromCentreLine(z) <= CityGrid.HALF_STREET - margin

    // ---- legs ----

    @Test
    fun aLegRunsFromHalfABlockBeforeItsNodeToHalfABlockAfterInTheRightLane() {
        val p = FloatArray(3)
        for (lane in listOf(CityGrid.CAR_LANE to (CityTraffic.RIGHT_RADIUS to CityTraffic.LEFT_RADIUS), CityGrid.BIKE_LANE to (CityRider.RIGHT_RADIUS to CityRider.LEFT_RADIUS))) {
            val (offset, radii) = lane
            for (dir in 0..3) for (turn in turns) {
                val length = CityGrid.legLength(turn, offset, radii.first, radii.second)
                val fx = CityGrid.DX[dir].toFloat()
                val fz = CityGrid.DZ[dir].toFloat()
                // Start: half a block back along the way in, `offset` to the right (right of east is south).
                CityGrid.pose(dir, turn, offset, radii.first, radii.second, 0f, p)
                assertEquals(-fx * CityGrid.HALF - fz * offset, p[0], 1e-3f, "start x, dir $dir turn $turn")
                assertEquals(-fz * CityGrid.HALF + fx * offset, p[1], 1e-3f, "start z, dir $dir turn $turn")
                assertEquals(cos(p[2]), fx, 1e-3f)
                assertEquals(sin(p[2]), fz, 1e-3f)
                // End: half a block out along the way out, `offset` to the right of that.
                val out = CityGrid.outDir(dir, turn)
                val ox = CityGrid.DX[out].toFloat()
                val oz = CityGrid.DZ[out].toFloat()
                CityGrid.pose(dir, turn, offset, radii.first, radii.second, length, p)
                assertEquals(ox * CityGrid.HALF - oz * offset, p[0], 1e-2f, "end x, dir $dir turn $turn")
                assertEquals(oz * CityGrid.HALF + ox * offset, p[1], 1e-2f, "end z, dir $dir turn $turn")
                assertEquals(cos(p[2]), ox, 1e-3f)
                assertEquals(sin(p[2]), oz, 1e-3f)
                // In between: no jumps, always on the tarmac, and it moves a metre per metre.
                var lastX = -fx * CityGrid.HALF - fz * offset
                var lastZ = -fz * CityGrid.HALF + fx * offset
                var s = 0.25f
                while (s <= length) {
                    CityGrid.pose(dir, turn, offset, radii.first, radii.second, s, p)
                    val moved = sqrt((p[0] - lastX) * (p[0] - lastX) + (p[1] - lastZ) * (p[1] - lastZ))
                    assertEquals(0.25f, moved, 0.02f, "dir $dir turn $turn at $s m")
                    assertTrue(onStreet(p[0], p[1], 0.05f), "dir $dir turn $turn leaves the street at $s m: ${p[0]}, ${p[1]}")
                    lastX = p[0]
                    lastZ = p[1]
                    s += 0.25f
                }
            }
        }
        assertEquals(CityGrid.EAST, CityGrid.outDir(CityGrid.NORTH, CityGrid.RIGHT))
        assertEquals(CityGrid.WEST, CityGrid.outDir(CityGrid.NORTH, CityGrid.LEFT))
        assertEquals(CityGrid.NORTH, CityGrid.outDir(CityGrid.EAST, CityGrid.LEFT))
    }

    @Test
    fun theLightsGiveEachStreetGreenInTurnWithEveryLightRedBetween() {
        val seen = HashSet<Int>()
        var last = CityGrid.greenAxis(3, -2, 0f, seed)
        var t = 0f
        while (t < 3f * CityGrid.CYCLE_SECONDS) {
            val now = CityGrid.greenAxis(3, -2, t, seed)
            seen += now
            if (now != last) {
                // Green never passes straight from one street to the other.
                assertTrue(now == CityGrid.ALL_RED || last == CityGrid.ALL_RED, "lights went from $last to $now at $t s")
                last = now
            }
            t += 0.05f
        }
        assertEquals(setOf(CityGrid.EAST_WEST, CityGrid.NORTH_SOUTH, CityGrid.ALL_RED), seen)
        // Junctions are out of step with each other.
        val phases = (0 until 20).map { CityGrid.greenAxis(it, it * 3, 4f, seed) }.toSet()
        assertTrue(phases.size >= 2, "all junctions change together")
        assertEquals(CityGrid.EAST_WEST, CityGrid.axis(CityGrid.WEST))
        assertEquals(CityGrid.NORTH_SOUTH, CityGrid.axis(CityGrid.NORTH))
    }

    // ---- the rider ----

    @Test
    fun theRiderStaysInTheBikeLaneAndTurnsAtRandomWithoutEverDoublingBack() {
        val r = CityRider(seed)
        val counts = IntArray(3)
        var lastLegs = 0L
        var lastDir = r.inDir
        counts[r.turn + 1]++
        val nodes = HashSet<Pair<Int, Int>>()
        // An hour and a half at 4 m/s: over two hundred junctions.
        repeat(60 * 60 * 90) {
            val bx = r.x
            val bz = r.z
            r.step(dt, 4f)
            assertEquals(4f * dt, sqrt((r.x - bx) * (r.x - bx) + (r.z - bz) * (r.z - bz)), 0.01f, "the rider jumped")
            assertTrue(onStreet(r.x, r.z, 0.05f), "the rider left the street at ${r.x}, ${r.z}")
            if (r.legs != lastLegs) {
                lastLegs = r.legs
                assertTrue(r.inDir != (lastDir + 2) % 4, "the rider doubled back")
                lastDir = r.inDir
                counts[r.turn + 1]++
                nodes += r.nodeX to r.nodeZ
            }
            assertTrue(abs(r.turnRate) < 2.5f, "turning ${r.turnRate} rad/s")
        }
        val total = counts.sum()
        assertTrue(total > 200, "$total junctions")
        assertTrue(counts[1] > total * 0.38 && counts[1] < total * 0.62, "straight on ${counts[1]} of $total")
        assertTrue(counts[0] > total * 0.15 && counts[2] > total * 0.15, "left ${counts[0]}, right ${counts[2]} of $total")
        assertTrue(nodes.size > 60, "the ride wanders: ${nodes.size} different junctions")
    }

    @Test
    fun onAStraightTheRiderIsInTheBikeLaneAtTheKerb() {
        val r = CityRider(seed)
        repeat(60 * 600) {
            r.step(dt, 3f)
            // Well away from any junction, the rider is 4.2 m from the centre line of the street they are on.
            val alongStreet = if (r.inDir % 2 == 0) r.x else r.z
            if (fromCentreLine(alongStreet) > 20f && r.along < 20f) {
                val across = if (r.inDir % 2 == 0) r.z else r.x
                assertEquals(CityGrid.BIKE_LANE, fromCentreLine(across), 0.01f)
            }
        }
    }

    @Test
    fun theSameSeedGivesTheSameRouteAndStoppingStopsTheRider() {
        fun route(s: Long): List<Float> {
            val r = CityRider(s)
            repeat(60 * 900) { r.step(dt, 5f) }
            return listOf(r.x, r.z, r.heading, r.legs.toFloat())
        }
        assertEquals(route(11L), route(11L))
        assertTrue(route(11L) != route(12L), "another seed gives another route")

        val r = CityRider(seed)
        repeat(600) { r.step(dt, 3f) }
        val x = r.x
        val z = r.z
        repeat(600) { r.step(dt, 0f) }
        assertEquals(x, r.x)
        assertEquals(z, r.z)
        assertEquals(0f, r.turnRate)
    }

    // ---- the cars ----

    private class Run(val rider: CityRider, val traffic: CityTraffic)

    private fun run(minutes: Int, riderSpeed: Float, each: (Run) -> Unit): Run {
        val run = Run(CityRider(seed), CityTraffic(seed))
        run.traffic.populate(run.rider)
        repeat(60 * 60 * minutes) {
            run.rider.step(dt, riderSpeed)
            run.traffic.step(dt, run.rider)
            each(run)
        }
        return run
    }

    @Test
    fun carsKeepToTheStreetsStayNearTheRiderAndKeepMoving() {
        var fewest = Int.MAX_VALUE
        var moving = 0L
        var samples = 0L
        val run = run(20, 4f) { r ->
            val t = r.traffic
            fewest = minOf(fewest, t.liveCount)
            var live = 0
            for (i in 0 until CityTraffic.MAX_CARS) {
                if (!t.alive[i]) continue
                live++
                assertTrue(onStreet(t.x[i], t.z[i], 0.6f), "car $i left the street at ${t.x[i]}, ${t.z[i]}")
                assertTrue(t.speed[i] in 0f..CityTraffic.CRUISE + 0.01f, "car $i at ${t.speed[i]} m/s")
                assertTrue(maxOf(abs(t.nodeX[i] - r.rider.nodeX), abs(t.nodeZ[i] - r.rider.nodeZ)) <= CityTraffic.RANGE + 1)
                samples++
                if (t.speed[i] > 1f) moving++
            }
            assertEquals(t.liveCount, live)
        }
        assertTrue(fewest >= CityTraffic.MAX_CARS * 2 / 3, "as few as $fewest cars about")
        assertTrue(run.traffic.liveCount > 0)
        // Not gridlocked: cars are under way most of the time.
        assertTrue(moving > samples * 0.55, "cars were moving ${100 * moving / samples}% of the time")
    }

    @Test
    fun carsDoNotRunIntoEachOtherOrTheRider() {
        // Up to 12 m/s: a hard pedal with the city's speed turned well up.
        for (riderSpeed in listOf(3f, 6f, 12f)) {
            var closestCars = Float.MAX_VALUE
            var closestToRider = Float.MAX_VALUE
            run(20, riderSpeed) { r ->
                val t = r.traffic
                for (i in 0 until CityTraffic.MAX_CARS) {
                    if (!t.alive[i]) continue
                    val rx = t.x[i] - r.rider.x
                    val rz = t.z[i] - r.rider.z
                    closestToRider = minOf(closestToRider, sqrt(rx * rx + rz * rz))
                    for (j in i + 1 until CityTraffic.MAX_CARS) {
                        if (!t.alive[j]) continue
                        val dx = t.x[i] - t.x[j]
                        val dz = t.z[i] - t.z[j]
                        closestCars = minOf(closestCars, sqrt(dx * dx + dz * dz))
                    }
                }
            }
            // Centres: two cars side by side in opposite lanes are 3.4 m apart; nose to tail they are a car's length.
            assertTrue(closestCars > 3.2f, "two cars' centres came within $closestCars m")
            // The rider in the bike lane is 2.5 m from the middle of the car lane beside it.
            assertTrue(closestToRider > 2.2f, "a car's centre came within $closestToRider m of the rider")
        }
    }

    @Test
    fun noCarEntersAJunctionAgainstARedLightOrWhileTheRiderIsNear() {
        val wasShort = BooleanArray(CityTraffic.MAX_CARS)
        var crossings = 0
        run(20, 4f) { r ->
            val t = r.traffic
            for (i in 0 until CityTraffic.MAX_CARS) {
                if (!t.alive[i]) { wasShort[i] = false; continue }
                val short = t.along[i] < CityTraffic.STOP_AT
                if (wasShort[i] && !short) {
                    crossings++
                    // The step it crossed the line: its street had green a moment ago, and the rider was not at this junction.
                    val green = CityGrid.greenAxis(t.nodeX[i], t.nodeZ[i], t.time - dt, seed) == CityGrid.axis(t.inDir[i]) ||
                        CityGrid.greenAxis(t.nodeX[i], t.nodeZ[i], t.time - 1.5f, seed) == CityGrid.axis(t.inDir[i])
                    assertTrue(green, "car $i crossed the line on red at ${t.time} s")
                    val riderHere = t.nodeX[i] == r.rider.nodeX && t.nodeZ[i] == r.rider.nodeZ
                    assertTrue(!riderHere || r.rider.distanceToNode >= CityTraffic.RIDER_PRIORITY - 3f, "car $i entered the rider's junction")
                }
                wasShort[i] = short
            }
        }
        assertTrue(crossings > 300, "$crossings cars went through junctions in twenty minutes")
    }

    @Test
    fun theSameSeedGivesTheSameTraffic() {
        fun trace(): List<Float> {
            val r = run(3, 4f) {}
            val out = ArrayList<Float>()
            for (i in 0 until CityTraffic.MAX_CARS) if (r.traffic.alive[i]) { out += r.traffic.x[i]; out += r.traffic.z[i]; out += r.traffic.speed[i] }
            return out
        }
        val a = trace()
        assertTrue(a.size > 15)
        assertEquals(a, trace())
    }
}
