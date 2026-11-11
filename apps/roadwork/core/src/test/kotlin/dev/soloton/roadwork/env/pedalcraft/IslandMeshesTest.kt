package dev.soloton.roadwork.env.pedalcraft

import dev.soloton.roadwork.Environments
import dev.soloton.roadwork.MeshWriter
import dev.soloton.roadwork.Rng
import dev.soloton.roadwork.RoadworkGame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IslandMeshesTest {
    private val seed = RoadworkGame.DEFAULT_SEED
    private val terrain = IslandTerrain(seed)
    private val heights = IslandMeshes.heights(terrain)

    private fun x(w: MeshWriter, v: Int) = w.floats[v * MeshWriter.STRIDE]
    private fun y(w: MeshWriter, v: Int) = w.floats[v * MeshWriter.STRIDE + 1]
    private fun z(w: MeshWriter, v: Int) = w.floats[v * MeshWriter.STRIDE + 2]
    private fun normalY(w: MeshWriter, v: Int) = w.floats[v * MeshWriter.STRIDE + 4]

    @Test
    fun theEnvironmentIsRegisteredWithItsMusicFolderAndItsOwnView() {
        val e = Environments.find("pedalcraft-island")
        assertEquals(Pedalcraft.environment, e)
        assertEquals("Pedalcraft Island", e?.title)
        assertEquals("Pilotwings", e?.musicFolder)
        val scene = Pedalcraft.environment.createScene(seed)
        assertFalse(scene.riderView, "the view follows the craft; no handlebars")
        assertEquals(listOf("overview", "craft-side"), scene.inspectionViews)
    }

    @Test
    fun theGridCoversTheIslandAndItsShallows() {
        // Everything shallower than the shallows' depth lies inside the grid, with room to spare.
        for (i in 0 until 360) {
            val a = (i * PI / 180).toFloat()
            val r = terrain.coastRadius(a) * (1f + IslandMeshes.SHALLOWS_DEPTH / IslandTerrain.SEABED_FALL)
            assertTrue(r < IslandMeshes.HALF - IslandMeshes.CELL, "shallows reach $r m at $i degrees; the grid ends at ${IslandMeshes.HALF}")
        }
        for (spot in terrain.spots) {
            assertTrue(abs(spot.x) + spot.radius < IslandMeshes.HALF && abs(spot.z) + spot.radius < IslandMeshes.HALF, spot.name)
        }
        val n = IslandMeshes.CELLS + 1
        assertEquals(n * n, heights.size)
        for (edge in 0 until n) {
            assertTrue(heights[edge] < -IslandMeshes.SHALLOWS_DEPTH && heights[(n - 1) * n + edge] < -IslandMeshes.SHALLOWS_DEPTH, "deep water along the grid's edge")
        }
    }

    @Test
    fun landTilesStayInBudgetFaceUpAndNeverDipBelowTheSea() {
        val w = MeshWriter(IslandMeshes.TILE_MAX_VERTICES)
        var triangles = 0
        var empty = 0
        for (tz in 0 until IslandMeshes.TILES) for (tx in 0 until IslandMeshes.TILES) {
            IslandMeshes.buildTile(w, terrain, heights, tx, tz, seed) // throws if it overflows the writer
            triangles += w.triangleCount
            if (w.vertexCount == 0) empty++
            for (v in 0 until w.vertexCount) {
                assertTrue(y(w, v) >= -0.51f, "tile $tx,$tz: a vertex at y ${y(w, v)}")
                assertTrue(normalY(w, v) > -1e-3f, "tile $tx,$tz: a face points down")
                assertTrue(abs(x(w, v)) <= IslandMeshes.HALF + 6f && abs(z(w, v)) <= IslandMeshes.HALF + 6f)
            }
        }
        assertTrue(empty >= 4, "the corner tiles are open sea: $empty empty")
        // The whole island is drawn once from afar and the nearby tiles again: well inside 60k a frame.
        assertTrue(triangles in 8_000..26_000, "$triangles triangles of land and trees")
    }

    @Test
    fun aTileIsTheSameEveryTime() {
        val a = MeshWriter(IslandMeshes.TILE_MAX_VERTICES)
        val b = MeshWriter(IslandMeshes.TILE_MAX_VERTICES)
        IslandMeshes.buildTile(a, terrain, heights, 3, 4, seed)
        IslandMeshes.buildTile(b, IslandTerrain(seed), IslandMeshes.heights(IslandTerrain(seed)), 3, 4, seed)
        assertEquals(a.vertexCount, b.vertexCount)
        assertTrue(a.vertexCount > 0)
        assertTrue(a.floats.copyOf(a.vertexCount * MeshWriter.STRIDE).contentEquals(b.floats.copyOf(b.vertexCount * MeshWriter.STRIDE)))
    }

    @Test
    fun theShallowsAndTheSeaAreFlatAndFaceUp() {
        val w = MeshWriter(IslandMeshes.SHALLOWS_MAX_VERTICES)
        IslandMeshes.buildShallows(w, heights)
        assertTrue(w.vertexCount in 1_000..IslandMeshes.SHALLOWS_MAX_VERTICES, "${w.vertexCount} vertices of shallows")
        for (v in 0 until w.vertexCount) {
            assertEquals(0f, y(w, v))
            assertEquals(1f, normalY(w, v), 1e-4f)
            assertTrue(terrain.height(x(w, v), z(w, v)) <= 0.01f, "shallows drawn over land")
        }

        val sea = MeshWriter(IslandMeshes.SEA_MAX_VERTICES)
        IslandMeshes.buildSea(sea)
        var farthest = 0f
        for (v in 0 until sea.vertexCount) {
            assertEquals(1f, normalY(sea, v), 1e-4f)
            assertTrue(y(sea, v) < 0f, "the sea sits just under the shallows")
            farthest = maxOf(farthest, sqrt(x(sea, v) * x(sea, v) + z(sea, v) * z(sea, v)))
        }
        assertTrue(farthest > 30_000f && farthest < PedalcraftScene.FAR_FAR, "the sea reaches $farthest m")
    }

    @Test
    fun cloudsMarkingsAndBuildingsFitAndSitWhereTheyShould() {
        val w = MeshWriter(IslandMeshes.CLOUDS_MAX_VERTICES)
        IslandMeshes.buildClouds(w, seed, Rng(0))
        assertEquals(IslandMeshes.CLOUDS_MAX_VERTICES, w.vertexCount)
        // Above the highest any craft can fly.
        val ceiling = FlightMotion(terrain).legs.maxOf { leg ->
            var top = 0f
            var s = 0f
            while (s < leg.route.length) { top = maxOf(top, leg.route.heightAt(s, leg.craft.liftRange, leg.craft.seat)); s += 50f }
            top
        }
        val cloudBase = (0 until w.vertexCount).minOf { y(w, it) }
        assertTrue(cloudBase > ceiling, "clouds from $cloudBase m; craft fly up to $ceiling m")

        val m = MeshWriter(IslandMeshes.MARKINGS_MAX_VERTICES)
        IslandMeshes.buildMarkings(m, terrain)
        assertTrue(m.vertexCount > 100)
        for (v in 0 until m.vertexCount) {
            val above = y(m, v) - terrain.height(x(m, v), z(m, v))
            assertTrue(above in 0.1f..0.6f, "a marking ${above} m off the ground at ${x(m, v)}, ${z(m, v)}")
            assertEquals(1f, normalY(m, v), 1e-4f)
        }

        val b = MeshWriter(IslandMeshes.BUILDINGS_MAX_VERTICES)
        IslandMeshes.buildBuildings(b, terrain, seed)
        assertTrue(b.vertexCount > 800, "${b.vertexCount} vertices of buildings")
        // Nothing built stands on a runway or strip.
        for (spot in terrain.spots) {
            for (v in 0 until b.vertexCount) {
                val dx = x(b, v) - spot.x
                val dz = z(b, v) - spot.z
                val along = dx * spot.dirX + dz * spot.dirZ
                val across = -dx * spot.dirZ + dz * spot.dirX
                assertFalse(abs(along) < spot.run && abs(across) < 12f, "something is built on the ${spot.name}")
            }
        }
    }

    @Test
    fun everyCraftFitsItsBufferAndSitsOnItsWheels() {
        val w = MeshWriter(CraftModel.MAX_VERTICES)
        for (craft in Craft.entries) {
            var most = 0
            for (step in 0 until 12) {
                CraftModel.build(w, craft, 10f, 50f, -20f, 0.6f, 0.8f, 20f, -8f, step * 0.5f) // throws if it overflows
                assertEquals(0, w.vertexCount % 3)
                most = maxOf(most, w.vertexCount)
            }
            assertTrue(most > 300, "$craft: $most vertices")

            // Level on the ground with the seat [Craft.seat] up, the lowest point is at the ground.
            CraftModel.build(w, craft, 0f, craft.seat, 0f, 0f, -1f, 0f, 0f, 0f)
            val lowest = (0 until w.vertexCount).minOf { y(w, it) }
            assertEquals(0f, lowest, 0.08f, "$craft: wheels at $lowest m with the seat ${craft.seat} m up")
            // It faces the way it is going: the propeller end and the tail are where they belong.
            val span = (0 until w.vertexCount).maxOf { x(w, it) } - (0 until w.vertexCount).minOf { x(w, it) }
            assertTrue(span in 8f..13f, "$craft spans $span m")
        }
        val s = MeshWriter(CraftModel.SHADOW_VERTICES)
        CraftModel.buildShadow(s, Craft.PLANE, 5f, 12f, 7f, 0f)
        assertEquals(CraftModel.SHADOW_VERTICES, s.vertexCount)
        for (v in 0 until s.vertexCount) {
            assertEquals(12f, y(s, v))
            assertEquals(1f, normalY(s, v), 1e-4f)
        }
    }
}
