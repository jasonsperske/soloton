package dev.soloton.roadwork.env.riverfront

import dev.soloton.roadwork.MeshWriter
import dev.soloton.roadwork.Rng
import dev.soloton.roadwork.BikeOverlay
import dev.soloton.roadwork.RoadworkGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeometryTest {
    private fun normal(w: MeshWriter, v: Int) = FloatArray(3) { w.floats[v * MeshWriter.STRIDE + 3 + it] }
    private fun pos(w: MeshWriter, v: Int) = FloatArray(3) { w.floats[v * MeshWriter.STRIDE + it] }

    /** Every face of a closed solid centred at c has a normal pointing away from c. */
    private fun assertOutward(w: MeshWriter, cx: Float, cy: Float, cz: Float) {
        for (t in 0 until w.triangleCount) {
            val n = normal(w, t * 3)
            var mx = 0f; var my = 0f; var mz = 0f
            for (k in 0 until 3) { val p = pos(w, t * 3 + k); mx += p[0] / 3; my += p[1] / 3; mz += p[2] / 3 }
            val dot = n[0] * (mx - cx) + n[1] * (my - cy) + n[2] * (mz - cz)
            assertTrue(dot > 0f, "triangle $t faces inward")
        }
    }

    @Test
    fun primitivesWindOutward() {
        val w = MeshWriter(1000)
        w.icosa(1f, 2f, 3f, 1f, 0.7f, 1.2f, 0f)
        assertEquals(20, w.triangleCount)
        assertOutward(w, 1f, 2f, 3f)

        w.reset()
        w.box(0f, 0f, 0f, 2f, 1f, 3f, 0f, bottom = true)
        assertEquals(12, w.triangleCount)
        assertOutward(w, 1f, 0.5f, 1.5f)

        w.reset()
        w.beam(0.2f, 0.1f, -0.3f, 1.1f, 0.9f, 0.4f, 0.2f, 0.1f, 0f)
        assertEquals(12, w.triangleCount)
        assertOutward(w, 0.65f, 0.5f, 0.05f)

        w.reset()
        w.beam(0f, 0f, 0f, 0f, 2f, 0f, 0.2f, 0.2f, 0f) // vertical
        assertOutward(w, 0f, 1f, 0f)

        w.reset()
        w.prism(0f, 0f, 0f, 1f, 1f, 2f, 7, 0f)
        for (t in 0 until w.triangleCount) assertTrue(normal(w, t * 3)[1] >= -1e-4f, "prism faces never point down")
    }

    @Test
    fun floorFacesUpAndTransformApplies() {
        val w = MeshWriter(10)
        w.transform.translate(5f, 0f, 0f)
        w.floor(0f, 1f, 0f, -1f, 0f, 0f)
        assertEquals(1f, normal(w, 0)[1], 1e-6f)
        assertEquals(5f, pos(w, 0)[0], 1e-6f)
        assertFailsWith<IllegalStateException> { w.floor(0f, 1f, 0f, -1f, 0f, 0f) }
    }

    @Test
    fun farLayersFitTheirBuffersAndTheBudget() {
        val w = MeshWriter(Skyline.MAX_VERTICES)
        val rng = Rng()
        Scenery.buildFarPlanes(w)
        val planes = w.vertexCount
        var detailed = 0
        var simple = 0
        var maxSection = 0
        for (s in 0 until Skyline.SECTIONS) {
            Skyline.buildDetailed(w, RoadworkGame.DEFAULT_SEED, rng, s)
            detailed += w.vertexCount
            maxSection = maxOf(maxSection, w.vertexCount)
            Skyline.buildSimple(w, RoadworkGame.DEFAULT_SEED, rng, s)
            simple += w.vertexCount
        }
        Skyline.buildDetailed(w, RoadworkGame.DEFAULT_SEED, rng)
        assertEquals(detailed, w.vertexCount, "the sections add up to the whole period")
        Scenery.buildNearWater(w, RoadworkGame.DEFAULT_SEED)
        val water = w.vertexCount
        var chunk = 0
        for (i in 0L..300L) {
            Scenery.buildChunk(w, i, RoadworkGame.DEFAULT_SEED, rng)
            chunk = maxOf(chunk, w.vertexCount)
        }
        println("far planes $planes, skyline detailed $detailed (max section $maxSection), plain $simple, water $water, chunk max $chunk vertices")
        assertTrue(planes in 100 until Skyline.MAX_VERTICES)
        assertTrue(simple < detailed / 2, "plain skyline $simple vertices")
        assertTrue(water in 1000 until Skyline.MAX_VERTICES)
        // Worst case on screen: detailed sections over the detail distance plus an overhang, plain
        // sections out to the far plane, every chunk, water, jogger and handlebars: under ~60k triangles.
        val detailSections = (RiverfrontScene.SKYLINE_DETAIL_DISTANCE / Skyline.SECTION_LENGTH).toInt() + 2
        val plainSections = (RiverfrontScene.SKYLINE_FAR / Skyline.SECTION_LENGTH).toInt() + 1
        val worst = (planes + detailSections * maxSection + plainSections * simple / Skyline.SECTIONS + water +
            RiverfrontScene.CHUNK_SLOTS * chunk + JoggerModel.MAX_VERTICES + BikeOverlay.MAX_VERTICES) / 3
        println("worst case on screen: $worst triangles")
        assertTrue(worst < 60_000, "worst case $worst triangles")
    }

    @Test
    fun skylineIsDeterministicAndStaysBeyondTheSkylineNearPlane() {
        val a = MeshWriter(Skyline.MAX_VERTICES)
        val b = MeshWriter(Skyline.MAX_VERTICES)
        Skyline.buildDetailed(a, 42L, Rng())
        Skyline.buildDetailed(b, 42L, Rng(9))
        assertEquals(a.vertexCount, b.vertexCount)
        assertTrue(a.floats.copyOf(a.vertexCount * MeshWriter.STRIDE).contentEquals(b.floats.copyOf(b.vertexCount * MeshWriter.STRIDE)))
        var tallest = 0f
        for (v in 0 until a.vertexCount) {
            val p = pos(a, v)
            // Everything sits in one period, so copies tile without overlapping.
            assertTrue(p[2] <= 0.01f && p[2] >= -Skyline.PERIOD - 0.01f, "z ${p[2]} outside the period")
            // Never closer to the path than the skyline camera's near plane.
            assertTrue(kotlin.math.abs(p[0]) >= RiverfrontScene.SKYLINE_NEAR, "x ${p[0]} too close")
            if (p[0] > 0f) tallest = maxOf(tallest, p[1])
        }
        assertTrue(tallest > 200f, "a dominant skyline needs some tall towers ($tallest m)")
    }

    @Test
    fun skyDriftsGentlyAndStaysDaylight() {
        val sky = DaySky()
        sky.update(0.0)
        val start = sky.sunDirection.cpy()
        val startZenith = sky.zenith.cpy()
        for (s in 0..3600 step 60) {
            sky.update(s.toDouble())
            assertEquals(1f, sky.sunDirection.len(), 1e-4f)
            assertTrue(sky.sunDirection.y > 0.75f, "sun high at $s s")
            // Behind and to the left, so the jogger's back and the far bank are sunlit.
            assertTrue(sky.sunDirection.x < 0f && sky.sunDirection.z > 0f)
            assertTrue(sky.zenith.b > 0.8f && sky.zenith.b > sky.zenith.r + 0.5f, "clear blue sky")
        }
        sky.update(1200.0)
        val end = sky.sunDirection.cpy()
        assertTrue(start.dot(end) < 0.9999f, "the sun moves over 20 minutes")
        assertTrue(start.dot(end) > 0.95f, "but only a little")
        assertTrue(kotlin.math.abs(sky.zenith.b - startZenith.b) < 0.05f)
        sky.update(5000.0)
        assertEquals(end, sky.sunDirection)
    }
}
