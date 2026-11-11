package dev.soloton.roadwork.env.riverfront

import dev.soloton.roadwork.MeshWriter
import dev.soloton.roadwork.Rng
import kotlin.math.PI
import kotlin.math.sin

/**
 * The invented city on the far bank, plus a distant treeline on the park side, built once from
 * the seed. Everything lies in a band of local z from -[PERIOD] to 0 and repeats seamlessly every
 * [PERIOD] metres: the game draws copies one period apart and slides them by `distance mod PERIOD`,
 * so the far bank scrolls past slowly with true parallax.
 *
 * The period is cut into [SECTIONS] sections of [SECTION_LENGTH] (an element belongs to the
 * section holding its near end), each built twice: detailed, with window-grid striping on the
 * facades that face the path, and plain (same silhouettes), so detail is spent only on the
 * stretch of bank that is close enough to show it. Everything is drawn with depth testing from a
 * camera whose near plane is 100 m, so it stays at least that far from the path.
 */
object Skyline {
    const val PERIOD = 3000f
    /** x of the far bank's quay wall. */
    const val FAR_BANK_X = 430f
    const val QUAY_Y = 2f
    const val SECTIONS = 6
    const val SECTION_LENGTH = PERIOD / SECTIONS
    /** How far an element can reach past the far end of its section (the widest building). */
    const val SECTION_OVERHANG = 70f
    const val MAX_VERTICES = 90000

    /** Section being built, or -1 for all of them. */
    private var section = -1

    private const val TWO_PI = (2 * PI).toFloat()

    // Building palettes (wall, window): whites, light greys, mid greys, charcoals.
    private val WALLS = arrayOf(
        floatArrayOf(0.92f, 0.92f, 0.90f), floatArrayOf(0.80f, 0.81f, 0.82f), floatArrayOf(0.66f, 0.68f, 0.70f),
        floatArrayOf(0.50f, 0.52f, 0.55f), floatArrayOf(0.30f, 0.32f, 0.35f), floatArrayOf(0.86f, 0.84f, 0.79f),
    )
    private val WINDOWS = arrayOf(
        floatArrayOf(0.42f, 0.50f, 0.58f), floatArrayOf(0.36f, 0.43f, 0.52f), floatArrayOf(0.30f, 0.36f, 0.44f),
        floatArrayOf(0.24f, 0.29f, 0.36f), floatArrayOf(0.55f, 0.62f, 0.70f), floatArrayOf(0.40f, 0.44f, 0.48f),
    )

    /** Builds section [s] (0 = nearest end of the period) with window striping, or the whole period when s = -1. */
    fun buildDetailed(w: MeshWriter, seed: Long, rng: Rng, s: Int = -1) = build(w, seed, rng, detailed = true, s)

    /** Builds section [s] with plain facades, or the whole period when s = -1. */
    fun buildSimple(w: MeshWriter, seed: Long, rng: Rng, s: Int = -1) = build(w, seed, rng, detailed = false, s)

    /** Which section an element whose near end is at local z belongs to. */
    fun sectionOf(z: Float): Int = (-z / SECTION_LENGTH).toInt().coerceIn(0, SECTIONS - 1)

    /** Keeps what was written since [mark] only if [z] is in the section being built. */
    private fun keep(w: MeshWriter, mark: Int, z: Float) {
        if (section >= 0 && sectionOf(z) != section) w.rewind(mark)
    }

    /** District envelope along the bank (0..1), periodic in [PERIOD]: tall clusters and low gaps. */
    fun envelope(z: Float, seed: Long): Float {
        val u = -z / PERIOD
        val p1 = Rng.hash01(seed, 1, 3) * TWO_PI
        val p2 = Rng.hash01(seed, 2, 3) * TWO_PI
        val p3 = Rng.hash01(seed, 3, 3) * TWO_PI
        val e = 0.55f + 0.28f * sin(TWO_PI * 2f * u + p1) + 0.2f * sin(TWO_PI * 5f * u + p2) + 0.1f * sin(TWO_PI * 11f * u + p3)
        return e.coerceIn(0f, 1f)
    }

    private fun build(w: MeshWriter, seed: Long, rng: Rng, detailed: Boolean, s: Int) {
        w.reset()
        section = s
        quay(w)
        rng.reset(seed * 31 + 17)
        waterfront(w, rng, detailed, seed)
        // Tower rows, front to back: x range, height range.
        towers(w, rng, detailed, seed, 470f, 560f, 45f, 150f, 0.0f, 8)
        towers(w, rng, detailed, seed, 590f, 720f, 70f, 250f, 0.25f, 8)
        towers(w, rng, detailed, seed, 760f, 950f, 55f, 190f, 0.5f, 5)
        treeline(w, rng)
    }

    /** The far bank: a quay wall facing the path and the ground behind it, split for fog. */
    private fun quay(w: MeshWriter) {
        val wall = MeshWriter.pack(0.55f, 0.53f, 0.50f)
        val ground = MeshWriter.pack(0.50f, 0.50f, 0.47f)
        val n = 12
        for (i in 0 until n) {
            val z0 = -PERIOD * i / n
            val z1 = -PERIOD * (i + 1) / n
            val mark = w.vertexCount
            // Wall face toward -x (the camera side).
            w.quad(FAR_BANK_X, Scenery.WATER_Y - 0.5f, z1, FAR_BANK_X, Scenery.WATER_Y - 0.5f, z0,
                FAR_BANK_X, QUAY_Y, z0, FAR_BANK_X, QUAY_Y, z1, wall)
            w.floor(FAR_BANK_X, 1300f, z0, z1, QUAY_Y, ground)
            keep(w, mark, z0 - 1f)
        }
    }

    /** Low buildings along the far quay, with gaps that show the water and the sky. */
    private fun waterfront(w: MeshWriter, rng: Rng, detailed: Boolean, seed: Long) {
        var z = -rng.range(0f, 20f)
        while (true) {
            val width = rng.range(16f, 48f)
            if (z - width < -PERIOD + 4f) break
            if (rng.chance(0.16f)) {
                z -= rng.range(25f, 80f)
                continue
            }
            val mark = w.vertexCount
            val x0 = FAR_BANK_X + rng.range(6f, 14f)
            val depth = rng.range(14f, 28f)
            val h = rng.range(7f, 22f) * (0.7f + 0.5f * envelope(z, seed))
            val pal = rng.nextInt(WALLS.size)
            val bands = if (detailed) (h / 4.5f).toInt().coerceIn(1, 5) else 0
            building(w, x0, x0 + depth, z - width, z, QUAY_Y, QUAY_Y + h, pal, bands, rng.chance(0.5f), rng)
            if (rng.chance(0.3f)) {
                // Pitched or stepped roofline.
                val c = shade(WALLS[pal], -0.12f)
                w.box(x0 + depth * 0.25f, QUAY_Y + h, z - width * 0.7f, x0 + depth * 0.75f, QUAY_Y + h + 3f, z - width * 0.3f, c)
            }
            keep(w, mark, z)
            z -= width + rng.range(2f, 9f)
        }
    }

    private fun towers(
        w: MeshWriter, rng: Rng, detailed: Boolean, seed: Long,
        xMin: Float, xMax: Float, hMin: Float, hMax: Float, envShift: Float, maxBands: Int,
    ) {
        var z = -rng.range(0f, 40f)
        while (true) {
            val width = rng.range(24f, 62f)
            if (z - width < -PERIOD + 4f) break
            val env = envelope(z - width * 0.5f - envShift * 300f, seed)
            if (env < 0.22f || rng.chance(0.08f)) {
                // A gap in the skyline.
                z -= rng.range(20f, 70f)
                continue
            }
            val mark = w.vertexCount
            val depth = rng.range(24f, 55f)
            val x0 = rng.range(xMin, xMax - depth)
            var h = hMin + (hMax - hMin) * env * rng.range(0.45f, 1f)
            if (rng.chance(0.07f)) h += rng.range(60f, 110f)
            val pal = rng.nextInt(WALLS.size)
            val floors = (h / 16f).toInt()
            val bands = if (detailed) floors.coerceIn(3, maxBands) else 0
            val vertical = rng.chance(0.3f)
            val y0 = QUAY_Y
            val kind = rng.nextInt(7)
            val c = shade(WALLS[pal], -0.06f)
            when (kind) {
                1 -> {
                    // Setback: a slimmer upper block.
                    val h1 = h * rng.range(0.55f, 0.7f)
                    building(w, x0, x0 + depth, z - width, z, y0, y0 + h1, pal, (bands * 0.65f).toInt(), vertical, rng)
                    val inset = rng.range(0.15f, 0.25f)
                    building(w, x0 + depth * inset, x0 + depth * (1f - inset), z - width * (1f - inset), z - width * inset,
                        y0 + h1, y0 + h, pal, (bands * 0.35f).toInt(), vertical, rng)
                }
                2 -> {
                    // Spire on a crown.
                    building(w, x0, x0 + depth, z - width, z, y0, y0 + h, pal, bands, vertical, rng)
                    val cx = x0 + depth * 0.5f; val cz = z - width * 0.5f
                    w.prism(cx, y0 + h, cz, minOf(depth, width) * 0.3f, minOf(depth, width) * 0.22f, 6f, 6, c)
                    w.prism(cx, y0 + h + 6f, cz, minOf(depth, width) * 0.18f, 0f, h * 0.35f, 6, c)
                }
                3 -> {
                    // Antenna mast.
                    building(w, x0, x0 + depth, z - width, z, y0, y0 + h, pal, bands, vertical, rng)
                    val cx = x0 + depth * 0.5f; val cz = z - width * 0.5f
                    w.box(cx - 4f, y0 + h, cz - 4f, cx + 4f, y0 + h + 5f, cz + 4f, c)
                    w.beam(cx, y0 + h + 5f, cz, cx, y0 + h + 5f + rng.range(25f, 55f), cz, 1.4f, 1.4f, MeshWriter.pack(0.35f, 0.36f, 0.38f))
                }
                4 -> {
                    // Round tower with a shallow cap.
                    val r = minOf(depth, width) * 0.5f
                    val cx = x0 + depth * 0.5f; val cz = z - width * 0.5f
                    roundTower(w, cx, cz, r, y0, y0 + h, pal, if (detailed) bands else 0)
                    w.prism(cx, y0 + h, cz, r, r * 0.4f, r * 0.35f, 8, c)
                }
                5 -> {
                    // Slanted crown.
                    building(w, x0, x0 + depth, z - width, z, y0, y0 + h, pal, bands, vertical, rng)
                    w.prism(x0 + depth * 0.5f, y0 + h, z - width * 0.5f, minOf(depth, width) * 0.7f, minOf(depth, width) * 0.25f,
                        minOf(depth, width) * 0.5f, 4, c, (PI / 4).toFloat())
                }
                else -> {
                    building(w, x0, x0 + depth, z - width, z, y0, y0 + h, pal, bands, vertical, rng)
                    // Rooftop plant room.
                    w.box(x0 + depth * 0.3f, y0 + h, z - width * 0.65f, x0 + depth * 0.7f, y0 + h + 4f, z - width * 0.35f, c)
                }
            }
            keep(w, mark, z)
            z -= width + rng.range(4f, 22f)
        }
    }

    private fun shade(c: FloatArray, d: Float): Float = MeshWriter.pack(c[0] + d, c[1] + d, c[2] + d)

    /**
     * A block whose two faces seen from the path (-x and +z) carry [bands] storeys of window
     * striping: alternating wall and window bands, horizontal or (when [vertical]) as columns.
     * With no bands the faces are plain in a colour between wall and window.
     */
    private fun building(
        w: MeshWriter, x0: Float, x1: Float, z0: Float, z1: Float, y0: Float, y1: Float,
        pal: Int, bands: Int, vertical: Boolean, rng: Rng,
    ) {
        val wall = WALLS[pal]
        val win = WINDOWS[pal]
        val s = rng.range(-0.03f, 0.03f)
        val wallC = MeshWriter.pack(wall[0] + s, wall[1] + s, wall[2] + s)
        val roof = MeshWriter.pack(wall[0] * 0.8f + s, wall[1] * 0.8f + s, wall[2] * 0.8f + s)
        // Top.
        w.quad(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, roof)
        if (bands <= 0) {
            val m = MeshWriter.pack((wall[0] * 0.6f + win[0] * 0.4f) + s, (wall[1] * 0.6f + win[1] * 0.4f) + s, (wall[2] * 0.6f + win[2] * 0.4f) + s)
            w.quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, m) // -x face
            w.quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, m) // +z face
            return
        }
        val winC = MeshWriter.pack(win[0] + s * 0.5f, win[1] + s * 0.5f, win[2] + s * 0.5f)
        if (!vertical) {
            // Horizontal: base wall strip, then (window, wall) per storey band, wall at the top.
            val bandH = (y1 - y0) / (bands + 0.4f)
            val solid = bandH * 0.38f
            var y = y0 + bandH * 0.2f
            hBand(w, x0, x1, z0, z1, y0, y, wallC)
            for (b in 0 until bands) {
                hBand(w, x0, x1, z0, z1, y, y + bandH - solid, winC)
                hBand(w, x0, x1, z0, z1, y + bandH - solid, y + bandH, wallC)
                y += bandH
            }
            hBand(w, x0, x1, z0, z1, y, y1, wallC)
        } else {
            // Vertical: columns of glass between fins, on each face.
            val cols = (bands / 2).coerceIn(3, 7)
            val dz = (z1 - z0) / cols
            for (c in 0 until cols) {
                val za = z0 + c * dz
                val zm = za + dz * 0.3f
                val zb = za + dz
                w.quad(x0, y0, za, x0, y0, zm, x0, y1, zm, x0, y1, za, wallC)
                w.quad(x0, y0, zm, x0, y0, zb, x0, y1, zb, x0, y1, zm, winC)
            }
            val dx = (x1 - x0) / cols
            for (c in 0 until cols) {
                val xa = x0 + c * dx
                val xm = xa + dx * 0.3f
                val xb = xa + dx
                w.quad(xa, y0, z1, xm, y0, z1, xm, y1, z1, xa, y1, z1, wallC)
                w.quad(xm, y0, z1, xb, y0, z1, xb, y1, z1, xm, y1, z1, winC)
            }
        }
    }

    /** One horizontal band on the -x and +z faces. */
    private fun hBand(w: MeshWriter, x0: Float, x1: Float, z0: Float, z1: Float, ya: Float, yb: Float, c: Float) {
        w.quad(x0, ya, z0, x0, ya, z1, x0, yb, z1, x0, yb, z0, c)
        w.quad(x0, ya, z1, x1, ya, z1, x1, yb, z1, x0, yb, z1, c)
    }

    /**
     * A round tower as a half cylinder turned toward the path (the far side is never seen), with
     * [bands] storeys of glass and spandrel, and a flat top.
     */
    private fun roundTower(w: MeshWriter, cx: Float, cz: Float, r: Float, y0: Float, y1: Float, pal: Int, bands: Int) {
        val wall = WALLS[pal]
        val win = WINDOWS[pal]
        val plain = MeshWriter.pack(wall[0] * 0.6f + win[0] * 0.4f, wall[1] * 0.6f + win[1] * 0.4f, wall[2] * 0.6f + win[2] * 0.4f)
        val wallC = MeshWriter.pack(wall[0], wall[1], wall[2])
        val winC = MeshWriter.pack(win[0], win[1], win[2])
        val n = bands.coerceAtLeast(0)
        val bandH = if (n > 0) (y1 - y0) / n else (y1 - y0)
        val sides = 6
        // From facing +z round through -x to facing -z: angles 0..180 degrees measured from +z toward -x.
        for (i in 0 until sides) {
            val a0 = (PI * i / sides).toFloat()
            val a1 = (PI * (i + 1) / sides).toFloat()
            val x0 = cx - r * sin(a0); val z0 = cz + r * kotlin.math.cos(a0)
            val x1 = cx - r * sin(a1); val z1 = cz + r * kotlin.math.cos(a1)
            if (n == 0) {
                w.quad(x1, y0, z1, x0, y0, z0, x0, y1, z0, x1, y1, z1, plain)
            } else {
                var y = y0
                for (b in 0 until n) {
                    val ym = y + bandH * 0.62f
                    w.quad(x1, y, z1, x0, y, z0, x0, ym, z0, x1, ym, z1, winC)
                    w.quad(x1, ym, z1, x0, ym, z0, x0, y + bandH, z0, x1, y + bandH, z1, wallC)
                    y += bandH
                }
            }
            w.tri(cx, y1, cz, x1, y1, z1, x0, y1, z0, wallC)
        }
    }

    /**
     * A distant belt of trees on the park side: two rows of rounded crowns drawn as a cheap
     * silhouette band (a fan of triangles per crown on one face leaning back from the path).
     */
    private fun treeline(w: MeshWriter, rng: Rng) {
        for (row in 0..1) {
            val x = if (row == 0) -160f else -240f
            val lean = 0.35f
            var z = -rng.range(0f, 10f)
            while (true) {
                val width = rng.range(14f, 30f)
                if (z - width < -PERIOD + 2f) break
                val mark = w.vertexCount
                val h = rng.range(9f, 17f) * (if (row == 0) 1f else 1.25f)
                val g = rng.range(-0.04f, 0.04f) - row * 0.03f
                val c = MeshWriter.pack(0.20f + g, 0.40f + g, 0.15f + g)
                // Crown outline: a half-ellipse over a trunk-height base, as a triangle fan.
                val base = h * 0.25f
                val zc = z - width * 0.5f
                val n = 4
                for (i in 0 until n) {
                    val a0 = (PI * i / n).toFloat()
                    val a1 = (PI * (i + 1) / n).toFloat()
                    val za = zc + width * 0.5f * kotlin.math.cos(a0)
                    val ya = base + (h - base) * sin(a0)
                    val zb = zc + width * 0.5f * kotlin.math.cos(a1)
                    val yb = base + (h - base) * sin(a1)
                    // Wound to face +x, toward the path.
                    w.tri(x - lean * base, base, zc, x - lean * yb, yb, zb, x - lean * ya, ya, za, c)
                }
                w.quad(x, -0.5f, z, x, -0.5f, z - width, x - lean * base, base, z - width, x - lean * base, base, z, c)
                keep(w, mark, z)
                z -= width * rng.range(0.55f, 0.9f)
            }
        }
    }

    /** A small cargo boat with stacked containers, bow toward -z, origin at the waterline. */
    fun buildCargoBoat(w: MeshWriter) {
        w.reset()
        val y = Scenery.WATER_Y
        val hull = MeshWriter.pack(0.20f, 0.24f, 0.30f)
        val deck = MeshWriter.pack(0.55f, 0.20f, 0.15f)
        w.box(-5f, y - 0.5f, -26f, 5f, y + 3.2f, 26f, hull)
        w.prism(0f, y - 0.5f, -26f, 5f, 5f, 3.7f, 6, hull)
        w.box(-4.6f, y + 3.2f, -24f, 4.6f, y + 3.5f, 24f, deck)
        val boxes = arrayOf(
            MeshWriter.pack(0.20f, 0.45f, 0.55f), MeshWriter.pack(0.80f, 0.55f, 0.15f),
            MeshWriter.pack(0.35f, 0.55f, 0.30f), MeshWriter.pack(0.70f, 0.70f, 0.68f),
        )
        for (i in 0 until 6) {
            val z0 = -20f + i * 6.4f
            w.box(-4.2f, y + 3.5f, z0, 4.2f, y + 6.0f + (i % 2) * 2.5f, z0 + 6f, boxes[i % boxes.size])
        }
        // Wheelhouse at the stern.
        w.box(-4f, y + 3.5f, 18f, 4f, y + 11f, 24f, MeshWriter.pack(0.92f, 0.92f, 0.90f))
        w.box(-4.1f, y + 9f, 17.9f, 4.1f, y + 10.2f, 24.1f, MeshWriter.pack(0.25f, 0.30f, 0.36f))
    }

    /** A river ferry: white with a teal band, two decks, bow toward +z (it heads downstream). */
    fun buildFerry(w: MeshWriter) {
        w.reset()
        val y = Scenery.WATER_Y
        val white = MeshWriter.pack(0.94f, 0.94f, 0.92f)
        val band = MeshWriter.pack(0.10f, 0.50f, 0.55f)
        val glass = MeshWriter.pack(0.25f, 0.32f, 0.40f)
        w.box(-4f, y - 0.4f, -14f, 4f, y + 1.6f, 14f, white)
        w.box(-4.05f, y + 0.6f, -14.05f, 4.05f, y + 1.2f, 14.05f, band)
        w.box(-3.6f, y + 1.6f, -11f, 3.6f, y + 4.2f, 11f, white)
        w.box(-3.65f, y + 2.4f, -10.5f, 3.65f, y + 3.6f, 10.5f, glass)
        w.box(-2.6f, y + 4.2f, -2f, 2.6f, y + 6.4f, 8f, white)
        w.box(-2.65f, y + 5.2f, -1.5f, 2.65f, y + 6.0f, 7.5f, glass)
    }
}
