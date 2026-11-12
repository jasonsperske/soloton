package dev.soloton.roadwork.env.nightstreets

import dev.soloton.roadwork.Rng

/**
 * The rider's way through the city: along the bike lane, and at every intersection straight on,
 * left or right, chosen at random (never back the way they came). Pure maths; it advances only in
 * [step], and the same seed gives the same route.
 *
 * Half the time the rider goes straight on, a quarter of the time left and a quarter right.
 */
class CityRider(private val seed: Long) {
    /** The node the current leg passes through, the direction it enters by, and its turn there. */
    var nodeX = 0
        private set
    var nodeZ = 0
        private set
    var inDir = CityGrid.NORTH
        private set
    var turn = CityGrid.STRAIGHT
        private set

    /** Metres along the current leg. */
    var along = 0f
        private set

    /** How many legs have been finished. */
    var legs = 0L
        private set

    /** Position in metres (x east, z south) and heading in radians from east toward south. */
    var x = 0f
        private set
    var z = 0f
        private set
    var heading = 0f
        private set

    /** How fast the rider is going, in metres per second, as of the last [step]. */
    var speed = 0f
        private set

    /** How fast the heading is changing, in radians per second: positive turning right. */
    var turnRate = 0f
        private set

    private val pose = FloatArray(3)

    init {
        turn = chooseTurn(0)
        place()
    }

    val legLength: Float get() = CityGrid.legLength(turn, CityGrid.BIKE_LANE, RIGHT_RADIUS, LEFT_RADIUS)

    /** Distance in metres from the centre of the node this leg passes through. */
    val distanceToNode: Float
        get() {
            val dx = x - nodeX * CityGrid.PERIOD
            val dz = z - nodeZ * CityGrid.PERIOD
            return kotlin.math.sqrt(dx * dx + dz * dz)
        }

    fun step(dt: Float, speed: Float) {
        val before = heading
        this.speed = speed.coerceAtLeast(0f)
        along += this.speed * dt
        while (along >= legLength) {
            along -= legLength
            val out = CityGrid.outDir(inDir, turn)
            nodeX += CityGrid.DX[out]
            nodeZ += CityGrid.DZ[out]
            inDir = out
            legs++
            turn = chooseTurn(legs)
        }
        place()
        var change = heading - before
        while (change > Math.PI) change -= (2 * Math.PI).toFloat()
        while (change < -Math.PI) change += (2 * Math.PI).toFloat()
        turnRate = if (dt > 0f) change / dt else 0f
    }

    private fun place() {
        CityGrid.pose(inDir, turn, CityGrid.BIKE_LANE, RIGHT_RADIUS, LEFT_RADIUS, along, pose)
        x = nodeX * CityGrid.PERIOD + pose[0]
        z = nodeZ * CityGrid.PERIOD + pose[1]
        heading = pose[2]
    }

    private fun chooseTurn(leg: Long): Int {
        val r = Rng.hash01(leg, 77, seed)
        return when {
            r < STRAIGHT_SHARE -> CityGrid.STRAIGHT
            r < STRAIGHT_SHARE + (1f - STRAIGHT_SHARE) / 2f -> CityGrid.LEFT
            else -> CityGrid.RIGHT
        }
    }

    companion object {
        const val STRAIGHT_SHARE = 0.5f

        /**
         * Corner radii for the bike: a tight right at the kerb, a wide left across the junction.
         * The right is as wide as it can be without the arc cutting across the corner of the pavement.
         */
        const val RIGHT_RADIUS = 2.4f
        const val LEFT_RADIUS = CityGrid.BIKE_LANE + 3f
    }
}
