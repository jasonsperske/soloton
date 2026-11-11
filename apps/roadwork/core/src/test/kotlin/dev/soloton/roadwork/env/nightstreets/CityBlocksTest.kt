package dev.soloton.roadwork.env.nightstreets

import dev.soloton.roadwork.Environments
import dev.soloton.roadwork.MeshWriter
import dev.soloton.roadwork.Rng
import dev.soloton.roadwork.RoadworkGame
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CityBlocksTest {
    private val seed = RoadworkGame.DEFAULT_SEED

    private fun x(w: MeshWriter, v: Int) = w.floats[v * MeshWriter.STRIDE]
    private fun y(w: MeshWriter, v: Int) = w.floats[v * MeshWriter.STRIDE + 1]
    private fun z(w: MeshWriter, v: Int) = w.floats[v * MeshWriter.STRIDE + 2]

    @Test
    fun theEnvironmentIsRegisteredWithItsMusicFolderAndTheViewFromTheSaddle() {
        val e = Environments.find("night-streets")
        assertEquals(NightStreets.environment, e)
        assertEquals("Night Streets", e?.title)
        assertEquals("Sim City", e?.musicFolder)
        val scene = NightStreets.environment.createScene(seed)
        assertTrue(scene.riderView, "the rider sees their handlebars")
        assertEquals(listOf("overhead"), scene.inspectionViews)
        assertTrue(scene.setInspectionView("overhead"))
        assertTrue(!scene.riderView, "the overhead camera has no handlebars")
    }

    @Test
    fun theCityHasEveryKindOfBlockInSensibleShares() {
        val counts = IntArray(4)
        for (i in -40 until 40) for (j in -40 until 40) counts[CityBlocks.zone(i, j, seed)]++
        val total = counts.sum().toFloat()
        assertTrue(counts[CityBlocks.PARK] / total in 0.06f..0.14f, "parks ${counts[CityBlocks.PARK] / total}")
        for (zone in listOf(CityBlocks.DOWNTOWN, CityBlocks.MIDRISE, CityBlocks.HOUSES)) {
            assertTrue(counts[zone] / total > 0.12f, "zone $zone is ${counts[zone] / total} of the city")
        }
        // Zones come in districts: a block's neighbour is the same kind more often than chance would have it.
        var same = 0
        var pairs = 0
        for (i in -40 until 39) for (j in -40 until 40) {
            pairs++
            if (CityBlocks.zone(i, j, seed) == CityBlocks.zone(i + 1, j, seed)) same++
        }
        assertTrue(same > pairs * 0.5, "$same of $pairs neighbours match")
        assertEquals(CityBlocks.zone(7, -3, seed), CityBlocks.zone(7, -3, seed))
    }

    @Test
    fun cellsStayInBudgetAndNothingIsBuiltOnTheStreet() {
        val w = MeshWriter(CityBlocks.CELL_MAX_VERTICES)
        val rng = Rng(0)
        var most = 0
        var total = 0L
        val seen = HashSet<Int>()
        var cells = 0
        for (i in -12..12) for (j in -12..12) {
            CityBlocks.buildCell(w, i, j, seed, rng) // throws if it overflows the writer
            most = maxOf(most, w.vertexCount)
            total += w.vertexCount
            cells++
            seen += CityBlocks.zone(i, j, seed)
            val kerb = CityGrid.HALF_STREET
            val farKerb = CityGrid.PERIOD - CityGrid.HALF_STREET
            var tallest = 0f
            for (v in 0 until w.vertexCount) {
                val vx = x(w, v)
                val vy = y(w, v)
                val vz = z(w, v)
                assertTrue(vy >= -0.01f, "cell $i,$j: a vertex under the ground")
                tallest = maxOf(tallest, vy)
                if (vy < 0.5f) continue
                // Anything standing up is on the block (the signal posts stand just off the junction's corners).
                // A street lamp's head leans out over the kerb, well above the traffic.
                val lean = if (vy > 5f) 1f else 0f
                val onBlock = vx > kerb - lean && vx < farKerb + lean && vz > kerb - lean && vz < farKerb + lean
                val signalPost = abs(abs(vx) - CityBlocks.SIGNAL_CORNER) < 0.2f && abs(abs(vz) - CityBlocks.SIGNAL_CORNER) < 0.2f
                assertTrue(onBlock || signalPost, "cell $i,$j: something stands in the street at $vx, $vy, $vz")
            }
            when (CityBlocks.zone(i, j, seed)) {
                CityBlocks.DOWNTOWN -> assertTrue(tallest > 45f, "a downtown block only $tallest m tall")
                CityBlocks.HOUSES, CityBlocks.PARK -> assertTrue(tallest < 15f, "a $tallest m building among the houses")
                else -> assertTrue(tallest in 13f..45f, "a mid-rise block $tallest m tall")
            }
        }
        assertEquals(4, seen.size, "every kind of block turned up")
        assertTrue(most <= CityBlocks.CELL_MAX_VERTICES && most > 3000, "the biggest cell has $most vertices")
        // Up to 49 cells are held and about half drawn: on average that must fit the tablet's 60k triangles a frame.
        val average = total / cells
        assertTrue(average * 30 / 3 < 45_000, "an average cell has $average vertices")
    }

    @Test
    fun aCellIsTheSameEveryTimeAndDiffersFromItsNeighbour() {
        val a = MeshWriter(CityBlocks.CELL_MAX_VERTICES)
        val b = MeshWriter(CityBlocks.CELL_MAX_VERTICES)
        CityBlocks.buildCell(a, 5, -9, seed, Rng(1))
        CityBlocks.buildCell(b, 5, -9, seed, Rng(77))
        assertEquals(a.vertexCount, b.vertexCount)
        assertTrue(a.floats.copyOf(a.vertexCount * MeshWriter.STRIDE).contentEquals(b.floats.copyOf(b.vertexCount * MeshWriter.STRIDE)))
        CityBlocks.buildCell(b, 6, -9, seed, Rng(1))
        assertTrue(!a.floats.copyOf(4000).contentEquals(b.floats.copyOf(4000)))
    }

    @Test
    fun theSkylineStandsWellBeyondTheCityThatIsBuilt() {
        val w = MeshWriter(CityBlocks.SKYLINE_MAX_VERTICES)
        CityBlocks.buildSkyline(w, seed, Rng(0))
        assertTrue(w.vertexCount > CityBlocks.SKYLINE_TOWERS * 100, "${w.vertexCount} vertices")
        val reach = (NightStreetsScene.REACH + 1) * CityGrid.PERIOD
        for (v in 0 until w.vertexCount) {
            val far = sqrt(x(w, v) * x(w, v) + z(w, v) * z(w, v))
            assertTrue(far > reach + 100f, "a skyline tower only $far m off; cells reach $reach m")
        }
    }

    @Test
    fun aCarIsFourMetresLongSitsOnTheRoadAndPointsItsHeadlightsTheWayItIsGoing() {
        val rider = CityRider(seed)
        val traffic = CityTraffic(seed)
        traffic.populate(rider)
        assertEquals(CityTraffic.MAX_CARS, traffic.liveCount)
        val w = MeshWriter(CityBlocks.CARS_MAX_VERTICES)
        CityBlocks.buildCars(w, traffic, rider.x, rider.z)
        assertEquals(CityTraffic.MAX_CARS * CityBlocks.CAR_VERTICES, w.vertexCount)
        var car = 0
        for (i in 0 until CityTraffic.MAX_CARS) {
            if (!traffic.alive[i]) continue
            val first = car * CityBlocks.CAR_VERTICES
            car++
            val cx = traffic.x[i] - rider.x
            val cz = traffic.z[i] - rider.z
            val fx = cos(traffic.heading[i])
            val fz = sin(traffic.heading[i])
            var front = -Float.MAX_VALUE
            var back = Float.MAX_VALUE
            var widest = 0f
            for (v in first until first + CityBlocks.CAR_VERTICES) {
                val along = (x(w, v) - cx) * fx + (z(w, v) - cz) * fz
                val across = -(x(w, v) - cx) * fz + (z(w, v) - cz) * fx
                front = maxOf(front, along)
                back = minOf(back, along)
                widest = maxOf(widest, abs(across))
                assertTrue(y(w, v) in 0.29f..1.51f, "car $i: a vertex at ${y(w, v)} m up")
            }
            assertEquals(CityTraffic.CAR_LENGTH / 2f, front, 0.15f, "car $i's nose")
            assertEquals(-CityTraffic.CAR_LENGTH / 2f, back, 0.15f, "car $i's tail")
            assertEquals(CityTraffic.CAR_WIDTH / 2f, widest, 0.02f, "car $i's width")
            // The headlights are the two boxes after the body and cabin: they are at the nose.
            val headlight = first + 2 * 36
            val along = (x(w, headlight) - cx) * fx + (z(w, headlight) - cz) * fz
            assertTrue(along > CityTraffic.CAR_LENGTH / 2f - 0.2f, "car $i's headlights are ${along} m along, not at its nose")
        }
    }

    @Test
    fun everyJunctionNearbyShowsFourLampsAndTheyAgreeWithTheLights() {
        val w = MeshWriter(CityBlocks.SIGNALS_MAX_VERTICES)
        CityBlocks.buildSignals(w, 2, -1, 37f, seed, 180f, -90f)
        assertEquals(CityBlocks.SIGNALS_MAX_VERTICES, w.vertexCount)
        for (v in 0 until w.vertexCount) {
            assertTrue(y(w, v) > CityBlocks.SIGNAL_HEIGHT - 0.01f, "a lamp below the top of its post")
            // Each lamp is over a signal post: just off a corner of some junction.
            val wx = x(w, v) + 180f
            val wz = z(w, v) - 90f
            val dx = abs(wx - Math.round(wx / CityGrid.PERIOD) * CityGrid.PERIOD)
            val dz = abs(wz - Math.round(wz / CityGrid.PERIOD) * CityGrid.PERIOD)
            assertEquals(CityBlocks.SIGNAL_CORNER, dx, 0.31f)
            assertEquals(CityBlocks.SIGNAL_CORNER, dz, 0.31f)
        }
        // The lamps change with the lights: a full cycle later they are the same, half a cycle later they are not.
        val later = MeshWriter(CityBlocks.SIGNALS_MAX_VERTICES)
        CityBlocks.buildSignals(later, 2, -1, 37f + CityGrid.CYCLE_SECONDS, seed, 180f, -90f)
        assertTrue(w.floats.contentEquals(later.floats))
        CityBlocks.buildSignals(later, 2, -1, 37f + CityGrid.CYCLE_SECONDS / 2f, seed, 180f, -90f)
        assertTrue(!w.floats.contentEquals(later.floats))
    }
}
