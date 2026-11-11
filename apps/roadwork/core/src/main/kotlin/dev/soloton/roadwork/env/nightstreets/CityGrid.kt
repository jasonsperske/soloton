package dev.soloton.roadwork.env.nightstreets

import dev.soloton.roadwork.Rng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The street grid, as pure maths: x east, z south. Streets run along every multiple of [PERIOD]
 * in both directions and cross at nodes, numbered (nodeX, nodeZ) with node (0, 0) at the origin.
 *
 * Everything that moves does so in **legs**. A leg starts half a block before a node, on the
 * street that enters it heading [inDir] (0 east, 1 south, 2 west, 3 north), passes through the
 * node going straight on, left or right, and ends half a block beyond it. Traffic keeps to the
 * right: a vehicle rides [offset] metres to the right of the street's centre line, cars in the
 * car lane ([CAR_LANE]) and the bike in the bike lane ([BIKE_LANE]) at the kerb.
 */
object CityGrid {
    const val PERIOD = 100f
    const val HALF = PERIOD / 2f

    /** Kerb to centre line. Each side has a car lane and, at the kerb, a bike lane. */
    const val HALF_STREET = 5f
    const val LANE_SPLIT = 3.4f
    const val CAR_LANE = 1.7f
    const val BIKE_LANE = 4.2f

    const val EAST = 0
    const val SOUTH = 1
    const val WEST = 2
    const val NORTH = 3
    val DX = intArrayOf(1, 0, -1, 0)
    val DZ = intArrayOf(0, 1, 0, -1)

    const val LEFT = -1
    const val STRAIGHT = 0
    const val RIGHT = 1

    /** The direction a leg leaves its node in. */
    fun outDir(inDir: Int, turn: Int): Int = (inDir + turn + 4) % 4

    private const val QUARTER = (PI / 2).toFloat()

    /** Length of a leg in metres for a lane [offset] from the centre line and the given corner radii. */
    fun legLength(turn: Int, offset: Float, rightRadius: Float, leftRadius: Float): Float = when (turn) {
        RIGHT -> 2f * (HALF - offset - rightRadius) + QUARTER * rightRadius
        LEFT -> 2f * (HALF + offset - leftRadius) + QUARTER * leftRadius
        else -> PERIOD
    }

    /**
     * Where a vehicle is [s] metres along a leg: writes x and z relative to the node's centre and
     * the heading (radians, from east toward south) into [out].
     */
    fun pose(inDir: Int, turn: Int, offset: Float, rightRadius: Float, leftRadius: Float, s: Float, out: FloatArray) {
        // Worked out for a leg entering heading east, then turned to the real direction.
        var x: Float
        var z: Float
        var heading: Float
        when (turn) {
            RIGHT -> {
                val r = rightRadius
                val straight = HALF - offset - r
                val arc = QUARTER * r
                when {
                    s < straight -> { x = -HALF + s; z = offset; heading = 0f }
                    s < straight + arc -> {
                        val a = (s - straight) / r
                        x = -offset - r + r * sin(a); z = offset + r - r * cos(a); heading = a
                    }
                    else -> { x = -offset; z = offset + r + (s - straight - arc); heading = QUARTER }
                }
            }
            LEFT -> {
                val r = leftRadius
                val straight = HALF + offset - r
                val arc = QUARTER * r
                when {
                    s < straight -> { x = -HALF + s; z = offset; heading = 0f }
                    s < straight + arc -> {
                        val a = (s - straight) / r
                        x = offset - r + r * sin(a); z = offset - r + r * cos(a); heading = -a
                    }
                    else -> { x = offset; z = offset - r - (s - straight - arc); heading = -QUARTER }
                }
            }
            else -> { x = -HALF + s; z = offset; heading = 0f }
        }
        // A quarter turn clockwise (east to south) per step of inDir.
        repeat(inDir) {
            val t = x
            x = -z
            z = t
        }
        out[0] = x
        out[1] = z
        out[2] = heading + inDir * QUARTER
    }

    // ---- traffic lights ----

    /** How long each street has green, and the pause with every light red between the two. */
    const val GREEN_SECONDS = 10f
    const val CLEAR_SECONDS = 4f
    const val CYCLE_SECONDS = 2f * (GREEN_SECONDS + CLEAR_SECONDS)
    const val EAST_WEST = 0
    const val NORTH_SOUTH = 1
    const val ALL_RED = -1

    /** Which street has green at node (nodeX, nodeZ) at [time] seconds: [EAST_WEST], [NORTH_SOUTH] or [ALL_RED]. */
    fun greenAxis(nodeX: Int, nodeZ: Int, time: Float, seed: Long): Int {
        val start = Rng.hash01(nodeX.toLong(), nodeZ.toLong(), seed xor 0x516E41L) * CYCLE_SECONDS
        val phase = (time + start) % CYCLE_SECONDS
        return when {
            phase < GREEN_SECONDS -> EAST_WEST
            phase < GREEN_SECONDS + CLEAR_SECONDS -> ALL_RED
            phase < 2f * GREEN_SECONDS + CLEAR_SECONDS -> NORTH_SOUTH
            else -> ALL_RED
        }
    }

    /** Seconds of green left for [axis] at node (nodeX, nodeZ) at [time]; 0 if it does not have green. */
    fun greenRemaining(nodeX: Int, nodeZ: Int, time: Float, seed: Long, axis: Int): Float {
        val start = Rng.hash01(nodeX.toLong(), nodeZ.toLong(), seed xor 0x516E41L) * CYCLE_SECONDS
        val phase = (time + start) % CYCLE_SECONDS
        val since = if (axis == EAST_WEST) phase else phase - GREEN_SECONDS - CLEAR_SECONDS
        return if (since >= 0f && since < GREEN_SECONDS) GREEN_SECONDS - since else 0f
    }

    /** The street a vehicle heading [dir] is on. */
    fun axis(dir: Int): Int = if (dir % 2 == 0) EAST_WEST else NORTH_SOUTH

    /** Unit vector of a heading in radians: x east, z south. */
    fun headingX(heading: Float): Float = cos(heading)
    fun headingZ(heading: Float): Float = sin(heading)
}
