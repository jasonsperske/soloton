package dev.soloton.roadwork.env.skylift

import dev.soloton.roadwork.Environments
import dev.soloton.roadwork.MeshWriter
import dev.soloton.roadwork.Rng
import dev.soloton.roadwork.RoadworkGame
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MesaSceneryTest {
    private val seed = RoadworkGame.DEFAULT_SEED

    private fun x(w: MeshWriter, v: Int) = w.floats[v * MeshWriter.STRIDE]
    private fun y(w: MeshWriter, v: Int) = w.floats[v * MeshWriter.STRIDE + 1]
    private fun z(w: MeshWriter, v: Int) = w.floats[v * MeshWriter.STRIDE + 2]

    @Test
    fun theEnvironmentIsRegisteredWithItsMusicFolder() {
        val e = Environments.find("skylift-mesa")
        assertEquals(Skylift.environment, e)
        assertEquals("Skylift Mesa", e?.title)
        assertEquals("Space Harrier II", e?.musicFolder)
    }

    @Test
    fun chunksStayInBudgetAndInsideTheirStretchOfTrail() {
        val w = MeshWriter(MesaScenery.CHUNK_MAX_VERTICES)
        val rng = Rng(0)
        var most = 0
        for (index in -1L..400L) {
            MesaScenery.buildChunk(w, index, seed, rng) // throws if it overflows the writer
            most = maxOf(most, w.vertexCount)
            for (v in 0 until w.vertexCount) {
                // A spire or rock near a chunk's end may overhang it by its own radius.
                assertTrue(z(w, v) <= 8f && z(w, v) >= -MesaScenery.CHUNK_LENGTH - 8f, "chunk $index: z ${z(w, v)}")
                assertTrue(abs(x(w, v)) <= MesaScenery.HALF_WIDTH + 0.01f, "chunk $index: x ${x(w, v)}")
                assertTrue(y(w, v) >= -0.21f && y(w, v) < 120f, "chunk $index: y ${y(w, v)}")
            }
        }
        assertTrue(most > 600, "chunks have scenery: $most vertices at most")
        // 14 chunks a frame plus the traffic and the far scenery stay far below the 60k triangle budget.
        val triangles = SkyliftScene.CHUNK_SLOTS * MesaScenery.CHUNK_MAX_VERTICES / 3 +
            MesaScenery.TRAFFIC_MAX_VERTICES / 3 + MesaScenery.FAR_MAX_VERTICES / 3
        assertTrue(triangles < 20_000, "$triangles triangles a frame at the very most")
    }

    @Test
    fun theWayAlongTheTrailIsClearAtEveryHeight() {
        val w = MeshWriter(MesaScenery.CHUNK_MAX_VERTICES)
        val rng = Rng(0)
        for (index in 0L..400L) {
            MesaScenery.buildChunk(w, index, seed, rng)
            for (v in 0 until w.vertexCount) {
                if (abs(x(w, v)) >= MesaScenery.CLEAR_HALF) continue
                // Only the ground and the low markers at the trail's edge are this close to the middle.
                assertTrue(y(w, v) <= 0.71f, "chunk $index: something at x ${x(w, v)}, y ${y(w, v)}")
                if (y(w, v) > 0.01f) assertTrue(abs(x(w, v)) > MesaScenery.TRAIL_HALF, "a marker on the trail in chunk $index")
            }
        }
        assertTrue(MesaScenery.CLEAR_HALF > SkyliftMotion.MAX_DODGE + 1f, "a swerving rider stays inside the clear way")
        assertTrue(MesaScenery.TRAIL_HALF > SkyliftMotion.MAX_DODGE, "a swerving rider stays on the trail")
    }

    @Test
    fun aChunkIsTheSameEveryTimeAndDiffersFromItsNeighbour() {
        val a = MeshWriter(MesaScenery.CHUNK_MAX_VERTICES)
        val b = MeshWriter(MesaScenery.CHUNK_MAX_VERTICES)
        MesaScenery.buildChunk(a, 17, seed, Rng(1))
        MesaScenery.buildChunk(b, 17, seed, Rng(99))
        assertEquals(a.vertexCount, b.vertexCount)
        assertContentEquals(a.floats.copyOf(a.vertexCount * MeshWriter.STRIDE), b.floats.copyOf(b.vertexCount * MeshWriter.STRIDE))
        MesaScenery.buildChunk(b, 18, seed, Rng(1))
        assertTrue(!a.floats.copyOf(2000).contentEquals(b.floats.copyOf(2000)))
    }

    @Test
    fun theFarSceneryFitsAndLeavesTheWayAheadOpen() {
        val w = MeshWriter(MesaScenery.FAR_MAX_VERTICES)
        MesaScenery.buildFar(w, seed, Rng(0))
        assertTrue(w.vertexCount > 12 + MesaScenery.MESAS * 30, "${w.vertexCount} vertices")
        // After the two ground quads come the mesas: none stands across the line of the trail.
        for (v in 12 until w.vertexCount) {
            assertTrue(abs(x(w, v)) > 300f, "a mesa reaches to x ${x(w, v)}")
            assertTrue(z(w, v) < -1500f, "a mesa as near as z ${z(w, v)}")
        }
    }

    @Test
    fun trafficGeometryFitsItsBufferWhenThePoolIsFull() {
        val m = SkyliftMotion(9L)
        m.start(1f)
        var most = 0
        val w = MeshWriter(MesaScenery.TRAFFIC_MAX_VERTICES)
        repeat(60 * 240) {
            m.step(1f / 60f, 6f, 1f)
            if (it % 30 == 0) {
                MesaScenery.buildTraffic(w, m)
                most = maxOf(most, w.vertexCount)
                assertEquals(0, w.vertexCount % 3)
            }
        }
        assertTrue(most > 0 && most <= MesaScenery.TRAFFIC_MAX_VERTICES, "$most vertices")

        // On the ground there are rollers too, and they sit on the ground, not in it.
        val ground = SkyliftMotion(9L)
        repeat(60 * 240) { ground.step(1f / 60f, 4f, 0f) }
        MesaScenery.buildTraffic(w, ground)
        for (v in 0 until w.vertexCount) assertTrue(y(w, v) >= -0.01f, "traffic below ground at y ${y(w, v)}")
    }
}
