package dev.soloton.roadwork.env.pedalcraft

import dev.soloton.roadwork.MeshWriter
import dev.soloton.roadwork.Rng
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry for the island, built once when the scene is created: x east, y up, z south.
 *
 * The land is a grid of [CELL]-metre squares over [IslandTerrain], two flat-shaded triangles each,
 * coloured by height (sand, grass, forest, dry upland, rock), cut into [TILES] x [TILES] tiles so
 * the scene can draw the nearby ones again with a tighter depth range. Sea bed shallower than
 * [SHALLOWS_DEPTH] is drawn flat at sea level in a paler blue, which gives the island its lagoon
 * rim; deeper than that there is only the open sea. Trees stand where [IslandTerrain.forest] says.
 */
object IslandMeshes {
    const val CELL = 50f
    const val TILES = 8
    const val TILE_CELLS = 14
    const val CELLS = TILES * TILE_CELLS
    const val HALF = CELLS * CELL / 2f

    /** Half the diagonal of a tile, for deciding which tiles are near. */
    val TILE_REACH = TILE_CELLS * CELL * sqrt(2f) / 2f

    const val SHALLOWS_DEPTH = 12f
    const val TREES_PER_CELL = 2

    /** Vertex budgets (checked by tests). */
    const val TILE_MAX_VERTICES = TILE_CELLS * TILE_CELLS * (6 + TREES_PER_CELL * 9)
    const val SHALLOWS_MAX_VERTICES = 24_000
    const val SEA_MAX_VERTICES = 1_400
    const val CLOUDS = 16
    const val CLOUDS_MAX_VERTICES = CLOUDS * 180
    const val MARKINGS_MAX_VERTICES = 600
    const val BUILDINGS_MAX_VERTICES = 2_600

    val SEA = floatArrayOf(0.05f, 0.30f, 0.52f)
    private val SHALLOW = floatArrayOf(0.22f, 0.70f, 0.72f)
    private val SAND = floatArrayOf(0.90f, 0.83f, 0.62f)
    private val GRASS = floatArrayOf(0.38f, 0.62f, 0.26f)
    private val FOREST = floatArrayOf(0.17f, 0.42f, 0.20f)
    private val UPLAND = floatArrayOf(0.62f, 0.60f, 0.38f)
    private val ROCK = floatArrayOf(0.56f, 0.52f, 0.47f)
    private val SUMMIT = floatArrayOf(0.70f, 0.67f, 0.62f)
    private val CLIFF = floatArrayOf(0.64f, 0.56f, 0.45f)
    private val FIELD = floatArrayOf(0.48f, 0.68f, 0.30f)
    private val TREE = arrayOf(floatArrayOf(0.12f, 0.36f, 0.17f), floatArrayOf(0.16f, 0.44f, 0.18f), floatArrayOf(0.20f, 0.40f, 0.14f))

    /** Heights at the grid's corners, row by row: `(CELLS + 1)` squared values. */
    fun heights(terrain: IslandTerrain): FloatArray {
        val n = CELLS + 1
        val out = FloatArray(n * n)
        for (j in 0 until n) for (i in 0 until n) out[j * n + i] = terrain.height(-HALF + i * CELL, -HALF + j * CELL)
        return out
    }

    /** The land of tile ([tx], [tz]) into [w] (cleared first), with its trees. */
    fun buildTile(w: MeshWriter, terrain: IslandTerrain, heights: FloatArray, tx: Int, tz: Int, seed: Long) {
        w.reset()
        val n = CELLS + 1
        for (cj in tz * TILE_CELLS until (tz + 1) * TILE_CELLS) {
            for (ci in tx * TILE_CELLS until (tx + 1) * TILE_CELLS) {
                val x0 = -HALF + ci * CELL
                val z0 = -HALF + cj * CELL
                val x1 = x0 + CELL
                val z1 = z0 + CELL
                val h00 = heights[cj * n + ci]
                val h10 = heights[cj * n + ci + 1]
                val h01 = heights[(cj + 1) * n + ci]
                val h11 = heights[(cj + 1) * n + ci + 1]
                val tint = (Rng.hash01(ci.toLong(), cj.toLong(), seed) - 0.5f) * 0.05f
                land(w, terrain, x0, h00, z0, x0, h01, z1, x1, h11, z1, tint)
                land(w, terrain, x0, h00, z0, x1, h11, z1, x1, h10, z0, tint - 0.012f)

                val cx = x0 + CELL / 2f
                val cz = z0 + CELL / 2f
                val low = minOf(minOf(h00, h10), minOf(h01, h11))
                val high = maxOf(maxOf(h00, h10), maxOf(h01, h11))
                if (low < 4f || high > 210f || high - low > 30f) continue
                if (terrain.forest(cx, cz) < IslandTerrain.FOREST_ABOVE || terrain.spotAt(cx, cz) != null) continue
                for (t in 0 until TREES_PER_CELL) {
                    val k = (ci * 131L + cj * 17L) * 4 + t
                    val x = x0 + CELL * (0.15f + 0.7f * Rng.hash01(k, 1, seed))
                    val z = z0 + CELL * (0.15f + 0.7f * Rng.hash01(k, 2, seed))
                    val size = 0.8f + 0.6f * Rng.hash01(k, 3, seed)
                    val c = TREE[(k % TREE.size).toInt()]
                    w.prism(x, terrain.height(x, z) - 0.5f, z, 5.5f * size, 0f, 15f * size, 3, MeshWriter.pack(c[0], c[1], c[2]), size * 3f)
                }
            }
        }
    }

    /** One land triangle, corners counter-clockwise seen from above. Skipped if it is all under water. */
    private fun land(
        w: MeshWriter, terrain: IslandTerrain,
        ax: Float, ah: Float, az: Float, bx: Float, bh: Float, bz: Float, cx: Float, ch: Float, cz: Float,
        tint: Float,
    ) {
        if (ah <= 0f && bh <= 0f && ch <= 0f) return
        val ay = maxOf(ah, 0f)
        val by = maxOf(bh, 0f)
        val cy = maxOf(ch, 0f)
        // How steep: the upward part of the triangle's unit normal.
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val up = ny / sqrt(nx * nx + ny * ny + nz * nz)
        val mx = (ax + bx + cx) / 3f
        val mz = (az + bz + cz) / 3f
        val h = (ah + bh + ch) / 3f
        val c = when {
            h < 3.5f -> SAND
            terrain.spotAt(mx, mz) != null && up > 0.985f -> FIELD
            up < 0.80f && h > 8f -> CLIFF
            h > 330f -> SUMMIT
            h > 230f -> ROCK
            terrain.forest(mx, mz) >= IslandTerrain.FOREST_ABOVE && h < 210f -> FOREST
            h > 70f -> UPLAND
            else -> GRASS
        }
        // Grass dries out with height, up to the upland colour.
        val dry = if (c === GRASS) ((h - 30f) / 40f).coerceIn(0f, 1f) * 0.6f else 0f
        w.tri(
            ax, ay, az, bx, by, bz, cx, cy, cz,
            MeshWriter.pack(
                c[0] + (UPLAND[0] - c[0]) * dry + tint, c[1] + (UPLAND[1] - c[1]) * dry + tint, c[2] + (UPLAND[2] - c[2]) * dry + tint * 0.6f,
            ),
        )
    }

    /** The shallows all round the island: flat at sea level, paler the shallower. Drawn as water. */
    fun buildShallows(w: MeshWriter, heights: FloatArray) {
        w.reset()
        val n = CELLS + 1
        for (cj in 0 until CELLS) {
            for (ci in 0 until CELLS) {
                val x0 = -HALF + ci * CELL
                val z0 = -HALF + cj * CELL
                val x1 = x0 + CELL
                val z1 = z0 + CELL
                val h00 = heights[cj * n + ci]
                val h10 = heights[cj * n + ci + 1]
                val h01 = heights[(cj + 1) * n + ci]
                val h11 = heights[(cj + 1) * n + ci + 1]
                shallow(w, x0, h00, z0, x0, h01, z1, x1, h11, z1)
                shallow(w, x0, h00, z0, x1, h11, z1, x1, h10, z0)
            }
        }
    }

    private fun shallow(w: MeshWriter, ax: Float, ah: Float, az: Float, bx: Float, bh: Float, bz: Float, cx: Float, ch: Float, cz: Float) {
        if (ah > 0f || bh > 0f || ch > 0f) return
        val depth = -(ah + bh + ch) / 3f
        if (depth >= SHALLOWS_DEPTH) return
        val t = depth / SHALLOWS_DEPTH
        w.tri(
            ax, SHALLOWS_Y, az, bx, SHALLOWS_Y, bz, cx, SHALLOWS_Y, cz,
            MeshWriter.pack(SHALLOW[0] + (SEA[0] - SHALLOW[0]) * t, SHALLOW[1] + (SEA[1] - SHALLOW[1]) * t, SHALLOW[2] + (SEA[2] - SHALLOW[2]) * t),
        )
    }

    private const val SHALLOWS_Y = 0f

    /**
     * The open sea as rings round the origin, finer near the middle, so the scene can centre it
     * under the camera and the per-vertex haze and sheen come out right from near to horizon.
     */
    fun buildSea(w: MeshWriter) {
        w.reset()
        val c = MeshWriter.pack(SEA[0], SEA[1], SEA[2])
        val step = (2 * PI / SEA_SEGMENTS).toFloat()
        for (ring in 0 until SEA_RINGS.size - 1) {
            val r0 = SEA_RINGS[ring]
            val r1 = SEA_RINGS[ring + 1]
            for (i in 0 until SEA_SEGMENTS) {
                val a0 = i * step
                val a1 = a0 + step
                val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
                if (r0 == 0f) {
                    w.tri(0f, SEA_Y, 0f, r1 * c1, SEA_Y, r1 * s1, r1 * c0, SEA_Y, r1 * s0, c)
                } else {
                    w.quad(r0 * c0, SEA_Y, r0 * s0, r0 * c1, SEA_Y, r0 * s1, r1 * c1, SEA_Y, r1 * s1, r1 * c0, SEA_Y, r1 * s0, c)
                }
            }
        }
    }

    private const val SEA_Y = -0.02f
    private const val SEA_SEGMENTS = 24
    private val SEA_RINGS = floatArrayOf(0f, 80f, 220f, 520f, 1100f, 2400f, 5200f, 12000f, 38000f)

    /** A scatter of flat-bottomed clouds round and over the island, above where the craft fly. */
    fun buildClouds(w: MeshWriter, seed: Long, rng: Rng) {
        w.reset()
        rng.reset(Rng.mix(seed xor 0xC10D5L))
        val white = MeshWriter.pack(0.97f, 0.97f, 0.98f)
        val shade = MeshWriter.pack(0.86f, 0.89f, 0.93f)
        for (i in 0 until CLOUDS) {
            val a = (2 * PI * i / CLOUDS).toFloat() + rng.range(-0.15f, 0.15f)
            val r = rng.range(1500f, 5200f)
            val x = r * cos(a)
            val z = r * sin(a)
            val y = rng.range(720f, 980f)
            val size = rng.range(0.7f, 1.4f)
            w.icosa(x, y, z, 190f * size, 48f * size, 120f * size, white)
            w.icosa(x + 120f * size, y - 8f * size, z + 40f * size, 120f * size, 36f * size, 90f * size, shade)
            w.icosa(x - 130f * size, y - 4f * size, z - 30f * size, 110f * size, 40f * size, 100f * size, white)
        }
    }

    // ---- what people have built ----

    private val TARMAC = MeshWriter.pack(0.30f, 0.31f, 0.33f)
    private val PAINT = MeshWriter.pack(0.93f, 0.93f, 0.90f)
    private val MAT = MeshWriter.pack(0.95f, 0.50f, 0.14f)
    private val MOWN = MeshWriter.pack(0.58f, 0.76f, 0.36f)
    private val WALL = MeshWriter.pack(0.90f, 0.87f, 0.80f)
    private val ROOF = MeshWriter.pack(0.72f, 0.30f, 0.22f)
    private val SHED = MeshWriter.pack(0.62f, 0.68f, 0.74f)
    private val SHED_ROOF = MeshWriter.pack(0.36f, 0.42f, 0.50f)
    private val POLE = MeshWriter.pack(0.86f, 0.86f, 0.84f)
    private val SOCK = MeshWriter.pack(0.98f, 0.45f, 0.10f)
    private val TIMBER = MeshWriter.pack(0.52f, 0.38f, 0.24f)
    private val LANTERN = MeshWriter.pack(0.20f, 0.22f, 0.26f)

    /** Puts [w]'s transform at a spot: origin on its ground, -z along its strip. */
    private fun at(w: MeshWriter, spot: Spot) {
        val yaw = Math.toDegrees(atan2(-spot.dirX, -spot.dirZ).toDouble()).toFloat()
        w.transform.idt().translate(spot.x, spot.height, spot.z).rotate(0f, 1f, 0f, yaw)
    }

    /**
     * Flat markings on the three spots, a hand's breadth above the ground (the scene draws them with
     * a depth offset): the airfield's tarmac runway with a dashed centre line, the hill's mown grass
     * strip with white corner boards, and an orange landing mat with a white border on the beach.
     */
    fun buildMarkings(w: MeshWriter, terrain: IslandTerrain) {
        w.reset()
        val y = 0.25f
        val a = terrain.airfield
        at(w, a)
        w.floor(-13f, 13f, a.run + 20f, -a.run - 20f, y, TARMAC)
        var z = a.run - 10f
        while (z > -a.run + 10f) {
            w.floor(-0.7f, 0.7f, z, z - 18f, y + 0.1f, PAINT)
            z -= 40f
        }
        for (end in -1..1 step 2) {
            for (bar in -3..3) w.floor(bar * 3.2f - 1f, bar * 3.2f + 1f, end * (a.run + 14f) + 5f, end * (a.run + 14f) - 5f, y + 0.1f, PAINT)
        }

        val h = terrain.hill
        at(w, h)
        w.floor(-11f, 11f, h.run, -h.run, y, MOWN)
        for (sx in -1..1 step 2) for (sz in -1..1 step 2) {
            w.floor(sx * 11f - 1.5f, sx * 11f + 1.5f, sz * h.run + 3f, sz * h.run - 3f, y + 0.1f, PAINT)
        }

        val b = terrain.beach
        at(w, b)
        w.floor(-20f, 20f, 20f, -20f, y, PAINT)
        w.floor(-16f, 16f, 16f, -16f, y + 0.1f, MAT)
        w.floor(-4f, 4f, 4f, -4f, y + 0.2f, PAINT)
        w.transform.idt()
    }

    /**
     * Solid things: two hangars and a hut beside the runway, a windsock at each spot, a jetty off
     * the beach, a lighthouse on the north-east shore and a handful of houses west of the airfield.
     */
    fun buildBuildings(w: MeshWriter, terrain: IslandTerrain, seed: Long) {
        w.reset()
        val a = terrain.airfield
        at(w, a)
        for (k in 0..1) {
            val z = -60f + k * 70f
            w.box(40f, 0f, z - 22f, 72f, 11f, z + 22f, SHED)
            w.box(38f, 11f, z - 24f, 74f, 13f, z + 24f, SHED_ROOF)
        }
        w.box(42f, 0f, 96f, 56f, 6f, 110f, WALL)
        w.box(41f, 6f, 95f, 57f, 7.2f, 111f, ROOF)
        windsock(w, -24f, -a.run * 0.6f)

        val h = terrain.hill
        at(w, h)
        windsock(w, -18f, h.run * 0.5f)
        w.box(16f, 0f, 60f, 26f, 4f, 72f, WALL)
        w.box(15f, 4f, 59f, 27f, 5f, 73f, ROOF)

        val b = terrain.beach
        at(w, b)
        windsock(w, 28f, -28f)

        // The jetty runs straight out to sea from the beach.
        w.transform.idt()
        val out = sqrt(b.x * b.x + b.z * b.z)
        val ox = b.x / out
        val oz = b.z / out
        w.beam(b.x + ox * 60f, 1.8f, b.z + oz * 60f, b.x + ox * 230f, 1.8f, b.z + oz * 230f, 5f, 0.5f, TIMBER)
        for (k in 0..4) {
            val d = 80f + k * 36f
            w.beam(b.x + ox * d, -4f, b.z + oz * d, b.x + ox * d, 1.8f, b.z + oz * d, 0.8f, 0.8f, TIMBER)
        }

        // The lighthouse, on the headland north-east of the peak.
        val la = -0.9f
        val lr = terrain.coastRadius(la) * 0.93f
        val lx = lr * cos(la)
        val lz = lr * sin(la)
        val ly = terrain.ground(lx, lz)
        w.prism(lx, ly - 1f, lz, 6f, 4f, 30f, 8, PAINT)
        w.prism(lx, ly + 11f, lz, 5.4f, 4.9f, 6f, 8, ROOF)
        w.prism(lx, ly + 29f, lz, 3f, 3f, 4f, 8, LANTERN)
        w.prism(lx, ly + 33f, lz, 3.6f, 0f, 3.5f, 8, ROOF)

        // Houses: wherever the ground west of the airfield is dry, gentle and clear of the strip.
        var built = 0
        var k = 0L
        while (built < 12 && k < 60) {
            val x = -520f + 420f * Rng.hash01(k, 11, seed)
            val z = 520f + 420f * Rng.hash01(k, 12, seed)
            k++
            val y = terrain.height(x, z)
            if (y < 4f || y > 60f || terrain.spotAt(x, z)?.let { it !== a } == true) continue
            if (kotlin.math.abs(z - a.z) < 60f) continue
            val turn = 360f * Rng.hash01(k, 13, seed)
            w.transform.idt().translate(x, y, z).rotate(0f, 1f, 0f, turn)
            w.box(-6f, -1f, -4.5f, 6f, 5f, 4.5f, WALL)
            w.box(-6.8f, 5f, -5.3f, 6.8f, 6.4f, 5.3f, ROOF)
            built++
        }
        w.transform.idt()
    }

    /** A pole with an orange sock, in the current transform. */
    private fun windsock(w: MeshWriter, x: Float, z: Float) {
        w.beam(x, 0f, z, x, 8f, z, 0.35f, 0.35f, POLE)
        w.beam(x, 7.7f, z, x + 3.4f, 7.2f, z + 0.6f, 0.9f, 0.9f, SOCK)
    }
}
