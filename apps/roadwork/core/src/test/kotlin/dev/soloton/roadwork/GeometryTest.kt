package dev.soloton.roadwork

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
    fun farLayerAndWaterFitTheirBuffers() {
        val w = MeshWriter(RoadworkGame.FAR_MAX_VERTICES)
        Scenery.buildFar(w, RoadworkGame.DEFAULT_SEED, Rng())
        val far = w.vertexCount
        Scenery.buildNearWater(w, RoadworkGame.DEFAULT_SEED)
        val water = w.vertexCount
        assertTrue(far in 1000 until RoadworkGame.FAR_MAX_VERTICES)
        assertTrue(water in 1000 until RoadworkGame.FAR_MAX_VERTICES)
        // Whole static scene on screen stays well under the ~60k triangle budget.
        assertTrue((far + water) / 3 + 13 * Scenery.CHUNK_MAX_VERTICES / 3 < 60_000)
    }

    @Test
    fun skyBrightensThenHolds() {
        val sky = DawnSky()
        var last = -1f
        for (s in 0..1200 step 60) {
            sky.update(s.toDouble())
            val lum = sky.zenith.r + sky.zenith.g + sky.zenith.b
            assertTrue(lum >= last, "zenith brightens at $s s")
            last = lum
            assertEquals(1f, sky.sunDirection.len(), 1e-4f)
        }
        sky.update(1200.0)
        val atEnd = sky.zenith.cpy()
        assertEquals(0f, sky.lampGlow)
        sky.update(5000.0)
        assertEquals(atEnd, sky.zenith)
        sky.update(0.0)
        assertEquals(1f, sky.lampGlow)
        assertTrue(sky.sunDirection.y > 0f && sky.sunDirection.z < 0f, "sun just above the horizon, ahead")
    }
}
