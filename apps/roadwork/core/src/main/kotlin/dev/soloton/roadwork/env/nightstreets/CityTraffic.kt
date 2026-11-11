package dev.soloton.roadwork.env.nightstreets

import dev.soloton.roadwork.Rng
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The cars, as pure maths. [MAX_CARS] of them drive the streets within [RANGE] blocks of the
 * rider; one that falls further behind is taken away and another started at the edge of that
 * range, so there is always traffic about however far the rider goes.
 *
 * A car follows legs like the rider ([CityGrid]), in the car lane, turning at random. It:
 * - cruises at [CRUISE] metres per second, speeding up and braking smoothly;
 * - stops at the line unless its street has green ([CityGrid.greenAxis]); between the two greens
 *   every light is red for a moment, so the junction empties;
 * - waits to turn left until nothing is coming the other way;
 * - keeps a gap behind whatever is ahead of it, cars and the rider alike;
 * - does not enter a junction while the rider is within [RIDER_PRIORITY] metres of its middle, so
 *   the rider, who cannot stop for a light, always has the junction to themself.
 *
 * Everything advances only in [step]; nothing allocates there.
 */
class CityTraffic(private val seed: Long) {
    val alive = BooleanArray(MAX_CARS)
    val nodeX = IntArray(MAX_CARS)
    val nodeZ = IntArray(MAX_CARS)
    val inDir = IntArray(MAX_CARS)
    val turn = IntArray(MAX_CARS)

    /** Metres along the current leg, and speed in metres per second. */
    val along = FloatArray(MAX_CARS)
    val speed = FloatArray(MAX_CARS)

    /** Position in metres and heading in radians from east toward south. */
    val x = FloatArray(MAX_CARS)
    val z = FloatArray(MAX_CARS)
    val heading = FloatArray(MAX_CARS)

    /** Which paint a car wears: an index the scene turns into a colour. */
    val paint = IntArray(MAX_CARS)

    var liveCount = 0
        private set

    /** Seconds since the scene started; the traffic lights run on it. */
    var time = 0f
        private set

    private val rng = Rng(Rng.mix(seed xor 0xCA25L))
    private val pose = FloatArray(3)

    /** Scratch for [step]: where the car being considered will be at each of the next [PATH_POINTS] steps along its path. */
    private val pathX = FloatArray(PATH_POINTS)
    private val pathZ = FloatArray(PATH_POINTS)

    /** Fills the streets round the rider, as at the start of a ride. */
    fun populate(rider: CityRider) {
        var tries = 0
        while (liveCount < MAX_CARS && tries++ < MAX_CARS * 20) spawn(rider, 1 + rng.nextInt(RANGE))
    }

    fun step(dt: Float, rider: CityRider) {
        time += dt

        // Take away what has fallen too far behind, and start one more at the edge if there is room.
        for (i in 0 until MAX_CARS) {
            if (alive[i] && maxOf(abs(nodeX[i] - rider.nodeX), abs(nodeZ[i] - rider.nodeZ)) > RANGE) {
                alive[i] = false
                liveCount--
            }
        }
        var tries = 0
        while (liveCount < MAX_CARS && tries++ < SPAWN_TRIES) spawn(rider, RANGE)

        for (i in 0 until MAX_CARS) {
            if (!alive[i]) continue
            var target = CRUISE

            // The stop line.
            if (along[i] < STOP_AT) {
                // The rider is near this junction, coming or going (it need not be the one their leg passes through).
                val rdx = rider.x - nodeX[i] * CityGrid.PERIOD
                val rdz = rider.z - nodeZ[i] * CityGrid.PERIOD
                val blockedByRider = rdx * rdx + rdz * rdz < RIDER_PRIORITY * RIDER_PRIORITY
                // Green, with enough of it left to get across before the other street goes.
                val green = CityGrid.greenRemaining(nodeX[i], nodeZ[i], time, seed, CityGrid.axis(inDir[i])) > ENTER_MARGIN_SECONDS
                if (!green || blockedByRider || (turn[i] == CityGrid.LEFT && oncoming(i))) {
                    target = minOf(target, stoppingSpeed(STOP_AT - along[i] - 0.3f))
                }
            }

            // Whatever is in this car's way: where it will be over the next stretch of its own path
            // (round a corner too), against where the other cars and the rider are now.
            val ox = nodeX[i] * CityGrid.PERIOD
            val oz = nodeZ[i] * CityGrid.PERIOD
            for (k in 0 until PATH_POINTS) {
                CityGrid.pose(inDir[i], turn[i], CityGrid.CAR_LANE, RIGHT_RADIUS, LEFT_RADIUS, along[i] + (k + 1) * PATH_STEP, pose)
                pathX[k] = ox + pose[0]
                pathZ[k] = oz + pose[1]
            }
            for (j in 0 until MAX_CARS) {
                if (j == i || !alive[j]) continue
                val gap = gapAlongPath(x[j], z[j], CAR_CLEARANCE, CAR_LENGTH)
                if (gap < Float.MAX_VALUE) target = minOf(target, stoppingSpeed(gap - FOLLOW_GAP))
            }
            val toRider = gapAlongPath(rider.x, rider.z, RIDER_CLEARANCE, CAR_LENGTH / 2f + 1f)
            if (toRider < Float.MAX_VALUE) target = minOf(target, stoppingSpeed(toRider - FOLLOW_GAP))

            speed[i] = if (target < speed[i]) maxOf(target, speed[i] - HARD_BRAKE * dt) else minOf(target, speed[i] + ACCELERATION * dt)
        }

        for (i in 0 until MAX_CARS) {
            if (!alive[i]) continue
            along[i] += speed[i] * dt
            val length = legLength(turn[i])
            if (along[i] >= length) {
                along[i] -= length
                val out = CityGrid.outDir(inDir[i], turn[i])
                nodeX[i] += CityGrid.DX[out]
                nodeZ[i] += CityGrid.DZ[out]
                inDir[i] = out
                turn[i] = randomTurn()
            }
            place(i)
        }
    }

    /**
     * Metres of clear road between this car and something at ([px], [pz]), if that lies within
     * [clearance] of the car's path as held in [pathX] and [pathZ]; otherwise a very large number.
     * [reach] is how far the two bodies extend toward each other along the path.
     */
    private fun gapAlongPath(px: Float, pz: Float, clearance: Float, reach: Float): Float {
        for (k in 0 until PATH_POINTS) {
            val dx = px - pathX[k]
            val dz = pz - pathZ[k]
            if (dx * dx + dz * dz < clearance * clearance) return (k + 1) * PATH_STEP - reach
        }
        return Float.MAX_VALUE
    }

    /** The fastest a car can be going and still stop comfortably in [distance] metres. */
    private fun stoppingSpeed(distance: Float): Float = sqrt(2f * BRAKE * maxOf(distance, 0f))

    /**
     * True if car [i], waiting to turn left, must give way: something is coming through the same
     * junction from the opposite direction. Two cars both waiting to turn left do not block each
     * other, or neither would ever go: the lower-numbered one goes first.
     */
    private fun oncoming(i: Int): Boolean {
        val opposite = (inDir[i] + 2) % 4
        for (j in 0 until MAX_CARS) {
            if (j == i || !alive[j] || nodeX[j] != nodeX[i] || nodeZ[j] != nodeZ[i] || inDir[j] != opposite) continue
            if (along[j] < STOP_AT - ONCOMING_LOOK || along[j] > CityGrid.HALF + CLEAR_OF_JUNCTION) continue
            val inJunction = along[j] >= STOP_AT
            if (turn[j] == CityGrid.LEFT && !inJunction && j > i) continue
            return true
        }
        return false
    }

    private fun spawn(rider: CityRider, blocksAway: Int) {
        // Somewhere on the square ring of nodes [blocksAway] from the rider's.
        val side = rng.nextInt(4)
        val across = rng.nextInt(2 * blocksAway + 1) - blocksAway
        val ox = when (side) { 0 -> blocksAway; 1 -> -blocksAway; else -> across }
        val oz = when (side) { 2 -> blocksAway; 3 -> -blocksAway; else -> across }
        val dir = rng.nextInt(4)
        val t = randomTurn()
        val s = rng.range(0f, STOP_AT - 12f)
        val colour = rng.nextInt(PAINTS)

        var slot = -1
        for (i in 0 until MAX_CARS) if (!alive[i]) { slot = i; break }
        if (slot < 0) return
        nodeX[slot] = rider.nodeX + ox
        nodeZ[slot] = rider.nodeZ + oz
        inDir[slot] = dir
        turn[slot] = t
        along[slot] = s
        place(slot)
        // Not on top of another car, and never right beside the rider.
        for (j in 0 until MAX_CARS) {
            if (j == slot || !alive[j]) continue
            val dx = x[j] - x[slot]
            val dz = z[j] - z[slot]
            if (dx * dx + dz * dz < SPAWN_CLEAR * SPAWN_CLEAR) return
        }
        val rx = rider.x - x[slot]
        val rz = rider.z - z[slot]
        if (rx * rx + rz * rz < RIDER_SPAWN_CLEAR * RIDER_SPAWN_CLEAR) return
        alive[slot] = true
        speed[slot] = CRUISE
        paint[slot] = colour
        liveCount++
    }

    private fun randomTurn(): Int {
        val r = rng.nextFloat()
        return if (r < 0.5f) CityGrid.STRAIGHT else if (r < 0.75f) CityGrid.LEFT else CityGrid.RIGHT
    }

    private fun legLength(turn: Int): Float = CityGrid.legLength(turn, CityGrid.CAR_LANE, RIGHT_RADIUS, LEFT_RADIUS)

    private fun place(i: Int) {
        CityGrid.pose(inDir[i], turn[i], CityGrid.CAR_LANE, RIGHT_RADIUS, LEFT_RADIUS, along[i], pose)
        x[i] = nodeX[i] * CityGrid.PERIOD + pose[0]
        z[i] = nodeZ[i] * CityGrid.PERIOD + pose[1]
        heading[i] = pose[2]
    }

    companion object {
        const val MAX_CARS = 30

        /** Cars are kept within this many blocks of the rider's node, counting along either street. */
        const val RANGE = 3
        const val PAINTS = 6

        const val CRUISE = 9f
        const val ACCELERATION = 2.5f
        const val BRAKE = 3f
        const val HARD_BRAKE = 9f
        const val CAR_LENGTH = 4.2f
        const val CAR_WIDTH = 1.8f

        /** A car waiting at the lights stops with its middle this far along its leg: its nose a metre short of the junction. */
        const val STOP_AT = CityGrid.HALF - CityGrid.HALF_STREET - CAR_LENGTH / 2f - 1f

        /** A car does not start across a junction with less than this much green left. */
        const val ENTER_MARGIN_SECONDS = 2f

        /** No car enters a junction while the rider is this close to its middle. */
        const val RIDER_PRIORITY = 25f

        /** A car checks its path at points this far apart, this many of them, and leaves this gap behind what it finds. */
        const val PATH_STEP = 2.5f
        const val PATH_POINTS = 9
        const val FOLLOW_GAP = 2.5f

        /**
         * How near to a point on its path something must be to be in the way. For another car it is
         * wide enough to catch one stopped part-way through a turn with its tail still in the lane,
         * but short of the oncoming lane 3.4 m away. For the rider it is narrower, so a car still
         * passes a bike in the bike lane 2.5 m beside it.
         */
        const val CAR_CLEARANCE = 2.7f
        const val RIDER_CLEARANCE = 2.1f

        /** A left-turner gives way to anything this far short of the opposite stop line, until it is this far past the middle. */
        const val ONCOMING_LOOK = 30f
        const val CLEAR_OF_JUNCTION = 7f

        /** Places tried each step for a new car while there are fewer than [MAX_CARS]. */
        const val SPAWN_TRIES = 4
        const val SPAWN_CLEAR = 16f
        const val RIDER_SPAWN_CLEAR = 60f

        /** Corner radii for a car in the car lane. */
        const val RIGHT_RADIUS = 4f
        const val LEFT_RADIUS = CityGrid.CAR_LANE + 5f
    }
}
