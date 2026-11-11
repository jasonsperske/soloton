package dev.soloton.roadwork.env.pedalcraft

import dev.soloton.roadwork.RoadworkGame
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlightTest {
    private val terrain = IslandTerrain(RoadworkGame.DEFAULT_SEED)
    private val dt = 1f / 60f

    // ---- the island ----

    @Test
    fun theIslandIsLandInTheMiddleAndSeaAllRound() {
        assertTrue(terrain.height(0f, 0f) > 20f)
        assertTrue(terrain.height(IslandTerrain.PEAK_X, IslandTerrain.PEAK_Z) > 350f, "the peak")
        for (i in 0 until 72) {
            val a = (i * 5f) * (Math.PI / 180.0).toFloat()
            val shore = terrain.coastRadius(a)
            assertTrue(shore in 1200f..2600f, "shore $shore m out at ${i * 5} degrees")
            val c = kotlin.math.cos(a)
            val s = kotlin.math.sin(a)
            assertTrue(terrain.height(c * shore * 0.9f, s * shore * 0.9f) > 0f, "land inside the shore at ${i * 5} degrees")
            assertTrue(terrain.height(c * shore * 1.12f, s * shore * 1.12f) < 0f, "sea outside the shore at ${i * 5} degrees")
            assertEquals(0f, terrain.ground(c * 4000f, s * 4000f))
        }
    }

    @Test
    fun theLandingSpotsAreLevelGroundAboveTheSea() {
        for (spot in terrain.spots) {
            for (along in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
                for (across in listOf(-12f, 0f, 12f)) {
                    val x = spot.x + spot.dirX * spot.run * along - spot.dirZ * across
                    val z = spot.z + spot.dirZ * spot.run * along + spot.dirX * across
                    assertEquals(spot.height, terrain.height(x, z), 0.05f, "${spot.name} at $along of its run, $across m across")
                }
            }
            assertTrue(spot.height > 1.5f, "${spot.name} is above the water")
            assertEquals(spot, terrain.spotAt(spot.x, spot.z))
        }
        // The beach is at the shore, the hill strip well up the mountain, the airfield on low ground.
        val beach = terrain.beach
        val shore = terrain.coastRadius(atan2(beach.z, beach.x))
        assertTrue(sqrt(beach.x * beach.x + beach.z * beach.z) in shore * 0.8f..shore, "the beach is by the sea")
        assertTrue(terrain.hill.height > 150f && terrain.airfield.height < 20f)
        assertEquals(null, terrain.spotAt(0f, 0f))
    }

    @Test
    fun theSameSeedGivesTheSameIsland() {
        val again = IslandTerrain(RoadworkGame.DEFAULT_SEED)
        val other = IslandTerrain(RoadworkGame.DEFAULT_SEED + 1)
        var differs = false
        for (i in 0 until 200) {
            val x = -1500f + i * 15f
            val z = 700f - i * 9f
            assertEquals(terrain.height(x, z), again.height(x, z))
            if (abs(terrain.height(x, z) - other.height(x, z)) > 0.5f) differs = true
        }
        assertTrue(differs, "another seed changes the small bumps")
    }

    // ---- the routes ----

    @Test
    fun theTourJoinsUpAndEachLegTakesFifteenToThirtyMinutes() {
        val m = FlightMotion(terrain)
        assertEquals(listOf(Craft.GLIDER, Craft.PARAMOTOR, Craft.PLANE), m.legs.map { it.craft })
        for ((i, leg) in m.legs.withIndex()) {
            val next = m.legs[(i + 1) % m.legs.size]
            assertEquals(leg.route.end, next.route.start, "${leg.craft} lands where ${next.craft} takes off")
            val minutes = leg.route.length / (leg.craft.gearing * FlightMotion.REFERENCE_SPEED) / 60f
            assertTrue(minutes in 15f..30f, "${leg.craft} takes $minutes minutes")
        }
        assertEquals(terrain.hill, m.legs.first().route.start)
    }

    @Test
    fun routesStartAndEndOnTheirStripsAndTurnGently() {
        for (leg in FlightMotion(terrain).legs) {
            val r = leg.route
            assertEquals(r.start.height, terrain.height(r.x(0f), r.z(0f)), 0.05f, "${leg.craft} starts on its strip")
            assertEquals(r.end.height, terrain.height(r.x(r.length), r.z(r.length)), 0.05f, "${leg.craft} stops on its strip")
            assertEquals(r.end.height, terrain.height(r.x(r.touchdown), r.z(r.touchdown)), 0.05f, "${leg.craft} touches down on its strip")
            assertTrue(r.liftOff < r.touchdown && r.touchdown < r.length)
            assertEquals(0f, r.airborne(0f))
            assertEquals(0f, r.airborne(r.length))
            assertEquals(1f, r.airborne(r.length / 2f))
            // No kinks: the heading never swings more than a few degrees in ten metres.
            var s = 20f
            var sharpest = 0f
            while (s < r.length - 20f) {
                var turn = r.heading(s + 10f) - r.heading(s)
                while (turn > Math.PI) turn -= (2 * Math.PI).toFloat()
                while (turn < -Math.PI) turn += (2 * Math.PI).toFloat()
                sharpest = maxOf(sharpest, abs(turn))
                s += 10f
            }
            // A turn radius of at least 60 m.
            assertTrue(sharpest < 10f / 60f, "${leg.craft} turns ${Math.toDegrees(sharpest.toDouble())} degrees in 10 m")
        }
    }

    @Test
    fun noCraftEverFliesIntoTheGroundWhateverTheResistance() {
        for (leg in FlightMotion(terrain).legs) {
            val r = leg.route
            for (lift in listOf(0f, leg.craft.liftRange)) {
                var s = 0f
                var lowest = Float.MAX_VALUE
                while (s <= r.length) {
                    val above = r.heightAt(s, lift, leg.craft.seat) - terrain.ground(r.x(s), r.z(s))
                    assertTrue(above >= leg.craft.seat - 0.01f, "${leg.craft} at $s m is $above m above the ground")
                    if (r.airborne(s) >= 1f) lowest = minOf(lowest, above)
                    s += 5f
                }
                assertTrue(lowest >= leg.craft.clearance * 0.5f, "${leg.craft} cruises as low as $lowest m over the ground")
            }
            // The cruising height never jumps: under 0.6 m per metre flown.
            var s = 0f
            while (s < r.length - 10f) {
                assertTrue(abs(r.cruise(s + 10f) - r.cruise(s)) < 6f, "${leg.craft} cruise height steps at $s m")
                s += 10f
            }
        }
    }

    @Test
    fun anApproachHoldsItsHeightOverHigherGroundUntilTheStripIsClearAhead() {
        for (leg in FlightMotion(terrain).legs) {
            val r = leg.route
            var s = r.touchdown - FlightRoute.DESCENT
            while (s < r.touchdown) {
                // On the way down the craft is above all the ground between it and the touchdown point
                // (as far as it looks ahead), not skimming up a slope toward it.
                val y = r.heightAt(s, 0f, leg.craft.seat)
                var ahead = s
                while (ahead <= minOf(s + FlightRoute.AHEAD - 10f, r.touchdown)) {
                    assertTrue(
                        y >= terrain.ground(r.x(ahead), r.z(ahead)) + leg.craft.seat - 0.3f,
                        "${leg.craft} ${r.touchdown - s} m from touchdown is at $y m, under ground ${ahead - s} m ahead",
                    )
                    ahead += 10f
                }
                s += 10f
            }
            assertEquals(r.end.height + leg.craft.seat, r.heightAt(r.touchdown, 0f, leg.craft.seat), 0.1f, "${leg.craft} touches down on the strip")
            assertEquals(r.start.height + leg.craft.seat, r.heightAt(0f, leg.craft.liftRange, leg.craft.seat), 0.1f, "${leg.craft} starts on the ground")
            assertEquals(r.start.height + leg.craft.seat, r.heightAt(r.liftOff, 0f, leg.craft.seat), 0.1f, "${leg.craft} rolls to lift-off")
        }
    }

    // ---- flying ----

    @Test
    fun pedallingFliesTheRouteAndStoppingHoldsStill() {
        val m = FlightMotion(terrain)
        assertEquals(terrain.hill.height + Craft.GLIDER.seat, m.y, 0.05f, "starts on the hill strip")
        repeat(60 * 120) { m.step(dt, 3f, 0f) }
        assertEquals(Craft.GLIDER.gearing * 3f * 120f, m.travel, 2f)
        assertEquals(Craft.GLIDER.gearing * 3f, m.airspeed, 1e-3f)
        assertTrue(m.heightAboveGround > 30f, "two minutes in the glider is flying: ${m.heightAboveGround} m up")
        val at = m.travel
        repeat(60 * 30) { m.step(dt, 0f, 0f) }
        assertEquals(at, m.travel, "no pedalling, no progress")
        assertEquals(0f, m.airspeed)
    }

    @Test
    fun resistanceRaisesTheCraftAndNeedsPedallingToHold() {
        fun heightAfter(resistance: Float, speed: Float = 3f): Float {
            val m = FlightMotion(terrain)
            repeat(60 * 180) { m.step(dt, 3f, resistance) }
            repeat(60 * 20) { m.step(dt, speed, resistance) }
            return m.y - m.leg.route.cruise(m.travel)
        }
        assertEquals(0f, heightAfter(0f), 0.5f)
        assertEquals(Craft.GLIDER.liftRange * 0.5f, heightAfter(0.5f), 1f)
        assertEquals(Craft.GLIDER.liftRange, heightAfter(1f), 1f)
        assertEquals(Craft.GLIDER.liftRange, heightAfter(4f), 1f, "resistance is clamped")
        assertEquals(0f, heightAfter(Float.NaN), 0.5f)
        // Stop pedalling and the lift goes; the craft settles back to its cruising height.
        assertEquals(0f, heightAfter(1f, speed = 0f), 1f)
    }

    @Test
    fun eachCraftLandsAndTheNextTakesOffFromTheSameSpot() {
        val m = FlightMotion(terrain)
        val seen = ArrayList<Craft>()
        var lowestInFlight = Float.MAX_VALUE
        var steepestBank = 0f
        // Ride hard so the whole tour and a bit more fits in the test: 6 m/s for 45 minutes.
        repeat(60 * 60 * 45) {
            val before = m.legIndex
            val wasY = m.y
            m.step(dt, 6f, 0.5f)
            if (seen.isEmpty() || m.legIndex != before) {
                seen += m.craft
                if (m.legIndex != before) {
                    val spot = m.leg.route.start
                    assertEquals(spot.height + m.craft.seat, m.y, 0.3f, "${m.craft} starts on the ground at the ${spot.name}")
                    assertTrue(abs(wasY - spot.height) < 2f, "the craft before it had landed there (was at $wasY m)")
                }
            }
            if (m.leg.route.airborne(m.travel) >= 1f) lowestInFlight = minOf(lowestInFlight, m.heightAboveGround)
            steepestBank = maxOf(steepestBank, abs(m.bankDegrees))
            assertTrue(abs(m.pitchDegrees) <= FlightMotion.MAX_PITCH + 0.01f)
        }
        assertEquals(listOf(Craft.GLIDER, Craft.PARAMOTOR, Craft.PLANE, Craft.GLIDER), seen.take(4), "the tour repeats")
        assertTrue(m.legsFlown >= 3)
        assertTrue(lowestInFlight > 15f, "in flight the lowest pass over the ground was $lowestInFlight m")
        assertTrue(steepestBank in 3f..FlightMotion.MAX_BANK + 0.01f, "banks into turns, up to $steepestBank degrees")
    }
}
