package dev.soloton.roadwork.env.pedalcraft

import dev.soloton.roadwork.Rng
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A place to take off from and land at: a flattened patch of the island with a strip or a mat.
 *
 * @param dirX,dirZ the direction the strip runs (normalised here)
 * @param height metres above the sea
 * @param radius the terrain is levelled to [height] out to [CORE] of this, then blends into the land around
 * @param run half the usable length along the strip, in metres
 */
class Spot(val name: String, val x: Float, val z: Float, dirX: Float, dirZ: Float, val height: Float, val radius: Float, val run: Float) {
    val dirX: Float
    val dirZ: Float

    init {
        val len = sqrt(dirX * dirX + dirZ * dirZ)
        this.dirX = dirX / len
        this.dirZ = dirZ / len
        require(run <= radius * CORE) { "$name: the strip must fit on the levelled ground" }
    }

    companion object {
        /** The share of a spot's radius that is dead level. */
        const val CORE = 0.55f
    }
}

/**
 * The island, as a height in metres for any point: x east, z south, the sea at 0. Pure maths, so
 * routes and tests can ask it without OpenGL.
 *
 * The coast is a wobbly circle about [COAST] metres from the middle, with bays and headlands. Low
 * land rises from the shore to rolling ground, a peak stands north-east of the middle and a lower
 * ridge to the south-west. Three [Spot]s are levelled into it: a strip on the peak's shoulder, a
 * beach on the west shore and an airfield on the southern plain. The shape is fixed; the seed only
 * varies the small bumps.
 */
class IslandTerrain(private val seed: Long) {
    val hill = Spot("hill strip", 650f, -700f, 0.68f, -0.73f, 230f, 300f, 110f)
    val beach = Spot("beach", -1500f, 150f, 0.1f, 1f, 2.5f, 160f, 60f)
    val airfield = Spot("airfield", 100f, 1050f, 1f, 0f, 8f, 560f, 240f)
    val spots: List<Spot> = listOf(hill, beach, airfield)

    /** Distance of the shore from the middle in the direction [angle] (radians, from east toward south). */
    fun coastRadius(angle: Float): Float =
        COAST * (1f + 0.16f * sin(3f * angle + 0.6f) + 0.09f * sin(5f * angle + 2.1f) + 0.05f * sin(8f * angle + 4.0f))

    /** Height of the land or the sea bed in metres; below 0 is under water. */
    fun height(x: Float, z: Float): Float {
        val r = sqrt(x * x + z * z)
        // 1 in the middle of the island, 0 at the shore, negative out at sea.
        val inland = 1f - r / coastRadius(atan2(z, x))
        var h: Float
        if (inland <= 0f) {
            h = inland * SEABED_FALL
        } else {
            val s = smooth(inland / 0.25f)
            h = 2f + 38f * s
            h += PEAK_HEIGHT * bump(x - PEAK_X, z - PEAK_Z, 520f)
            h += 140f * bump(x + 500f, z - 300f, 400f)
            val n = 0.65f * valueNoise(x / 260f, z / 260f, 1L) + 0.35f * valueNoise(x / 90f, z / 90f, 2L)
            h += n * (10f + 50f * s) * s
            // Land stays land: never lower than a gentle rise from the shore.
            h = maxOf(h, 0.6f + 8f * inland)
        }
        for (spot in spots) {
            val dx = x - spot.x
            val dz = z - spot.z
            val d = sqrt(dx * dx + dz * dz)
            if (d >= spot.radius) continue
            val level = 1f - smooth((d / spot.radius - Spot.CORE) / (1f - Spot.CORE))
            h += (spot.height - h) * level
        }
        return h
    }

    /** The surface a craft could touch: the land, or the sea where there is none. */
    fun ground(x: Float, z: Float): Float = maxOf(height(x, z), 0f)

    /** Where trees grow: a slowly varying value, forest where it is above [FOREST_ABOVE]. */
    fun forest(x: Float, z: Float): Float = valueNoise(x / 190f, z / 190f, 7L)

    /** The spot whose levelled ground (x, z) lies on, or null. */
    fun spotAt(x: Float, z: Float): Spot? = spots.firstOrNull {
        val dx = x - it.x
        val dz = z - it.z
        dx * dx + dz * dz < it.radius * it.radius
    }

    private fun bump(dx: Float, dz: Float, width: Float): Float = exp(-(dx * dx + dz * dz) / (2f * width * width))

    /** Smooth noise in -1..1 with one feature per unit. */
    private fun valueNoise(x: Float, z: Float, salt: Long): Float {
        val fx = floor(x)
        val fz = floor(z)
        val ix = fx.toLong()
        val iz = fz.toLong()
        val tx = smooth(x - fx)
        val tz = smooth(z - fz)
        val a = lattice(ix, iz, salt)
        val b = lattice(ix + 1, iz, salt)
        val c = lattice(ix, iz + 1, salt)
        val d = lattice(ix + 1, iz + 1, salt)
        val top = a + (b - a) * tx
        val bottom = c + (d - c) * tx
        return top + (bottom - top) * tz
    }

    private fun lattice(ix: Long, iz: Long, salt: Long): Float = Rng.hash01(ix, iz, seed + salt * 7919L) * 2f - 1f

    companion object {
        /** Average distance of the shore from the middle of the island, in metres. */
        const val COAST = 1900f

        /** How fast the sea bed falls away past the shore, in metres per unit of [coastRadius]. */
        const val SEABED_FALL = 120f
        const val PEAK_X = 250f
        const val PEAK_Z = -250f
        const val PEAK_HEIGHT = 430f
        const val FOREST_ABOVE = 0.12f

        /** 0 at or below 0, 1 at or above 1, eased between. */
        fun smooth(t: Float): Float {
            val c = t.coerceIn(0f, 1f)
            return c * c * (3f - 2f * c)
        }
    }
}
