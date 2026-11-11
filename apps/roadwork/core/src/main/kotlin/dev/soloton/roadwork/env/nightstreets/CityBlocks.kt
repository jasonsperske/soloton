package dev.soloton.roadwork.env.nightstreets

import dev.soloton.roadwork.MeshWriter
import dev.soloton.roadwork.Rng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Geometry for the city at night: x east, y up, z south.
 *
 * The city is endless and made a **cell** at a time. Cell (i, j) is one [CityGrid.PERIOD]-metre
 * square with the junction of node (i, j) at its north-west corner: that junction, the block of
 * street running east from it, the block running south, and the city block they enclose. A cell's
 * contents come from its coordinates and the seed alone, so it is the same every time the rider
 * comes back to it.
 *
 * What stands on a block depends on its [zone]: towers downtown, mid-rise blocks around that,
 * houses with gardens further out, and here and there a park. Nothing is textured: a building's
 * lit windows are bands of bright colour stacked between bands of wall, and the scene's light is
 * set so those colours show as written (see [NightSky]).
 *
 * Faces never overlap in the same plane (walls and window bands are tiled, as are the road's
 * lanes and lines), because the tablet's 16-bit depth buffer cannot tell such faces apart at a
 * distance.
 */
object CityBlocks {
    const val DOWNTOWN = 0
    const val MIDRISE = 1
    const val HOUSES = 2
    const val PARK = 3

    /** Vertex budget of one cell (checked by tests). */
    const val CELL_MAX_VERTICES = 6600

    /** The most storeys of windows a tower is given. */
    const val TOWER_BANDS = 10

    /** The pavement runs this far in from the kerb; buildings stand inside it. */
    const val PAVEMENT = 3f
    private const val KERB = CityGrid.HALF_STREET
    private const val FAR_KERB = CityGrid.PERIOD - CityGrid.HALF_STREET

    private val TARMAC = MeshWriter.pack(0.15f, 0.16f, 0.20f)
    private val JUNCTION = MeshWriter.pack(0.17f, 0.18f, 0.22f)
    private val BIKE_LANE = MeshWriter.pack(0.34f, 0.15f, 0.17f)
    private val CENTRE_LINE = MeshWriter.pack(0.85f, 0.72f, 0.28f)
    private val PAVING = MeshWriter.pack(0.30f, 0.30f, 0.35f)
    private val PLAZA = MeshWriter.pack(0.22f, 0.22f, 0.27f)
    private val LAWN = MeshWriter.pack(0.09f, 0.22f, 0.14f)
    private val POLE = MeshWriter.pack(0.20f, 0.21f, 0.25f)
    private val LAMP = MeshWriter.pack(1.00f, 0.93f, 0.68f)
    private val TRUNK = MeshWriter.pack(0.20f, 0.15f, 0.12f)
    private val LEAVES = arrayOf(MeshWriter.pack(0.08f, 0.24f, 0.15f), MeshWriter.pack(0.10f, 0.28f, 0.14f), MeshWriter.pack(0.07f, 0.20f, 0.17f))
    private val ROOF_LIGHT = MeshWriter.pack(1.00f, 0.22f, 0.18f)
    private val DARK_WINDOW = MeshWriter.pack(0.09f, 0.11f, 0.17f)

    /** Wall colours by zone: cool glass and stone downtown, warmer brick further out. */
    private val WALLS = arrayOf(
        arrayOf(floatArrayOf(0.13f, 0.16f, 0.26f), floatArrayOf(0.17f, 0.18f, 0.24f), floatArrayOf(0.11f, 0.19f, 0.25f), floatArrayOf(0.20f, 0.17f, 0.25f)),
        arrayOf(floatArrayOf(0.24f, 0.18f, 0.18f), floatArrayOf(0.20f, 0.20f, 0.24f), floatArrayOf(0.26f, 0.22f, 0.18f), floatArrayOf(0.17f, 0.21f, 0.22f)),
        arrayOf(floatArrayOf(0.30f, 0.22f, 0.20f), floatArrayOf(0.28f, 0.27f, 0.24f), floatArrayOf(0.22f, 0.25f, 0.28f), floatArrayOf(0.31f, 0.26f, 0.19f)),
    )

    /** Lit windows: warm lamplight, cooler office light, a pale blue screen glow. */
    private val LIT = arrayOf(floatArrayOf(1.00f, 0.80f, 0.42f), floatArrayOf(0.95f, 0.92f, 0.74f), floatArrayOf(0.62f, 0.82f, 1.00f), floatArrayOf(1.00f, 0.66f, 0.36f))
    private val HOUSE_ROOF = arrayOf(MeshWriter.pack(0.26f, 0.13f, 0.12f), MeshWriter.pack(0.16f, 0.17f, 0.21f), MeshWriter.pack(0.22f, 0.17f, 0.12f))

    /**
     * What stands on cell (i, j)'s block. Downtown is where a slowly varying value across the
     * city runs high, houses where it runs low, and about one block in ten is a park.
     */
    fun zone(i: Int, j: Int, seed: Long): Int {
        if (Rng.hash01(i.toLong(), j.toLong(), seed xor 0x9A4CL) < PARK_SHARE) return PARK
        val v = density(i / 4f, j / 4f, seed)
        return if (v > 0.58f) DOWNTOWN else if (v > 0.40f) MIDRISE else HOUSES
    }

    const val PARK_SHARE = 0.10f

    /** Smooth noise in 0..1 with one feature per unit. */
    private fun density(x: Float, z: Float, seed: Long): Float {
        val fx = floor(x)
        val fz = floor(z)
        val ix = fx.toLong()
        val iz = fz.toLong()
        val tx = (x - fx).let { it * it * (3f - 2f * it) }
        val tz = (z - fz).let { it * it * (3f - 2f * it) }
        val a = Rng.hash01(ix, iz, seed xor 0xD3L)
        val b = Rng.hash01(ix + 1, iz, seed xor 0xD3L)
        val c = Rng.hash01(ix, iz + 1, seed xor 0xD3L)
        val d = Rng.hash01(ix + 1, iz + 1, seed xor 0xD3L)
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * tz
    }

    /** Builds cell (i, j) into [w] (cleared first), in cell-local space: the node at the origin, the block to its south-east. */
    fun buildCell(w: MeshWriter, i: Int, j: Int, seed: Long, rng: Rng) {
        w.reset()
        rng.reset(Rng.mix(seed xor (i * 0x5DEECE66DL) xor (j * 0x2545F491L)))
        val zone = zone(i, j, seed)

        // The junction, then the two streets, each tiled across: bike lane, lane, line, lane, bike lane.
        w.floor(-KERB, KERB, KERB, -KERB, 0f, JUNCTION)
        val edges = floatArrayOf(-KERB, -CityGrid.LANE_SPLIT, -LINE, LINE, CityGrid.LANE_SPLIT, KERB)
        val paints = floatArrayOf(BIKE_LANE, TARMAC, CENTRE_LINE, TARMAC, BIKE_LANE)
        for (k in 0 until 5) {
            w.floor(KERB, FAR_KERB, edges[k + 1], edges[k], 0f, paints[k])
            w.floor(edges[k], edges[k + 1], FAR_KERB, KERB, 0f, paints[k])
        }

        // The block: a ring of pavement round whatever is inside.
        val inner = KERB + PAVEMENT
        val farInner = FAR_KERB - PAVEMENT
        w.floor(KERB, FAR_KERB, inner, KERB, 0f, PAVING)
        w.floor(KERB, FAR_KERB, FAR_KERB, farInner, 0f, PAVING)
        w.floor(KERB, inner, farInner, inner, 0f, PAVING)
        w.floor(farInner, FAR_KERB, farInner, inner, 0f, PAVING)
        w.floor(inner, farInner, farInner, inner, 0f, if (zone == DOWNTOWN || zone == MIDRISE) PLAZA else LAWN)

        // Street lamps on the block's north and west pavements, and a signal post at each corner of the junction.
        for (at in floatArrayOf(28f, 72f)) {
            streetLamp(w, at, KERB + 1.2f, 0f, -1f)
            streetLamp(w, KERB + 1.2f, at, -1f, 0f)
        }
        for (sx in -1..1 step 2) for (sz in -1..1 step 2) {
            w.prism(sx * SIGNAL_CORNER, 0f, sz * SIGNAL_CORNER, 0.12f, 0.12f, SIGNAL_HEIGHT, 3, POLE)
        }

        when (zone) {
            DOWNTOWN -> downtown(w, rng, inner, farInner)
            MIDRISE -> midrise(w, rng, inner, farInner)
            HOUSES -> houses(w, rng, inner, farInner)
            else -> park(w, rng, inner, farInner)
        }
    }

    private const val LINE = 0.15f

    /** Where a junction's signal posts stand (each side of the node, both ways) and how tall they are. */
    const val SIGNAL_CORNER = CityGrid.HALF_STREET + 0.7f
    const val SIGNAL_HEIGHT = 5.2f

    /** A post with a lit head leaning [dx], [dz] toward the street. */
    private fun streetLamp(w: MeshWriter, x: Float, z: Float, dx: Float, dz: Float) {
        w.prism(x, 0f, z, 0.11f, 0.08f, 6.2f, 3, POLE)
        w.boxC(x + dx * 0.9f, 6.2f, z + dz * 0.9f, 0.25f + 0.5f * kotlin.math.abs(dx), 0.12f, 0.25f + 0.5f * kotlin.math.abs(dz), LAMP)
    }

    /** Four towers, each with a narrower top storey and a red light on a mast. */
    private fun downtown(w: MeshWriter, rng: Rng, inner: Float, farInner: Float) {
        val half = (farInner - inner) / 2f
        for (q in 0 until 4) {
            val x0 = inner + (q % 2) * half
            val z0 = inner + (q / 2) * half
            val side = rng.range(26f, half - 5f)
            val depth = rng.range(26f, half - 5f)
            val x = x0 + (half - side) / 2f
            val z = z0 + (half - depth) / 2f
            val height = rng.range(45f, 150f)
            val wall = WALLS[DOWNTOWN][rng.nextInt(WALLS[DOWNTOWN].size)]
            // A storey of windows about every eight metres of height, up to [TOWER_BANDS] of them.
            building(w, x, z, x + side, z + depth, 0f, height, (height / 8f).roundToInt().coerceIn(5, TOWER_BANDS), wall, 0.5f, rng)
            val inset = side * 0.2f
            val insetZ = depth * 0.2f
            building(w, x + inset, z + insetZ, x + side - inset, z + depth - insetZ, height, height + rng.range(6f, 14f), 1, wall, 0.5f, rng)
            if (rng.chance(0.6f)) {
                val mx = x + side / 2f
                val mz = z + depth / 2f
                val top = height + 16f
                w.prism(mx, height + 6f, mz, 0.3f, 0.15f, 12f, 3, POLE)
                w.boxC(mx, top + 2.4f, mz, 0.5f, 0.5f, 0.5f, ROOF_LIGHT)
            }
        }
    }

    /** Nine mid-rise blocks in a three by three grid, of varied height. */
    private fun midrise(w: MeshWriter, rng: Rng, inner: Float, farInner: Float) {
        val lot = (farInner - inner) / 3f
        for (q in 0 until 9) {
            val x0 = inner + (q % 3) * lot
            val z0 = inner + (q / 3) * lot
            val side = rng.range(17f, lot - 3f)
            val depth = rng.range(17f, lot - 3f)
            val x = x0 + (lot - side) / 2f
            val z = z0 + (lot - depth) / 2f
            val wall = WALLS[MIDRISE][rng.nextInt(WALLS[MIDRISE].size)]
            val height = rng.range(13f, 42f)
            building(w, x, z, x + side, z + depth, 0f, height, (height / 7f).roundToInt().coerceIn(2, 5), wall, 0.5f, rng)
        }
    }

    /** Houses on a four by four grid of plots, with a tree on the plots left empty. */
    private fun houses(w: MeshWriter, rng: Rng, inner: Float, farInner: Float) {
        val lot = (farInner - inner) / 4f
        for (q in 0 until 16) {
            val cx = inner + (q % 4 + 0.5f) * lot
            val cz = inner + (q / 4 + 0.5f) * lot
            if (rng.chance(0.28f)) {
                tree(w, cx + rng.range(-3f, 3f), cz + rng.range(-3f, 3f), rng)
                continue
            }
            val hx = rng.range(5.5f, 7.5f)
            val hz = rng.range(4.5f, 6.5f)
            val height = rng.range(5.5f, 9f)
            val wall = WALLS[HOUSES][rng.nextInt(WALLS[HOUSES].size)]
            building(w, cx - hx, cz - hz, cx + hx, cz + hz, 0f, height, 1, wall, 0.5f, rng)
            w.box(cx - hx - 0.6f, height, cz - hz - 0.6f, cx + hx + 0.6f, height + 0.9f, cz + hz + 0.6f, HOUSE_ROOF[rng.nextInt(HOUSE_ROOF.size)])
        }
    }

    /** A dozen trees on grass, with four lamps along a path through the middle. */
    private fun park(w: MeshWriter, rng: Rng, inner: Float, farInner: Float) {
        for (t in 0 until 12) tree(w, rng.range(inner + 5f, farInner - 5f), rng.range(inner + 5f, farInner - 5f), rng)
        val mid = (inner + farInner) / 2f
        for (k in 0 until 4) streetLamp(w, inner + 12f + k * 20f, mid, 0f, 0f)
    }

    private fun tree(w: MeshWriter, x: Float, z: Float, rng: Rng) {
        val height = rng.range(3f, 5.5f)
        val crown = rng.range(2.2f, 3.6f)
        w.prism(x, 0f, z, 0.28f, 0.2f, height, 3, TRUNK)
        w.icosa(x, height + crown * 0.7f, z, crown, crown * 1.1f, crown, LEAVES[rng.nextInt(LEAVES.size)])
    }

    /**
     * A box of a building from [y0] up to [y1], its four walls cut into `2 * bands + 1` strips: wall,
     * then a storey of windows, then wall, and so on. A storey of windows is cut again into bays
     * along each wall, each bay lit with probability [litShare] or dark.
     */
    private fun building(
        w: MeshWriter, x0: Float, z0: Float, x1: Float, z1: Float, y0: Float, y1: Float,
        bands: Int, wall: FloatArray, litShare: Float, rng: Rng,
    ) {
        val strips = 2 * bands + 1
        // A storey of windows is a little shorter than the wall between storeys.
        val storey = (y1 - y0) / (bands + WALL_SHARE)
        val v = rng.range(-0.02f, 0.02f)
        val wallColour = MeshWriter.pack(wall[0] + v, wall[1] + v, wall[2] + v)
        val baysX = ((x1 - x0) / BAY_WIDTH).roundToInt().coerceIn(1, MAX_BAYS)
        val baysZ = ((z1 - z0) / BAY_WIDTH).roundToInt().coerceIn(1, MAX_BAYS)
        var b = y0
        for (s in 0 until strips) {
            val a = b
            b = if (s == strips - 1) y1 else a + storey * (if (s % 2 == 0) WALL_SHARE else 1f - WALL_SHARE)
            if (s % 2 == 0) {
                w.quad(x0, a, z1, x1, a, z1, x1, b, z1, x0, b, z1, wallColour)
                w.quad(x1, a, z0, x0, a, z0, x0, b, z0, x1, b, z0, wallColour)
                w.quad(x1, a, z1, x1, a, z0, x1, b, z0, x1, b, z1, wallColour)
                w.quad(x0, a, z0, x0, a, z1, x0, b, z1, x0, b, z0, wallColour)
                continue
            }
            // A storey of windows: each wall in bays side by side, each bay lit or dark on its own.
            for (k in 0 until baysX) {
                val xa = x0 + (x1 - x0) * k / baysX
                val xb = x0 + (x1 - x0) * (k + 1) / baysX
                w.quad(xa, a, z1, xb, a, z1, xb, b, z1, xa, b, z1, window(litShare, rng))
                w.quad(xb, a, z0, xa, a, z0, xa, b, z0, xb, b, z0, window(litShare, rng))
            }
            for (k in 0 until baysZ) {
                val za = z0 + (z1 - z0) * k / baysZ
                val zb = z0 + (z1 - z0) * (k + 1) / baysZ
                w.quad(x1, a, zb, x1, a, za, x1, b, za, x1, b, zb, window(litShare, rng))
                w.quad(x0, a, za, x0, a, zb, x0, b, zb, x0, b, za, window(litShare, rng))
            }
        }
        w.quad(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, MeshWriter.pack(wall[0] * 0.7f, wall[1] * 0.7f, wall[2] * 0.7f))
    }

    /** One bay of windows: lit, in one of the [LIT] colours at some brightness, with probability [litShare]; otherwise dark. */
    private fun window(litShare: Float, rng: Rng): Float {
        val on = rng.chance(litShare)
        val c = LIT[rng.nextInt(LIT.size)]
        val dim = rng.range(0.65f, 1f)
        return if (on) MeshWriter.pack(c[0] * dim, c[1] * dim, c[2] * dim) else DARK_WINDOW
    }

    /** A wall is cut into bays about this wide, at most [MAX_BAYS] of them. */
    private const val BAY_WIDTH = 7f
    private const val MAX_BAYS = 4

    /** The share of each storey's height that is wall; the rest is its windows. */
    private const val WALL_SHARE = 0.58f

    // ---- the distant skyline ----

    const val SKYLINE_TOWERS = 44
    const val SKYLINE_MAX_VERTICES = SKYLINE_TOWERS * 270

    /**
     * A ring of far-off towers all round, built farthest first so they can be drawn without
     * depth. It moves with the rider and so never comes closer, like the moon.
     */
    fun buildSkyline(w: MeshWriter, seed: Long, rng: Rng) {
        w.reset()
        rng.reset(Rng.mix(seed xor 0x5C11EL))
        val order = IntArray(SKYLINE_TOWERS) { it }
        val radius = FloatArray(SKYLINE_TOWERS) { rng.range(620f, 980f) }
        val sorted = order.sortedByDescending { radius[it] }
        for (k in sorted) {
            val a = (2 * PI * k / SKYLINE_TOWERS).toFloat() + rng.range(-0.05f, 0.05f)
            val x = radius[k] * cos(a)
            val z = radius[k] * sin(a)
            val half = rng.range(16f, 34f)
            val height = rng.range(50f, 230f)
            val wall = WALLS[DOWNTOWN][rng.nextInt(WALLS[DOWNTOWN].size)]
            building(w, x - half, z - half, x + half, z + half, -2f, height, 2, wall, 0.7f, rng)
        }
    }

    // ---- cars and traffic lights ----

    const val CAR_VERTICES = 6 * 36
    const val CARS_MAX_VERTICES = CityTraffic.MAX_CARS * CAR_VERTICES

    private val PAINTS = arrayOf(
        MeshWriter.pack(0.62f, 0.14f, 0.14f), MeshWriter.pack(0.16f, 0.30f, 0.62f), MeshWriter.pack(0.72f, 0.72f, 0.76f),
        MeshWriter.pack(0.20f, 0.46f, 0.36f), MeshWriter.pack(0.80f, 0.60f, 0.16f), MeshWriter.pack(0.24f, 0.24f, 0.30f),
    )
    private val CAR_GLASS = MeshWriter.pack(0.10f, 0.13f, 0.20f)
    private val HEADLIGHT = MeshWriter.pack(1.00f, 0.97f, 0.82f)
    private val TAIL_LIGHT = MeshWriter.pack(1.00f, 0.16f, 0.12f)

    /** Every car in [traffic], in metres relative to ([originX], [originZ]): a body, a cabin, two headlights and two tail lights. */
    fun buildCars(w: MeshWriter, traffic: CityTraffic, originX: Float, originZ: Float) {
        w.reset()
        val halfLength = CityTraffic.CAR_LENGTH / 2f
        val halfWidth = CityTraffic.CAR_WIDTH / 2f
        for (i in 0 until CityTraffic.MAX_CARS) {
            if (!traffic.alive[i]) continue
            // Car space: forward is -z. A heading measured from east toward south is a turn of -(heading + 90) degrees about y.
            val yaw = -(Math.toDegrees(traffic.heading[i].toDouble()).toFloat() + 90f)
            w.transform.idt().translate(traffic.x[i] - originX, 0f, traffic.z[i] - originZ).rotate(0f, 1f, 0f, yaw)
            w.box(-halfWidth, 0.3f, -halfLength, halfWidth, 0.95f, halfLength, PAINTS[traffic.paint[i] % PAINTS.size], bottom = true)
            w.box(-halfWidth + 0.12f, 0.95f, -0.7f, halfWidth - 0.12f, 1.5f, 1.3f, CAR_GLASS, bottom = true)
            for (s in -1..1 step 2) {
                w.boxC(s * 0.6f, 0.68f, -halfLength - 0.03f, 0.2f, 0.11f, 0.05f, HEADLIGHT)
                w.boxC(s * 0.62f, 0.72f, halfLength + 0.03f, 0.2f, 0.09f, 0.05f, TAIL_LIGHT)
            }
        }
        w.transform.idt()
    }

    /** How many junctions each way from the rider's have their lamps drawn. */
    const val SIGNAL_RANGE = 2
    const val SIGNALS_MAX_VERTICES = (2 * SIGNAL_RANGE + 1) * (2 * SIGNAL_RANGE + 1) * 4 * 36
    private val GO = MeshWriter.pack(0.25f, 1.00f, 0.45f)
    private val STOP = MeshWriter.pack(1.00f, 0.20f, 0.14f)

    /**
     * The lamps on the signal posts of the junctions round node ([nodeX], [nodeZ]), red or green
     * for [time], relative to ([originX], [originZ]). Traffic heading [dir] reads the lamp on the
     * far right corner of the junction.
     */
    fun buildSignals(w: MeshWriter, nodeX: Int, nodeZ: Int, time: Float, seed: Long, originX: Float, originZ: Float) {
        w.reset()
        for (dz in -SIGNAL_RANGE..SIGNAL_RANGE) for (dx in -SIGNAL_RANGE..SIGNAL_RANGE) {
            val ix = nodeX + dx
            val iz = nodeZ + dz
            val green = CityGrid.greenAxis(ix, iz, time, seed)
            for (dir in 0..3) {
                // Far right of the junction for traffic heading `dir`: ahead of the node and to its right.
                val fx = CityGrid.DX[dir]
                val fz = CityGrid.DZ[dir]
                val x = ix * CityGrid.PERIOD + (fx - fz) * SIGNAL_CORNER - originX
                val z = iz * CityGrid.PERIOD + (fz + fx) * SIGNAL_CORNER - originZ
                w.boxC(x, SIGNAL_HEIGHT + 0.35f, z, 0.3f, 0.35f, 0.3f, if (green == CityGrid.axis(dir)) GO else STOP)
            }
        }
    }
}
