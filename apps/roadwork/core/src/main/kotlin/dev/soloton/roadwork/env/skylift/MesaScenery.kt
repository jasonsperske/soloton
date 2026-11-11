package dev.soloton.roadwork.env.skylift

import dev.soloton.roadwork.MeshWriter
import dev.soloton.roadwork.Rng
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural geometry for the mesa: a rust-coloured desert under a teal sky. Coordinates: x to
 * the right, y up, the trail runs toward -z.
 *
 * A packed-earth trail [TRAIL_HALF] metres either side of the middle runs across sand marked with
 * darker wind ripples. Beside it stand rock spires and bulb plants, and above them hang floating
 * rocks, which the rider climbs among as the resistance goes up. Nothing but the low trail
 * markers comes within [CLEAR_HALF] metres of the middle of the trail, at any height.
 */
object MesaScenery {
    const val CHUNK_LENGTH = 40f

    /** How far the sand reaches either side; wide enough to fill the view from full altitude. */
    const val HALF_WIDTH = 300f
    const val TRAIL_HALF = 3f

    /** Nothing taller than a trail marker is built closer to the middle of the trail than this. */
    const val CLEAR_HALF = 5f
    const val MARKER_X = 3.6f
    const val MARKER_SPACING = 10f

    /** One light band of sand and one dark ripple. */
    const val RIPPLE_PERIOD = 5f
    const val RIPPLE_WIDTH = 1.8f

    /** Vertex budget per chunk (checked by tests). */
    const val CHUNK_MAX_VERTICES = 2200
    const val FAR_MAX_VERTICES = 2400
    const val MESAS = 26

    /** The sand's base colour; ripples fade into it with distance. */
    val SAND = floatArrayOf(0.80f, 0.62f, 0.40f)
    private val RIPPLE = floatArrayOf(0.66f, 0.47f, 0.30f)
    private val TRAIL = floatArrayOf(0.56f, 0.41f, 0.30f)
    private val MARKER = MeshWriter.pack(0.96f, 0.88f, 0.60f, MeshWriter.OBJECT)
    private val SPIRES = arrayOf(
        floatArrayOf(0.62f, 0.33f, 0.22f), floatArrayOf(0.70f, 0.40f, 0.25f),
        floatArrayOf(0.54f, 0.29f, 0.23f), floatArrayOf(0.66f, 0.45f, 0.30f),
    )
    private val STEM = MeshWriter.pack(0.36f, 0.30f, 0.33f, MeshWriter.OBJECT)
    private val BULBS = arrayOf(
        floatArrayOf(0.14f, 0.62f, 0.60f), floatArrayOf(0.20f, 0.70f, 0.55f), floatArrayOf(0.12f, 0.52f, 0.66f),
    )
    private val FLOATER_TOP = floatArrayOf(0.60f, 0.46f, 0.38f)
    private val FLOATER_UNDER = MeshWriter.pack(0.40f, 0.27f, 0.25f, MeshWriter.OBJECT)
    private val MESA = arrayOf(
        floatArrayOf(0.52f, 0.31f, 0.28f), floatArrayOf(0.58f, 0.36f, 0.29f), floatArrayOf(0.46f, 0.28f, 0.29f),
    )

    /** Builds chunk [index] into [w] (cleared first), in chunk-local space: z from 0 (near end) to -[CHUNK_LENGTH]. */
    fun buildChunk(w: MeshWriter, index: Long, seed: Long, rng: Rng) {
        w.reset()
        rng.reset(Rng.mix(seed xor (index * 0x5DEECE66DL) xor 0x4D455341L))

        // Sand in light bands and dark ripples either side of the trail. The ripples are marked as
        // grout, so they fade into the sand with distance instead of shimmering.
        val periods = (CHUNK_LENGTH / RIPPLE_PERIOD).toInt()
        for (p in 0 until periods) {
            val z0 = -p * RIPPLE_PERIOD
            val zr = z0 - (RIPPLE_PERIOD - RIPPLE_WIDTH)
            val z1 = z0 - RIPPLE_PERIOD
            val g = index * periods + p
            val j = (Rng.hash01(g, 3, seed) - 0.5f) * 0.03f
            val sand = MeshWriter.pack(SAND[0] + j, SAND[1] + j, SAND[2] + j * 0.6f)
            val k = (Rng.hash01(g, 4, seed) - 0.5f) * 0.06f
            val ripple = MeshWriter.pack(RIPPLE[0] + k, RIPPLE[1] + k, RIPPLE[2] + k * 0.6f, MeshWriter.GROUT)
            w.floor(-HALF_WIDTH, -TRAIL_HALF, z0, zr, 0f, sand)
            w.floor(-HALF_WIDTH, -TRAIL_HALF, zr, z1, 0f, ripple)
            w.floor(TRAIL_HALF, HALF_WIDTH, z0, zr, 0f, sand)
            w.floor(TRAIL_HALF, HALF_WIDTH, zr, z1, 0f, ripple)
            val t = (Rng.hash01(g, 5, seed) - 0.5f) * 0.04f
            w.floor(-TRAIL_HALF, TRAIL_HALF, z0, z1, 0f, MeshWriter.pack(TRAIL[0] + t, TRAIL[1] + t, TRAIL[2] + t))
        }

        // Low pale cones mark the trail's edges.
        val markers = (CHUNK_LENGTH / MARKER_SPACING).toInt()
        for (m in 0 until markers) {
            val z = -m * MARKER_SPACING - MARKER_SPACING * 0.5f
            w.prism(-MARKER_X, 0f, z, 0.2f, 0f, 0.7f, 4, MARKER)
            w.prism(MARKER_X, 0f, z, 0.2f, 0f, 0.7f, 4, MARKER)
        }

        val spires = 4 + rng.nextInt(4)
        for (s in 0 until spires) spire(w, rng)
        val plants = 3 + rng.nextInt(3)
        for (s in 0 until plants) bulbPlant(w, rng)
        val floaters = 2 + rng.nextInt(3)
        for (s in 0 until floaters) floater(w, rng)
    }

    /** A rock tower: wide at the foot, narrower at its flat top. More of them stand near the trail than far from it. */
    private fun spire(w: MeshWriter, rng: Rng) {
        val side = if (rng.chance(0.5f)) 1f else -1f
        val a = rng.nextFloat()
        val away = 4f + a * a * 220f
        val foot = rng.range(1.5f, 4.5f) + away * 0.012f
        val height = rng.range(5f, 30f) + away * 0.05f
        val top = foot * rng.range(0.3f, 0.65f)
        val sides = 5 + rng.nextInt(3)
        val c = SPIRES[rng.nextInt(SPIRES.size)]
        val v = rng.range(-0.04f, 0.04f)
        w.prism(
            side * (CLEAR_HALF + away + foot), -0.2f, -rng.range(2f, CHUNK_LENGTH - 2f),
            foot, top, height, sides,
            MeshWriter.pack(c[0] + v, c[1] + v, c[2] + v, MeshWriter.OBJECT), rng.range(0f, 1f),
        )
    }

    /** A thin stem carrying a teal bulb. */
    private fun bulbPlant(w: MeshWriter, rng: Rng) {
        val side = if (rng.chance(0.5f)) 1f else -1f
        val r = rng.range(0.6f, 1.3f)
        val x = side * (CLEAR_HALF + r + rng.range(0.5f, 65f))
        val z = -rng.range(1f, CHUNK_LENGTH - 1f)
        val stem = rng.range(1.8f, 4.2f)
        val c = BULBS[rng.nextInt(BULBS.size)]
        w.prism(x, 0f, z, 0.22f, 0.14f, stem, 5, STEM)
        w.icosa(x, stem + r * 0.7f, z, r, r * 1.15f, r, MeshWriter.pack(c[0], c[1], c[2], MeshWriter.OBJECT))
    }

    /** A rock hanging in the air: a flattened top with a narrower root under it. */
    private fun floater(w: MeshWriter, rng: Rng) {
        val side = if (rng.chance(0.5f)) 1f else -1f
        val rx = rng.range(2f, 7f)
        val ry = rng.range(0.8f, 2.4f)
        val rz = rng.range(2f, 7f)
        val x = side * (CLEAR_HALF + rx + rng.range(4f, 105f))
        val y = rng.range(9f, 48f)
        val z = -rng.range(rz, CHUNK_LENGTH - rz)
        val v = rng.range(-0.05f, 0.05f)
        w.icosa(x, y, z, rx, ry, rz, MeshWriter.pack(FLOATER_TOP[0] + v, FLOATER_TOP[1] + v, FLOATER_TOP[2] + v, MeshWriter.OBJECT))
        w.icosa(x, y - ry * 0.9f, z, rx * 0.55f, ry * 1.6f, rz * 0.55f, FLOATER_UNDER)
    }

    /**
     * What lies beyond the chunks, drawn first and without depth: sand to the horizon, the trail
     * carrying on across it, and a ring of mesas, farthest first. It does not scroll; at that
     * distance nothing would be seen to move.
     */
    fun buildFar(w: MeshWriter, seed: Long, rng: Rng) {
        w.reset()
        w.floor(-9000f, 9000f, 60f, -9000f, -0.06f, MeshWriter.pack(SAND[0], SAND[1], SAND[2]))
        w.floor(-TRAIL_HALF, TRAIL_HALF, -400f, -7000f, -0.05f, MeshWriter.pack(TRAIL[0], TRAIL[1], TRAIL[2]))

        rng.reset(Rng.mix(seed xor 0x4D45534153L))
        val xs = FloatArray(MESAS)
        val zs = FloatArray(MESAS)
        val rs = FloatArray(MESAS)
        val hs = FloatArray(MESAS)
        for (i in 0 until MESAS) {
            val side = if (i % 2 == 0) 1f else -1f
            xs[i] = side * rng.range(MESA_CLEAR_HALF, 5200f)
            zs[i] = -rng.range(2600f, 6200f)
            rs[i] = rng.range(180f, 520f)
            hs[i] = rng.range(120f, 420f)
        }
        // Painter's order: farthest first.
        val order = (0 until MESAS).sortedBy { zs[it] }
        for (i in order) {
            val c = MESA[i % MESA.size]
            val v = rng.range(-0.03f, 0.03f)
            w.prism(xs[i], -1f, zs[i], rs[i], rs[i] * rng.range(0.6f, 0.85f), hs[i], 6, MeshWriter.pack(c[0] + v, c[1] + v, c[2] + v), i * 0.4f)
        }
    }

    /** Mesas keep this far from the line of the trail, plus their own radius, so the way ahead stays open. */
    const val MESA_CLEAR_HALF = 900f

    // ---- traffic ----

    const val TRAFFIC_MAX_VERTICES = SkyliftMotion.MAX_TRAFFIC * 96
    private val DRIFTER_CORE = MeshWriter.pack(0.62f, 0.96f, 1.00f, MeshWriter.OBJECT)
    private val DRIFTER_SHARD = MeshWriter.pack(1.00f, 0.58f, 0.16f, MeshWriter.OBJECT)
    private val ROLLER_ROCK = MeshWriter.pack(0.27f, 0.23f, 0.26f, MeshWriter.OBJECT)
    private const val THIRD_TURN = (2 * PI / 3).toFloat()

    /**
     * Everything in [motion] that is alive, in the rider's space (see [SkyliftMotion]). A drifter
     * is a spinning pale crystal with three orange shards circling it; a roller is a dark boulder
     * bouncing along the ground.
     */
    fun buildTraffic(w: MeshWriter, motion: SkyliftMotion) {
        w.reset()
        for (i in 0 until SkyliftMotion.MAX_TRAFFIC) {
            if (!motion.alive[i]) continue
            val x = motion.x[i]
            val z = motion.z[i]
            val ph = motion.phase[i]
            if (motion.kind[i] == SkyliftMotion.ROLLER) {
                val r = SkyliftMotion.ROLLER_RADIUS
                w.icosa(x, r + abs(sin(ph)) * 0.6f, z, r, r, r, ROLLER_ROCK)
            } else {
                val y = motion.y[i] + sin(ph * 0.7f) * 0.25f
                octahedron(w, x, y, z, 0.7f, 1.0f, ph, DRIFTER_CORE)
                for (k in 0 until 3) {
                    val a = ph * 1.7f + k * THIRD_TURN
                    octahedron(w, x + cos(a) * 1.25f, y + sin(a * 0.5f) * 0.3f, z - sin(a) * 1.25f, 0.18f, 0.32f, -ph, DRIFTER_SHARD)
                }
            }
        }
    }

    /** Two four-sided pyramids base to base, [r] wide and [h] tall each way, turned by [turn] radians. */
    private fun octahedron(w: MeshWriter, x: Float, y: Float, z: Float, r: Float, h: Float, turn: Float, color: Float) {
        val quarter = (PI / 2).toFloat()
        for (i in 0 until 4) {
            val a0 = turn + i * quarter
            val a1 = a0 + quarter
            val x0 = x + r * cos(a0); val z0 = z - r * sin(a0)
            val x1 = x + r * cos(a1); val z1 = z - r * sin(a1)
            w.tri(x0, y, z0, x1, y, z1, x, y + h, z, color)
            w.tri(x1, y, z1, x0, y, z0, x, y - h, z, color)
        }
    }
}
