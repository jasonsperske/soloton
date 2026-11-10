package dev.soloton.roadwork

import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkTrackTest {
    private fun assertContiguous(track: ChunkTrack, distance: Double) {
        val sorted = track.indices.sorted()
        val first = floor((distance - track.length) / track.length).toLong()
        assertEquals(first, sorted.first(), "first index at d=$distance")
        for (i in 1 until sorted.size) assertEquals(sorted[i - 1] + 1, sorted[i], "gap in $sorted")
        // Slots cover from behind the rider to at least reachAhead in front.
        val coveredEnd = (sorted.last() + 1) * track.length
        assertTrue(coveredEnd >= distance + track.reachAhead - track.length, "covers ahead at d=$distance")
    }

    @Test
    fun startsCoveringTheFirstStretch() {
        val t = ChunkTrack(20f, 13)
        assertEquals((-1L..11L).toList(), t.indices.sorted())
        assertEquals(0, t.update(0.0) { _, _ -> })
        assertEquals(240f, t.reachAhead)
    }

    @Test
    fun recyclesWithoutGrowingAfterTravellingFar() {
        val t = ChunkTrack(20f, 13)
        var recycled = 0
        var d = 0.0
        // 100 km in 1/60 s steps at 4.5 m/s would be slow to test; use 7.5 cm steps.
        while (d < 100_000.0) {
            d += 0.075
            recycled += t.update(d) { slot, index ->
                assertTrue(slot in 0 until 13)
                assertTrue(index * 20.0 >= d - 20.0 - 20.0, "new chunk $index is ahead")
            }
        }
        assertEquals(13, t.indices.size)
        assertContiguous(t, d)
        // One recycle per 20 m travelled.
        assertEquals(5000, recycled)
        assertEquals(floor((d - 20.0) / 20.0).toLong(), t.firstIndex)
    }

    @Test
    fun handlesALongJump() {
        val t = ChunkTrack(20f, 13)
        t.update(50.0) { _, _ -> }
        assertContiguous(t, 50.0)
        val n = t.update(10_000.0) { _, _ -> }
        assertEquals(13, n)
        assertContiguous(t, 10_000.0)
        // A jump smaller than the whole window keeps the chunks still in range.
        val m = t.update(10_100.0) { _, _ -> }
        assertEquals(5, m)
        assertContiguous(t, 10_100.0)
    }

    @Test
    fun chunkGeometryFitsBudgetAndIsDeterministic() {
        val w = MeshWriter(Scenery.CHUNK_MAX_VERTICES)
        val rng = Rng()
        var maxVerts = 0
        for (i in -1L..400L) {
            Scenery.buildChunk(w, i, RoadworkGame.DEFAULT_SEED, rng)
            maxVerts = maxOf(maxVerts, w.vertexCount)
        }
        // Keeps every chunk slot together well under the device triangle budget.
        assertTrue(maxVerts / 3 * RoadworkGame.CHUNK_SLOTS < 30_000, "max ${maxVerts / 3} triangles per chunk")

        Scenery.buildChunk(w, 1234L, 99L, rng)
        val a = w.floats.copyOf(w.vertexCount * MeshWriter.STRIDE)
        Scenery.buildChunk(w, 7L, 99L, rng)
        Scenery.buildChunk(w, 1234L, 99L, rng)
        val b = w.floats.copyOf(w.vertexCount * MeshWriter.STRIDE)
        assertTrue(a.contentEquals(b), "same index and seed give the same chunk")
        Scenery.buildChunk(w, 1235L, 99L, rng)
        val c = w.floats.copyOf(w.vertexCount * MeshWriter.STRIDE)
        assertTrue(!a.contentEquals(c), "neighbouring chunks differ")
    }

    @Test
    fun gameTrackStaysBoundedOverALongRide() {
        val t = ChunkTrack(Scenery.CHUNK_LENGTH, RoadworkGame.CHUNK_SLOTS)
        val ride = RideState(6f)
        val ts = FixedTimestep()
        var recycled = 0
        ts.advanceTo(3600.0) {
            ride.step(it, 6f)
            recycled += t.update(ride.distanceMeters) { slot, _ -> assertTrue(slot in 0 until RoadworkGame.CHUNK_SLOTS) }
        }
        assertEquals(RoadworkGame.CHUNK_SLOTS, t.indices.size)
        assertContiguous(t, ride.distanceMeters)
        assertEquals(floor((ride.distanceMeters - t.length) / t.length).toLong() + 1, recycled.toLong())
        // The slots reach past the near far plane's fade-out of objects.
        assertTrue(t.reachAhead >= RoadworkGame.NEAR_FAR - Scenery.CHUNK_LENGTH)
    }
}
