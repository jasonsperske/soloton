package dev.soloton.roadwork

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Procedural geometry for the waterfront. Coordinates: x to the right, y up, the path runs toward -z.
 *
 * Layout across the path (x, metres):
 * ```
 *  -16 .......... -3.35 | -3.1 ..... -0.31 | -0.19 ..... 2.5 | 2.5 .. 3.3 | 3.3 ...........
 *   grass, trees, lamps | kerb  footpath   | line  bike lane | wall+rail  | seawall, water
 * ```
 */
object Scenery {
    const val CHUNK_LENGTH = 20f
    const val SLAB = 2.5f
    const val PATH_LEFT = -3.1f
    const val PATH_RIGHT = 2.5f
    const val LINE_LEFT = -0.31f
    const val LINE_RIGHT = -0.19f
    const val KERB_LEFT = -3.35f
    const val WALL_RIGHT = 3.3f
    const val RAIL_X = 3.0f
    const val VERGE_END = -16f
    const val WATER_Y = -1.1f
    const val GROUND_FAR_Y = -0.06f

    /** Near-water grid cell and the repeat length of its ripple pattern (water scrolls by distance mod this). */
    const val WATER_CELL = 4f
    const val WATER_PERIOD_ROWS = 8
    const val WATER_PERIOD = WATER_CELL * WATER_PERIOD_ROWS
    const val WATER_NEAR_REACH = 160f

    /** Vertex budget per chunk (checked by tests). */
    const val CHUNK_MAX_VERTICES = 9000

    private val VERGE_X = floatArrayOf(KERB_LEFT, -4.6f, -6.5f, -9f, -12f, VERGE_END)

    // Palette (dawn-neutral base colours; lighting and haze do the rest).
    private val CONCRETE = floatArrayOf(0.66f, 0.63f, 0.58f)
    private val BIKE_LANE = floatArrayOf(0.52f, 0.37f, 0.31f)
    private val LINE = MeshWriter.pack(0.90f, 0.88f, 0.82f)
    private val KERB = MeshWriter.pack(0.56f, 0.55f, 0.53f)
    private val WALL_TOP = MeshWriter.pack(0.50f, 0.50f, 0.49f)
    private val RAIL = MeshWriter.pack(0.20f, 0.25f, 0.28f)
    private val POLE = MeshWriter.pack(0.17f, 0.19f, 0.21f)
    private val WOOD = MeshWriter.pack(0.56f, 0.38f, 0.22f)
    private val IRON = MeshWriter.pack(0.20f, 0.20f, 0.22f)
    private val TRUNK = MeshWriter.pack(0.36f, 0.26f, 0.18f)
    private val CROWNS = arrayOf(
        floatArrayOf(0.24f, 0.42f, 0.22f), floatArrayOf(0.30f, 0.47f, 0.20f),
        floatArrayOf(0.20f, 0.37f, 0.25f), floatArrayOf(0.60f, 0.47f, 0.20f),
    )
    private val CONIFER = floatArrayOf(0.15f, 0.32f, 0.25f)

    /** Builds chunk [index] into [w] (cleared first), in chunk-local space: z from 0 (near end) to -[CHUNK_LENGTH]. */
    fun buildChunk(w: MeshWriter, index: Long, seed: Long, rng: Rng) {
        w.reset()
        rng.reset(Rng.mix(seed xor (index * 0x5DEECE66DL)))
        val rows = (CHUNK_LENGTH / SLAB).toInt()

        // Path slabs: two lanes and the centre line, tiled so nothing overlaps (no z-fighting).
        for (r in 0 until rows) {
            val z0 = -r * SLAB
            val z1 = z0 - SLAB
            val gz = index * rows + r
            val j = (Rng.hash01(gz, 1, seed) - 0.5f) * 0.05f
            w.floor(PATH_LEFT, LINE_LEFT, z0, z1, 0f, MeshWriter.pack(CONCRETE[0] + j, CONCRETE[1] + j, CONCRETE[2] + j))
            val dash = if (gz % 2L == 0L) LINE else MeshWriter.pack(CONCRETE[0] - 0.04f, CONCRETE[1] - 0.04f, CONCRETE[2] - 0.04f)
            w.floor(LINE_LEFT, LINE_RIGHT, z0, z1, 0f, dash)
            val k = (Rng.hash01(gz, 2, seed) - 0.5f) * 0.04f
            w.floor(LINE_RIGHT, PATH_RIGHT, z0, z1, 0f, MeshWriter.pack(BIKE_LANE[0] + k, BIKE_LANE[1] + k, BIKE_LANE[2] + k))
        }
        val zEnd = -CHUNK_LENGTH

        // Left kerb and the wall top on the water side.
        w.box(KERB_LEFT, -0.1f, zEnd, PATH_LEFT, 0.07f, 0f, KERB)
        w.box(PATH_RIGHT, -0.1f, zEnd, WALL_RIGHT, 0.12f, 0f, WALL_TOP)
        // Seawall face, in four blocks of slightly different stone.
        for (b in 0 until 4) {
            val z0 = -b * 5f
            val z1 = z0 - 5f
            val s = 0.33f + (Rng.hash01(index * 4 + b, 3, seed) - 0.5f) * 0.06f
            w.quad(WALL_RIGHT, -1.45f, z0, WALL_RIGHT, -1.45f, z1, WALL_RIGHT, -0.1f, z1, WALL_RIGHT, -0.1f, z0,
                MeshWriter.pack(s, s * 0.98f, s * 0.95f))
        }

        // Railing: posts every slab, a top rail and a middle rail.
        for (r in 0 until rows) {
            val z = -r * SLAB - SLAB * 0.5f
            w.box(RAIL_X - 0.035f, 0.12f, z - 0.035f, RAIL_X + 0.035f, 1.05f, z + 0.035f, RAIL)
        }
        w.box(RAIL_X - 0.04f, 1.03f, zEnd, RAIL_X + 0.04f, 1.09f, 0f, RAIL)
        w.box(RAIL_X - 0.015f, 0.58f, zEnd, RAIL_X + 0.015f, 0.61f, 0f, RAIL)

        grass(w, index, seed, rows)
        lamp(w, -3.8f, -10f)
        if (rng.chance(0.55f)) bench(w, -4.15f, if (rng.chance(0.5f)) -4.5f else -15.5f, rng)

        val trees = 2 + rng.nextInt(3)
        for (t in 0 until trees) {
            val z = -(t + rng.range(0.1f, 0.9f)) * CHUNK_LENGTH / trees
            val x = rng.range(-14.5f, -6.2f)
            tree(w, x, z, rng)
        }
        val bushes = 1 + rng.nextInt(3)
        for (b in 0 until bushes) {
            val x = rng.range(-5.6f, -4.3f)
            val z = rng.range(-19f, -1f)
            if (z in -11.2f..-8.8f) continue // keep the lamp base clear
            val g = rng.range(-0.04f, 0.04f)
            w.icosa(x, 0.15f, z, rng.range(0.4f, 0.7f), rng.range(0.3f, 0.45f), rng.range(0.4f, 0.7f),
                MeshWriter.pack(0.22f + g, 0.38f + g, 0.20f + g))
        }
    }

    /** Height of the grass grid at column [ix] and global row [gz]: flat by the kerb, gently rolling beyond. */
    private fun grassHeight(ix: Int, gz: Long, seed: Long): Float {
        if (ix == 0) return -0.04f
        if (ix == VERGE_X.size - 1) return GROUND_FAR_Y
        return -0.04f + (Rng.hash01(ix.toLong(), gz, seed) - 0.3f) * 0.12f * ix
    }

    private fun grass(w: MeshWriter, index: Long, seed: Long, rows: Int) {
        for (r in 0 until rows) {
            val gz = index * rows + r
            val z0 = -r * SLAB
            val z1 = z0 - SLAB
            for (c in 0 until VERGE_X.size - 1) {
                val xa = VERGE_X[c]  // inner (toward path, larger x)
                val xb = VERGE_X[c + 1]
                val ha0 = grassHeight(c, gz, seed); val ha1 = grassHeight(c, gz + 1, seed)
                val hb0 = grassHeight(c + 1, gz, seed); val hb1 = grassHeight(c + 1, gz + 1, seed)
                val v1 = (Rng.hash01(gz, c * 2L + 10, seed) - 0.5f) * 0.07f
                val v2 = (Rng.hash01(gz, c * 2L + 11, seed) - 0.5f) * 0.07f
                // Seen from above: (xb,z0) -> (xa,z0) -> (xa,z1) is CCW.
                w.tri(xb, hb0, z0, xa, ha0, z0, xa, ha1, z1, MeshWriter.pack(0.30f + v1, 0.45f + v1, 0.23f + v1 * 0.5f))
                w.tri(xb, hb0, z0, xa, ha1, z1, xb, hb1, z1, MeshWriter.pack(0.29f + v2, 0.44f + v2, 0.22f + v2 * 0.5f))
            }
        }
    }

    private fun lamp(w: MeshWriter, x: Float, z: Float) {
        w.prism(x, -0.05f, z, 0.16f, 0.12f, 0.35f, 6, POLE)
        w.prism(x, 0.3f, z, 0.07f, 0.06f, 3.9f, 6, POLE)
        w.beam(x, 4.15f, z, -3.05f, 4.2f, z, 0.07f, 0.07f, POLE)
        w.boxC(-3.05f, 4.08f, z, 0.17f, 0.06f, 0.17f, POLE)
        // Lantern glass: emissive, so it glows at dawn and fades as the shader's glow drops.
        w.boxC(-3.05f, 3.88f, z, 0.13f, 0.15f, 0.13f, MeshWriter.pack(1f, 0.83f, 0.52f, emissive = 1f))
    }

    private fun bench(w: MeshWriter, x: Float, z: Float, rng: Rng) {
        val half = 0.95f
        // Seat (facing the path, +x), backrest, two cast-iron ends.
        w.box(x - 0.25f, 0.42f, z - half, x + 0.25f, 0.48f, z + half, WOOD)
        w.box(x - 0.31f, 0.55f, z - half, x - 0.25f, 0.92f, z + half, WOOD)
        for (s in 0..1) {
            val zz = z + (if (s == 0) -half + 0.15f else half - 0.15f)
            w.box(x - 0.3f, 0f, zz - 0.04f, x + 0.22f, 0.42f, zz + 0.04f, IRON)
            w.box(x - 0.32f, 0.42f, zz - 0.035f, x - 0.26f, 0.9f, zz + 0.035f, IRON)
        }
        if (rng.chance(0.5f)) {
            // Litter bin beside the bench.
            w.prism(x + 0.05f, 0f, z + half + 0.55f, 0.22f, 0.24f, 0.75f, 7, MeshWriter.pack(0.24f, 0.33f, 0.30f))
        }
    }

    private fun tree(w: MeshWriter, x: Float, z: Float, rng: Rng) {
        val scale = rng.range(0.8f, 1.3f)
        if (rng.chance(0.28f)) {
            // Conifer: trunk and three stacked cones.
            w.prism(x, -0.1f, z, 0.16f * scale, 0.12f * scale, 1.2f * scale, 5, TRUNK, rng.nextFloat())
            val g = rng.range(-0.03f, 0.03f)
            val c = MeshWriter.pack(CONIFER[0] + g, CONIFER[1] + g, CONIFER[2] + g)
            var y = 0.8f * scale
            var r = 1.5f * scale
            for (i in 0 until 3) {
                w.prism(x, y, z, r, 0f, 2.2f * scale, 7, c, rng.nextFloat())
                y += 1.3f * scale
                r *= 0.72f
            }
        } else {
            val h = rng.range(1.8f, 2.6f) * scale
            w.prism(x, -0.1f, z, 0.17f * scale, 0.12f * scale, h + 0.4f, 5, TRUNK, rng.nextFloat())
            val pal = CROWNS[if (rng.chance(0.12f)) 3 else rng.nextInt(3)]
            val g = rng.range(-0.04f, 0.04f)
            val c = MeshWriter.pack(pal[0] + g, pal[1] + g, pal[2] + g)
            val r = rng.range(1.2f, 1.7f) * scale
            w.icosa(x, h + r * 0.8f, z, r, r * rng.range(0.85f, 1.1f), r, c)
            if (rng.chance(0.6f)) {
                val c2 = MeshWriter.pack(pal[0] + g + 0.03f, pal[1] + g + 0.03f, pal[2] + g)
                w.icosa(x + rng.range(-0.7f, 0.7f), h + r * 1.5f, z + rng.range(-0.7f, 0.7f), r * 0.7f, r * 0.65f, r * 0.7f, c2)
            }
        }
    }

    /**
     * Faceted water from the seawall out to [WATER_NEAR_REACH], in render space (camera at z = 0).
     * Its ripple heights repeat every [WATER_PERIOD] metres along z, so it can be scrolled by
     * `distance mod WATER_PERIOD` and look continuous.
     */
    fun buildNearWater(w: MeshWriter, seed: Long) {
        w.reset()
        val cols = ((WATER_NEAR_REACH - WALL_RIGHT) / WATER_CELL).toInt() + 1
        val rowsAhead = ((WATER_NEAR_REACH + WATER_PERIOD) / WATER_CELL).toInt()
        val rowStart = -3 // a few rows behind the camera
        fun h(c: Int, r: Int): Float {
            if (c == 0) return WATER_Y
            val rr = Math.floorMod(r, WATER_PERIOD_ROWS)
            return WATER_Y + (Rng.hash01(c.toLong(), rr.toLong(), seed + 77) - 0.5f) * 0.22f
        }
        for (r in rowStart until rowsAhead) {
            val z0 = -r * WATER_CELL
            val z1 = z0 - WATER_CELL
            for (c in 0 until cols) {
                val x0 = WALL_RIGHT + c * WATER_CELL
                val x1 = x0 + WATER_CELL
                // Skip cells beyond the reach radius; the far water plane covers them.
                val mx = x0 + WATER_CELL * 0.5f
                val mz = z0 - WATER_CELL * 0.5f
                if (sqrt(mx * mx + mz * mz) > WATER_NEAR_REACH + WATER_PERIOD) continue
                val v = (Rng.hash01(c.toLong(), Math.floorMod(r, WATER_PERIOD_ROWS).toLong(), seed + 5) - 0.5f) * 0.03f
                w.tri(x0, h(c, r), z0, x1, h(c + 1, r), z0, x1, h(c + 1, r + 1), z1, MeshWriter.pack(0.16f + v, 0.31f + v, 0.38f + v))
                w.tri(x0, h(c, r), z0, x1, h(c + 1, r + 1), z1, x0, h(c, r + 1), z1, MeshWriter.pack(0.15f + v, 0.30f + v, 0.37f + v))
            }
        }
    }

    // Grid lines for the big far planes: dense near the camera so per-vertex fog interpolates well.
    private val FAR_STEPS = floatArrayOf(0f, 20f, 45f, 80f, 120f, 170f, 240f, 350f, 550f, 900f, 1500f, 3200f)

    /**
     * The far layer, in render space and drawn without depth testing, so it is written back to front:
     * water, ground, far shore, hills, city, boats. It moves with the camera (it is far enough away
     * that parallax would be a pixel or two at most).
     */
    fun buildFar(w: MeshWriter, seed: Long, rng: Rng) {
        w.reset()
        rng.reset(seed * 7919 + 13)
        val water = MeshWriter.pack(0.16f, 0.31f, 0.38f)
        val ground = MeshWriter.pack(0.28f, 0.42f, 0.23f)
        val zSteps = FAR_STEPS
        // Water plane: x from the seawall outward; z from behind the camera to the horizon.
        for (i in 0 until zSteps.size - 1) {
            val z0 = if (i == 0) 60f else -zSteps[i]
            val z1 = -zSteps[i + 1]
            for (j in 0 until zSteps.size - 1) {
                val x0 = WALL_RIGHT + zSteps[j]
                val x1 = WALL_RIGHT + zSteps[j + 1]
                w.floor(x0, x1, z0, z1, WATER_Y, water)
                // Ground on the left, mirrored.
                w.floor(VERGE_END - zSteps[j + 1], VERGE_END - zSteps[j], z0, z1, GROUND_FAR_Y, ground)
            }
        }

        // Far hills behind the city, across the water (right-front).
        ridge(w, rng, cx0 = 1700f, cz0 = -2300f, cx1 = 2600f, cz1 = -900f, depth = 160f, hMin = 50f, hMax = 120f,
            segments = 14, color = floatArrayOf(0.34f, 0.42f, 0.44f))
        // Far shore strip under the city.
        val shore = MeshWriter.pack(0.36f, 0.40f, 0.36f)
        w.quad(360f, 1.5f, -560f, 1600f, 1.5f, -560f, 1600f, 1.5f, -2000f, 360f, 1.5f, -2000f, shore)
        w.quad(360f, WATER_Y, -560f, 1600f, WATER_Y, -560f, 1600f, 1.5f, -560f, 360f, 1.5f, -560f, shore)
        city(w, rng)

        // Two ranges of low hills on the left, far one first.
        ridge(w, rng, cx0 = -900f, cz0 = 200f, cx1 = -900f, cz1 = -2600f, depth = 260f, hMin = 45f, hMax = 110f,
            segments = 18, color = floatArrayOf(0.30f, 0.40f, 0.36f))
        ridge(w, rng, cx0 = -360f, cz0 = 150f, cx1 = -420f, cz1 = -2200f, depth = 140f, hMin = 14f, hMax = 42f,
            segments = 20, color = floatArrayOf(0.27f, 0.42f, 0.27f))

        // A few small sailing boats, far ones first.
        boat(w, 210f, -420f, 1.3f)
        boat(w, 140f, -260f, 1f)
        boat(w, 52f, -150f, 0.9f)
    }

    /**
     * A rolling ridge whose crest runs from (cx0, cz0) to (cx1, cz1), drawn far end first. Only the
     * slope that faces the path (toward x = 0) is built; the other one is never seen.
     */
    private fun ridge(
        w: MeshWriter, rng: Rng,
        cx0: Float, cz0: Float, cx1: Float, cz1: Float,
        depth: Float, hMin: Float, hMax: Float, segments: Int, color: FloatArray,
    ) {
        // Order the ends so we start with the one farther from the camera.
        val farFirst = (cx0 * cx0 + cz0 * cz0) >= (cx1 * cx1 + cz1 * cz1)
        val sx = if (farFirst) cx0 else cx1
        val sz = if (farFirst) cz0 else cz1
        val ex = if (farFirst) cx1 else cx0
        val ez = if (farFirst) cz1 else cz0
        // Unit direction from the crest toward the camera side, perpendicular to the ridge in xz.
        var nx = -(ez - sz)
        var nz = ex - sx
        val nl = sqrt(nx * nx + nz * nz)
        nx /= nl; nz /= nl
        val mx = (sx + ex) * 0.5f
        val mz = (sz + ez) * 0.5f
        if (nx * -mx + nz * -mz < 0f) { nx = -nx; nz = -nz }
        val ph = rng.range(0f, 6.28f)
        var pcx = 0f; var pcz = 0f; var ph0 = 0f; var pmx = 0f; var pmz = 0f; var pm0 = 0f; var pbx = 0f; var pbz = 0f
        for (i in 0..segments) {
            val t = i / segments.toFloat()
            val x = sx + (ex - sx) * t
            val z = sz + (ez - sz) * t
            val wave = 0.5f + 0.35f * sin(t * 9.1f + ph) + 0.15f * sin(t * 23f + ph * 2f)
            val h = hMin + (hMax - hMin) * wave.coerceIn(0f, 1f)
            val bx = x + nx * depth
            val bz = z + nz * depth
            val mxp = x + nx * depth * 0.45f
            val mzp = z + nz * depth * 0.45f
            val mh = h * rng.range(0.45f, 0.62f)
            if (i > 0) {
                val g1 = rng.range(-0.025f, 0.025f)
                val g2 = rng.range(-0.025f, 0.025f)
                val upper = MeshWriter.pack(color[0] + g1, color[1] + g1, color[2] + g1)
                val lower = MeshWriter.pack(color[0] + g2 - 0.02f, color[1] + g2, color[2] + g2 - 0.02f)
                upper(w, pcx, ph0, pcz, x, h, z, mxp, mh, mzp, pmx, pm0, pmz, nx, nz, upper)
                upper(w, pmx, pm0, pmz, mxp, mh, mzp, bx, WATER_Y, bz, pbx, WATER_Y, pbz, nx, nz, lower)
            }
            pcx = x; pcz = z; ph0 = h; pmx = mxp; pmz = mzp; pm0 = mh; pbx = bx; pbz = bz
        }
    }

    /** Quad that faces toward (nx, 0, nz) and upward. */
    private fun upper(
        w: MeshWriter,
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float,
        nx: Float, nz: Float, color: Float,
    ) {
        // Normal of (p0,p1,p2): decide winding so it agrees with (nx, +, nz).
        val ux = x1 - x0; val uy = y1 - y0; val uz = z1 - z0
        val vx = x2 - x0; val vy = y2 - y0; val vz = z2 - z0
        val cx = uy * vz - uz * vy
        val cz = ux * vy - uy * vx
        if (cx * nx + cz * nz >= 0f) w.quad(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, color)
        else w.quad(x3, y3, z3, x2, y2, z2, x1, y1, z1, x0, y0, z0, color)
    }

    private fun city(w: MeshWriter, rng: Rng) {
        // Generate an original, irregular cluster of towers, then draw it far to near.
        val n = 42
        val bx = FloatArray(n); val bz = FloatArray(n); val bw = FloatArray(n); val bd = FloatArray(n)
        val bh = FloatArray(n); val shade = FloatArray(n); val dist = FloatArray(n); val kind = IntArray(n)
        for (i in 0 until n) {
            val az = Math.toRadians(rng.range(25f, 41f).toDouble())
            // Taller buildings cluster toward the middle of the group.
            val centre = 1f - kotlin.math.abs((Math.toDegrees(az).toFloat() - 33f) / 8f)
            val d = rng.range(950f, 1350f)
            bx[i] = (sin(az) * d).toFloat()
            bz[i] = (-cos(az) * d).toFloat()
            bw[i] = rng.range(18f, 46f)
            bd[i] = rng.range(18f, 40f)
            bh[i] = rng.range(18f, 50f) + centre * rng.range(10f, 90f)
            if (rng.chance(0.08f)) bh[i] += 70f
            shade[i] = rng.range(-0.04f, 0.04f)
            kind[i] = rng.nextInt(4)
            dist[i] = d
        }
        val order = (0 until n).sortedByDescending { dist[it] }
        for (i in order) {
            val s = shade[i]
            val c = MeshWriter.pack(0.34f + s, 0.38f + s, 0.46f + s)
            val x = bx[i]; val z = bz[i]; val hw = bw[i] * 0.5f; val hd = bd[i] * 0.5f; val h = bh[i]
            w.box(x - hw, 0f, z - hd, x + hw, h, z + hd, c)
            when (kind[i]) {
                0 -> w.box(x - hw * 0.6f, h, z - hd * 0.6f, x + hw * 0.6f, h + h * 0.18f, z + hd * 0.6f, c)
                1 -> w.prism(x, h, z, hw * 0.8f, 0f, hw * 1.1f, 4, c, (PI / 4).toFloat())
                2 -> w.box(x - 0.8f, h, z - 0.8f, x + 0.8f, h + 22f, z + 0.8f, c)
                else -> {}
            }
        }
    }

    private fun boat(w: MeshWriter, x: Float, z: Float, s: Float) {
        val hull = MeshWriter.pack(0.85f, 0.84f, 0.80f)
        val sail = MeshWriter.pack(0.95f, 0.93f, 0.88f)
        w.box(x - 1.1f * s, WATER_Y - 0.3f, z - 3.5f * s, x + 1.1f * s, WATER_Y + 0.6f * s, z + 3.5f * s, hull)
        w.beam(x, WATER_Y + 0.6f * s, z, x, WATER_Y + 9f * s, z, 0.15f, 0.15f, IRON)
        // Mainsail facing the camera side, double-sided so it reads from any angle.
        w.tri(x + 0.05f, WATER_Y + 1.2f * s, z + 0.2f, x + 0.05f, WATER_Y + 1.2f * s, z + 3.2f * s, x + 0.05f, WATER_Y + 8.8f * s, z + 0.2f, sail)
        w.tri(x - 0.05f, WATER_Y + 1.2f * s, z + 0.2f, x - 0.05f, WATER_Y + 8.8f * s, z + 0.2f, x - 0.05f, WATER_Y + 1.2f * s, z + 3.2f * s, sail)
        w.tri(x - 0.05f, WATER_Y + 1.4f * s, z - 0.4f, x - 0.05f, WATER_Y + 8f * s, z - 0.3f, x - 0.05f, WATER_Y + 1.0f * s, z - 3.2f * s, sail)
        w.tri(x + 0.05f, WATER_Y + 1.4f * s, z - 0.4f, x + 0.05f, WATER_Y + 1.0f * s, z - 3.2f * s, x + 0.05f, WATER_Y + 8f * s, z - 0.3f, sail)
    }
}
