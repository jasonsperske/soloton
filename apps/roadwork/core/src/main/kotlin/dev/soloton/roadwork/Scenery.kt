package dev.soloton.roadwork

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Procedural geometry for the riverfront promenade. Coordinates: x to the right, y up, the path
 * runs toward -z. The river is on the right.
 *
 * Layout across the path (x, metres):
 * ```
 *  -40 ..... -2.15 | -1.9 ...... 1.7 | 1.85 ........ 10.25 | 10.45 ...... 13.7 | 14.1 .....
 *  park, trees     | kerb  ride lane | edge  tiled promenade | edging  grass strip | coping  river
 *                                                             posts, hedge beds, rope swags
 * ```
 */
object Scenery {
    const val CHUNK_LENGTH = 24f

    const val KERB_LEFT = -2.15f
    const val LANE_LEFT = -1.9f
    const val LANE_RIGHT = 1.7f
    const val PROM_LEFT = 1.85f
    const val TILE = 1.2f
    const val TILE_COLUMNS = 7
    const val PROM_RIGHT = PROM_LEFT + TILE * TILE_COLUMNS
    const val GROUT = 0.05f
    const val GRASS_LEFT = PROM_RIGHT + 0.2f
    const val BANK = 13.7f
    const val COPING = 14.1f
    const val POST_X = 13.15f
    const val POST_SPACING = 3f
    const val PARK_END = -40f
    const val WATER_Y = -1.0f
    const val PARK_Y = -0.04f

    /** Near-water grid cell and the repeat length of its ripple pattern (water scrolls by distance mod this). */
    const val WATER_CELL = 4f
    const val WATER_PERIOD_ROWS = 8
    const val WATER_PERIOD = WATER_CELL * WATER_PERIOD_ROWS
    const val WATER_NEAR_REACH = 160f

    /** Vertex budget per chunk (checked by tests). */
    const val CHUNK_MAX_VERTICES = 7500

    private val PARK_X = floatArrayOf(KERB_LEFT, -3.6f, -6f, -10f, -16f, -26f, PARK_END)

    // Palette: base colours before lighting (the midday sun brightens up-facing surfaces ~1.4x).
    private val LANE = floatArrayOf(0.47f, 0.25f, 0.18f)
    val TILE_BASE = floatArrayOf(0.62f, 0.60f, 0.56f)
    private val GROUT_COLOR = MeshWriter.pack(0.36f, 0.35f, 0.34f, MeshWriter.GROUT)
    private val EDGE = MeshWriter.pack(0.70f, 0.69f, 0.66f)
    private val KERB = MeshWriter.pack(0.62f, 0.62f, 0.60f)
    private val EDGING = MeshWriter.pack(0.46f, 0.46f, 0.46f)
    private val COPING_STONE = MeshWriter.pack(0.66f, 0.64f, 0.60f)
    private val GRASS = floatArrayOf(0.30f, 0.50f, 0.17f)
    private val SOIL = MeshWriter.pack(0.30f, 0.21f, 0.14f, MeshWriter.OBJECT)
    private val WOOD = MeshWriter.pack(0.46f, 0.31f, 0.18f, MeshWriter.OBJECT)
    private val WOOD_TOP = MeshWriter.pack(0.38f, 0.25f, 0.15f, MeshWriter.OBJECT)
    private val ROPE = MeshWriter.pack(0.78f, 0.70f, 0.52f, MeshWriter.OBJECT)
    private val POLE = MeshWriter.pack(0.20f, 0.24f, 0.24f, MeshWriter.OBJECT)
    private val LAMP_HEAD = MeshWriter.pack(0.85f, 0.85f, 0.80f, MeshWriter.OBJECT)
    private val BENCH_WOOD = MeshWriter.pack(0.55f, 0.38f, 0.22f, MeshWriter.OBJECT)
    private val IRON = MeshWriter.pack(0.18f, 0.19f, 0.21f, MeshWriter.OBJECT)
    private val TRUNK = MeshWriter.pack(0.36f, 0.27f, 0.19f, MeshWriter.OBJECT)
    private val BOX_HEDGE = floatArrayOf(0.16f, 0.36f, 0.14f)
    private val CROWNS = arrayOf(
        floatArrayOf(0.22f, 0.45f, 0.16f), floatArrayOf(0.30f, 0.52f, 0.17f),
        floatArrayOf(0.18f, 0.40f, 0.18f), floatArrayOf(0.36f, 0.55f, 0.20f),
    )

    /** Builds chunk [index] into [w] (cleared first), in chunk-local space: z from 0 (near end) to -[CHUNK_LENGTH]. */
    fun buildChunk(w: MeshWriter, index: Long, seed: Long, rng: Rng) {
        w.reset()
        rng.reset(Rng.mix(seed xor (index * 0x5DEECE66DL)))
        val zEnd = -CHUNK_LENGTH

        // Ride lane in 3 m slabs, a flush light edge strip, the left kerb and the promenade.
        val slabs = (CHUNK_LENGTH / 3f).toInt()
        for (r in 0 until slabs) {
            val z0 = -r * 3f
            val z1 = z0 - 3f
            val gz = index * slabs + r
            val j = (Rng.hash01(gz, 1, seed) - 0.5f) * 0.035f
            w.floor(LANE_LEFT, LANE_RIGHT, z0, z1, 0f, MeshWriter.pack(LANE[0] + j, LANE[1] + j * 0.6f, LANE[2] + j * 0.5f))
            w.floor(LANE_RIGHT, PROM_LEFT, z0, z1, 0f, EDGE)
            w.floor(PROM_RIGHT, GRASS_LEFT, z0, z1, 0f, EDGING)
            val g = (Rng.hash01(gz, 2, seed) - 0.5f) * 0.05f
            w.floor(GRASS_LEFT, BANK, z0, z1, 0f, MeshWriter.pack(GRASS[0] + g, GRASS[1] + g, GRASS[2] + g * 0.5f))
        }
        w.box(KERB_LEFT, -0.1f, zEnd, LANE_LEFT, 0.08f, 0f, KERB)
        w.box(BANK, -0.1f, zEnd, COPING, 0.1f, 0f, COPING_STONE)
        promenade(w, index, seed)

        // Bank-side posts every 3 m with rope swags, and hedge beds between them.
        val posts = (CHUNK_LENGTH / POST_SPACING).toInt()
        for (p in 0 until posts) {
            val z = -p * POST_SPACING - POST_SPACING * 0.5f
            post(w, z)
            rope(w, z, z - POST_SPACING)
            hedgeBed(w, z - POST_SPACING * 0.5f, index * posts + p, seed)
        }

        park(w, index, seed, slabs)
        lamp(w, -2.75f, -12f)
        boxHedges(w, rng)
        if (rng.chance(0.45f)) bench(w, PROM_RIGHT - 0.6f, -rng.range(4f, 20f))

        val trees = 2 + rng.nextInt(3)
        for (t in 0 until trees) {
            val z = -(t + rng.range(0.1f, 0.9f)) * CHUNK_LENGTH / trees
            val x = rng.range(-24f, -5.5f)
            tree(w, x, z, rng)
        }
    }

    /**
     * Light square paving tiles with darker grout. Each 1.2 m row is one strip of tiles and grout
     * gaps plus one grout band across, with no overlapping faces (so no z-fighting). The grout is
     * marked [MeshWriter.GROUT] and fades into the tile colour with distance, so it never shimmers.
     */
    private fun promenade(w: MeshWriter, index: Long, seed: Long) {
        val rows = (CHUNK_LENGTH / TILE).toInt()
        val half = GROUT * 0.5f
        for (r in 0 until rows) {
            val z0 = -r * TILE
            val zt = z0 - (TILE - GROUT)
            val z1 = z0 - TILE
            val gz = index * rows + r
            for (c in 0 until TILE_COLUMNS) {
                val xl = PROM_LEFT + c * TILE + (if (c == 0) 0f else half)
                val xr = PROM_LEFT + (c + 1) * TILE - (if (c == TILE_COLUMNS - 1) 0f else half)
                val checker = if (((gz + c) and 1L) == 0L) 0.022f else -0.022f
                val v = checker + (Rng.hash01(gz, c + 20L, seed) - 0.5f) * 0.03f
                w.floor(xl, xr, z0, zt, 0f, MeshWriter.pack(TILE_BASE[0] + v, TILE_BASE[1] + v, TILE_BASE[2] + v * 0.9f))
                if (c < TILE_COLUMNS - 1) w.floor(xr, xr + GROUT, z0, zt, 0f, GROUT_COLOR)
            }
            w.floor(PROM_LEFT, PROM_RIGHT, zt, z1, 0f, GROUT_COLOR)
        }
    }

    private fun post(w: MeshWriter, z: Float) {
        w.box(POST_X - 0.07f, -0.02f, z - 0.07f, POST_X + 0.07f, 0.78f, z + 0.07f, WOOD)
        w.prism(POST_X, 0.78f, z, 0.1f, 0f, 0.08f, 4, WOOD_TOP, (PI / 4).toFloat())
    }

    /** A rope hanging in a shallow curve between the tops of two posts. */
    private fun rope(w: MeshWriter, za: Float, zb: Float) {
        val n = 3
        val top = 0.70f
        val sag = 0.2f
        var py = top
        var pz = za - 0.07f
        for (i in 1..n) {
            val t = i / n.toFloat()
            val z = za - 0.07f + (zb - za + 0.14f) * t
            val y = top - sag * 4f * t * (1f - t)
            w.beam(POST_X - 0.075f, py, pz, POST_X - 0.075f, y, z, 0.035f, 0.035f, ROPE)
            py = y; pz = z
        }
    }

    /** A rectangular soil bed between two posts holding two round, clipped hedge clumps. */
    private fun hedgeBed(w: MeshWriter, z: Float, gid: Long, seed: Long) {
        val hl = 0.95f
        w.box(POST_X - 0.42f, -0.02f, z - hl, POST_X + 0.42f, 0.12f, z + hl, SOIL)
        for (k in 0..1) {
            val v = (Rng.hash01(gid, k + 40L, seed) - 0.5f) * 0.06f
            val r = 0.30f + Rng.hash01(gid, k + 50L, seed) * 0.07f
            val c = MeshWriter.pack(0.17f + v, 0.38f + v, 0.13f + v * 0.5f, MeshWriter.OBJECT)
            w.icosa(POST_X, 0.12f + r * 0.8f, z + (if (k == 0) 0.47f else -0.47f), r, r * 0.85f, r, c)
        }
    }

    /** Height of the park grid at column [ix] and global row [gz]: flat by the kerb, gently rolling beyond. */
    private fun parkHeight(ix: Int, gz: Long, seed: Long): Float {
        if (ix == 0) return PARK_Y
        if (ix == PARK_X.size - 1) return PARK_Y
        return PARK_Y + (Rng.hash01(ix.toLong(), gz, seed) - 0.3f) * 0.1f * ix
    }

    private fun park(w: MeshWriter, index: Long, seed: Long, rows: Int) {
        val len = CHUNK_LENGTH / rows
        for (r in 0 until rows) {
            val gz = index * rows + r
            val z0 = -r * len
            val z1 = z0 - len
            for (c in 0 until PARK_X.size - 1) {
                val xa = PARK_X[c] // inner (toward the path, larger x)
                val xb = PARK_X[c + 1]
                val ha0 = parkHeight(c, gz, seed); val ha1 = parkHeight(c, gz + 1, seed)
                val hb0 = parkHeight(c + 1, gz, seed); val hb1 = parkHeight(c + 1, gz + 1, seed)
                val v1 = (Rng.hash01(gz, c * 2L + 10, seed) - 0.5f) * 0.06f
                val v2 = (Rng.hash01(gz, c * 2L + 11, seed) - 0.5f) * 0.06f
                // Seen from above: (xb,z0) -> (xa,z0) -> (xa,z1) is CCW.
                w.tri(xb, hb0, z0, xa, ha0, z0, xa, ha1, z1, MeshWriter.pack(GRASS[0] + v1, GRASS[1] + v1, GRASS[2] + v1 * 0.5f))
                w.tri(xb, hb0, z0, xa, ha1, z1, xb, hb1, z1, MeshWriter.pack(GRASS[0] - 0.01f + v2, GRASS[1] - 0.01f + v2, GRASS[2] + v2 * 0.5f))
            }
        }
    }

    /** Low clipped box hedges along the park edge, with gaps for paths. */
    private fun boxHedges(w: MeshWriter, rng: Rng) {
        var z = -rng.range(0f, 3f)
        while (z > -CHUNK_LENGTH + 1.5f) {
            val len = rng.range(3f, 8f).coerceAtMost(z + CHUNK_LENGTH - 0.5f)
            if (len > 1f && rng.chance(0.75f)) {
                val g = rng.range(-0.03f, 0.03f)
                val c = MeshWriter.pack(BOX_HEDGE[0] + g, BOX_HEDGE[1] + g, BOX_HEDGE[2] + g, MeshWriter.OBJECT)
                w.box(-3.55f, -0.05f, z - len, -3.0f, 0.62f, z, c)
            }
            z -= len + rng.range(1.2f, 3f)
        }
    }

    private fun lamp(w: MeshWriter, x: Float, z: Float) {
        w.prism(x, -0.05f, z, 0.13f, 0.10f, 0.4f, 6, POLE)
        w.prism(x, 0.35f, z, 0.06f, 0.05f, 4.1f, 6, POLE)
        w.beam(x, 4.38f, z, x + 0.55f, 4.42f, z, 0.06f, 0.06f, POLE)
        w.boxC(x + 0.62f, 4.36f, z, 0.2f, 0.06f, 0.14f, LAMP_HEAD)
    }

    /** Bench on the promenade facing the river. */
    private fun bench(w: MeshWriter, x: Float, z: Float) {
        val half = 0.95f
        w.box(x - 0.25f, 0.42f, z - half, x + 0.25f, 0.48f, z + half, BENCH_WOOD)
        w.box(x - 0.31f, 0.55f, z - half, x - 0.25f, 0.92f, z + half, BENCH_WOOD)
        for (s in 0..1) {
            val zz = z + (if (s == 0) -half + 0.15f else half - 0.15f)
            w.box(x - 0.3f, 0f, zz - 0.04f, x + 0.22f, 0.42f, zz + 0.04f, IRON)
            w.box(x - 0.32f, 0.42f, zz - 0.035f, x - 0.26f, 0.9f, zz + 0.035f, IRON)
        }
    }

    private fun tree(w: MeshWriter, x: Float, z: Float, rng: Rng) {
        val scale = rng.range(0.85f, 1.35f)
        val h = rng.range(1.9f, 2.8f) * scale
        w.prism(x, -0.1f, z, 0.17f * scale, 0.12f * scale, h + 0.4f, 5, TRUNK, rng.nextFloat())
        val pal = CROWNS[rng.nextInt(CROWNS.size)]
        val g = rng.range(-0.04f, 0.04f)
        val c = MeshWriter.pack(pal[0] + g, pal[1] + g, pal[2] + g, MeshWriter.OBJECT)
        val r = rng.range(1.3f, 1.9f) * scale
        w.icosa(x, h + r * 0.8f, z, r, r * rng.range(0.85f, 1.1f), r, c)
        if (rng.chance(0.6f)) {
            val c2 = MeshWriter.pack(pal[0] + g + 0.04f, pal[1] + g + 0.04f, pal[2] + g, MeshWriter.OBJECT)
            w.icosa(x + rng.range(-0.7f, 0.7f), h + r * 1.5f, z + rng.range(-0.7f, 0.7f), r * 0.7f, r * 0.65f, r * 0.7f, c2)
        }
    }

    /**
     * Faceted water from the bank out to [WATER_NEAR_REACH], in render space (camera at z = 0).
     * Its ripple heights repeat every [WATER_PERIOD] metres along z, so it can be scrolled by
     * `distance mod WATER_PERIOD` and look continuous.
     */
    fun buildNearWater(w: MeshWriter, seed: Long) {
        w.reset()
        val cols = ((WATER_NEAR_REACH - COPING) / WATER_CELL).toInt() + 1
        val rowsAhead = ((WATER_NEAR_REACH + WATER_PERIOD) / WATER_CELL).toInt()
        val rowStart = -3 // a few rows behind the camera
        fun h(c: Int, r: Int): Float {
            if (c == 0) return WATER_Y
            val rr = Math.floorMod(r, WATER_PERIOD_ROWS)
            return WATER_Y + (Rng.hash01(c.toLong(), rr.toLong(), seed + 77) - 0.5f) * 0.2f
        }
        for (r in rowStart until rowsAhead) {
            val z0 = -r * WATER_CELL
            val z1 = z0 - WATER_CELL
            for (c in 0 until cols) {
                val x0 = COPING + c * WATER_CELL
                val x1 = x0 + WATER_CELL
                // Skip cells beyond the reach radius; the far water plane covers them.
                val mx = x0 + WATER_CELL * 0.5f
                val mz = z0 - WATER_CELL * 0.5f
                if (sqrt(mx * mx + mz * mz) > WATER_NEAR_REACH + WATER_PERIOD) continue
                val v = (Rng.hash01(c.toLong(), Math.floorMod(r, WATER_PERIOD_ROWS).toLong(), seed + 5) - 0.5f) * 0.03f
                w.tri(x0, h(c, r), z0, x1, h(c + 1, r), z0, x1, h(c + 1, r + 1), z1, MeshWriter.pack(WATER[0] + v, WATER[1] + v, WATER[2] + v))
                w.tri(x0, h(c, r), z0, x1, h(c + 1, r + 1), z1, x0, h(c, r + 1), z1, MeshWriter.pack(WATER[0] - 0.01f + v, WATER[1] - 0.01f + v, WATER[2] - 0.01f + v))
            }
        }
    }

    val WATER = floatArrayOf(0.13f, 0.33f, 0.46f)

    // Grid lines for the big far planes: dense near the camera so per-vertex fog interpolates well.
    private val FAR_STEPS = floatArrayOf(0f, 40f, 90f, 160f, 250f, 400f, 650f, 1000f, 1600f, 2500f, 4000f, 6500f)

    /**
     * The flat far layer, in render space and drawn without depth testing, so it is written back to
     * front: the river out to the far bank, the park, and the path, promenade and grass continued
     * in flat colours past the end of the near chunks. It moves with the camera.
     */
    fun buildFarPlanes(w: MeshWriter) {
        w.reset()
        val water = MeshWriter.pack(WATER[0], WATER[1], WATER[2])
        val park = MeshWriter.pack(GRASS[0], GRASS[1], GRASS[2])
        val zs = FAR_STEPS
        for (i in zs.size - 2 downTo 0) {
            val z0 = if (i == 0) 60f else -zs[i]
            val z1 = -zs[i + 1]
            // River: from the bank to the far bank.
            for (j in zs.size - 2 downTo 0) {
                val x0 = COPING + zs[j]
                val x1 = minOf(COPING + zs[j + 1], Skyline.FAR_BANK_X)
                if (x0 < x1) w.floor(x0, x1, z0, z1, WATER_Y, water)
            }
        }
        for (i in zs.size - 2 downTo 0) {
            val z0 = if (i == 0) 60f else -zs[i]
            val z1 = -zs[i + 1]
            for (j in zs.size - 2 downTo 0) {
                w.floor(KERB_LEFT - zs[j + 1], KERB_LEFT - zs[j], z0, z1, PARK_Y, park)
            }
            if (zs[i + 1] > 200f) {
                // Continuation of the riding surfaces beyond the near chunks.
                val zn = minOf(z0, -200f)
                w.floor(KERB_LEFT, LANE_LEFT, zn, z1, 0f, KERB)
                w.floor(LANE_LEFT, LANE_RIGHT, zn, z1, 0f, MeshWriter.pack(LANE[0], LANE[1], LANE[2]))
                w.floor(LANE_RIGHT, PROM_LEFT, zn, z1, 0f, EDGE)
                w.floor(PROM_LEFT, PROM_RIGHT, zn, z1, 0f, MeshWriter.pack(TILE_BASE[0], TILE_BASE[1], TILE_BASE[2]))
                w.floor(PROM_RIGHT, GRASS_LEFT, zn, z1, 0f, EDGING)
                w.floor(GRASS_LEFT, BANK, zn, z1, 0f, MeshWriter.pack(GRASS[0], GRASS[1], GRASS[2]))
                w.floor(BANK, COPING, zn, z1, 0.1f, COPING_STONE)
            }
        }
    }
}
