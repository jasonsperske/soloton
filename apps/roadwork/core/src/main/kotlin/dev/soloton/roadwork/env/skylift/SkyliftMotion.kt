package dev.soloton.roadwork.env.skylift

import dev.soloton.roadwork.Rng
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * What moves on the mesa, as pure maths: how high the bike has lifted, the things coming the other
 * way, and the rider's swerve around the ones on a collision course.
 *
 * - **Lift.** Resistance up to [GROUND_BAND] keeps the wheels on the trail. Above it the bike
 *   climbs, up to [MAX_ALTITUDE] with the knob at its end ([liftFor]).
 * - **Traffic.** Drifters (in the air) and rollers (bouncing along the ground) arrive from
 *   [SPAWN_DISTANCE] ahead. They come more often and faster the higher the resistance
 *   ([spawnRate]), and stop coming when the rider stops.
 * - **Dodging.** The rider cannot steer, so the bike swerves by itself: when something will reach
 *   the rider within [DODGE_LOOKAHEAD_SECONDS] at the rider's height, [dodge] eases to the nearest
 *   lane that is clear of it, and back to the middle once it has passed. When the traffic is too
 *   thick for any lane to be clear, it takes the one with the most room.
 *
 * Positions are relative to the rider's place on the trail: x to the right, y up from the ground,
 * z negative ahead. Everything advances only in [step], and nothing allocates there.
 */
class SkyliftMotion(seed: Long) {
    /** Metres the bike has lifted off the trail. */
    var altitude = 0f
        private set

    /** The rider's sideways offset from the middle of the trail, in metres. */
    var dodge = 0f
        private set

    /** How fast [dodge] is changing, in metres per second (for leaning the view). */
    var dodgeSpeed = 0f
        private set

    val alive = BooleanArray(MAX_TRAFFIC)
    val kind = IntArray(MAX_TRAFFIC)
    val x = FloatArray(MAX_TRAFFIC)
    val y = FloatArray(MAX_TRAFFIC)
    val z = FloatArray(MAX_TRAFFIC)

    /** Each one's own speed toward the rider, on top of the rider's. */
    val ownSpeed = FloatArray(MAX_TRAFFIC)

    /** Spin or bounce angle in radians. */
    val phase = FloatArray(MAX_TRAFFIC)

    var liveCount = 0
        private set

    /** How many have been sent since the scene started. */
    var spawned = 0L
        private set

    /** Scratch for [step]: which of the traffic will reach the rider soon, at the rider's height. */
    private val arriving = BooleanArray(MAX_TRAFFIC)

    /** Scratch for [step]: seconds until each of the [arriving] is level with the rider. */
    private val arrival = FloatArray(MAX_TRAFFIC)
    private val rng = Rng(Rng.mix(seed xor 0x5C711F7L))
    private var untilSpawn = 1f

    /** Height of the rider's middle above the ground. */
    val riderHeight: Float get() = RIDER_MIDDLE + altitude

    /** Starts at the height [resistance] calls for, instead of climbing to it. */
    fun start(resistance: Float) {
        altitude = liftFor(resistance)
    }

    fun step(dt: Float, speed: Float, resistance: Float) {
        val lift = liftFor(resistance)
        altitude += (lift - altitude) * (1f - exp(-dt / LIFT_EASE_SECONDS))
        if (abs(lift - altitude) < 1e-3f) altitude = lift

        // Move the traffic, retire what has passed, and note what will reach the rider soon at the rider's height.
        val riderY = riderHeight
        for (i in 0 until MAX_TRAFFIC) {
            arriving[i] = false
            if (!alive[i]) continue
            val closing = speed + ownSpeed[i]
            z[i] += closing * dt
            phase[i] += dt * (if (kind[i] == ROLLER) ROLLER_BOUNCE_RATE else DRIFTER_SPIN_RATE)
            if (z[i] > PASSED) {
                alive[i] = false
                liveCount--
                continue
            }
            if (z[i] > LEVEL_BEHIND || closing <= 0.01f) continue
            arrival[i] = -z[i] / closing
            arriving[i] = arrival[i] < DODGE_LOOKAHEAD_SECONDS && abs(y[i] - riderY) < CLEARANCE_HEIGHT
        }

        // Ride in the lane nearest the middle of the trail that is clear of everything arriving
        // (the middle itself when nothing is), without cutting across anything that is about to
        // arrive on the way there. Failing that, the lane with the most room.
        var target = 0f
        var bestRank = -1
        var mostRoom = -1f
        for (l in 0 until LANES) {
            val n = (l + 1) / 2
            val lane = (if (l % 2 == 1) n else -n) * LANE_STEP
            val low = minOf(dodge, lane)
            val high = maxOf(dodge, lane)
            var room = Float.MAX_VALUE
            var crosses = false
            for (i in 0 until MAX_TRAFFIC) {
                if (!arriving[i]) continue
                room = minOf(room, abs(x[i] - lane))
                if (arrival[i] < CROSSING_SECONDS && x[i] > low && x[i] < high) crosses = true
            }
            val rank = if (crosses) 0 else if (room < CLEARANCE) 1 else 2
            if (rank == 2) {
                target = lane
                break
            }
            if (rank > bestRank || (rank == bestRank && room > mostRoom)) {
                bestRank = rank
                mostRoom = room
                target = lane
            }
        }
        val before = dodge
        dodge += (target - dodge) * (1f - exp(-dt / DODGE_EASE_SECONDS))
        if (abs(target - dodge) < 1e-3f) dodge = target
        dodgeSpeed = if (dt > 0f) (dodge - before) / dt else 0f

        untilSpawn -= spawnRate(speed, resistance) * dt
        while (untilSpawn <= 0f) {
            spawn(resistance)
            untilSpawn += rng.range(0.6f, 1.4f)
        }
    }

    private fun spawn(resistance: Float) {
        // Draw the same random numbers whether or not there is room, so a full pool does not
        // change what comes next.
        val aimed = rng.chance(AIMED_SHARE)
        val side = if (rng.chance(0.5f)) 1f else -1f
        val aimedX = rng.range(-AIMED_SPREAD, AIMED_SPREAD)
        val wideX = side * rng.range(2.5f, 16f)
        val roller = altitude < GROUNDED_BELOW && rng.chance(ROLLER_SHARE)
        val aimedY = rng.range(-0.5f, 0.5f)
        val wideY = rng.range(-3f, 6f)
        val pace = rng.nextFloat()
        val startPhase = rng.range(0f, 6.28f)

        var slot = -1
        for (i in 0 until MAX_TRAFFIC) if (!alive[i]) { slot = i; break }
        if (slot < 0) return
        val r = resistance.coerceIn(0f, 1f)
        alive[slot] = true
        kind[slot] = if (roller) ROLLER else DRIFTER
        x[slot] = if (aimed) aimedX else wideX
        y[slot] = when {
            roller -> ROLLER_RADIUS
            aimed -> (riderHeight + aimedY).coerceAtLeast(DRIFTER_FLOOR)
            else -> (riderHeight + wideY).coerceAtLeast(DRIFTER_FLOOR)
        }
        z[slot] = -SPAWN_DISTANCE
        ownSpeed[slot] = if (roller) 4f + 5f * pace else 9f + 9f * pace + 8f * r
        phase[slot] = startPhase
        liveCount++
        spawned++
    }

    companion object {
        const val MAX_TRAFFIC = 32
        const val DRIFTER = 0
        const val ROLLER = 1

        /** Resistance up to this fraction keeps the bike on the ground. */
        const val GROUND_BAND = 0.15f

        /** Height with the resistance knob at its end, in metres. */
        const val MAX_ALTITUDE = 40f
        const val LIFT_EASE_SECONDS = 1.6f

        /** Height of the rider's middle above the wheels' contact with the ground. */
        const val RIDER_MIDDLE = 1.1f

        const val SPAWN_DISTANCE = 320f

        /** Past the rider by this much, a thing is retired. */
        const val PASSED = 8f

        /** Arrivals per second at a riding pace: with no resistance, and with the knob at its end. */
        const val BASE_RATE = 0.12f
        const val FULL_RATE = 1.6f

        /** Below this speed arrivals thin out, down to none at a standstill. */
        const val FULL_RATE_SPEED = 1.5f

        /** Share of arrivals that come straight down the trail, so the rider has to swerve. */
        const val AIMED_SHARE = 0.35f
        const val AIMED_SPREAD = 0.9f

        /** Below this altitude some arrivals are rollers on the ground. */
        const val GROUNDED_BELOW = 1f
        const val ROLLER_SHARE = 0.55f
        const val ROLLER_RADIUS = 0.55f
        const val ROLLER_BOUNCE_RATE = 5f
        const val DRIFTER_SPIN_RATE = 3f

        /** Drifters never come lower than this. */
        const val DRIFTER_FLOOR = 1f

        const val DODGE_LOOKAHEAD_SECONDS = 2.2f
        const val DODGE_EASE_SECONDS = 0.35f

        /** How close, sideways and in height, counts as in the way. */
        const val CLEARANCE = 1.6f
        const val CLEARANCE_HEIGHT = 1.8f

        /** The rider swerves between lanes this far apart, [LANES] of them centred on the middle of the trail. */
        const val LANE_STEP = 0.65f
        const val LANES = 9

        /** The furthest the rider goes from the middle of the trail. */
        const val MAX_DODGE = LANE_STEP * (LANES / 2)

        /** The rider does not cut across the path of anything that will arrive within this many seconds. */
        const val CROSSING_SECONDS = 1.2f

        /** A thing still counts as arriving until it is this far past the rider. */
        const val LEVEL_BEHIND = 1.5f

        /** Metres of lift for a resistance fraction: none in the ground band, then rising ever faster. */
        fun liftFor(resistance: Float): Float {
            if (resistance.isNaN()) return 0f
            val t = ((resistance - GROUND_BAND) / (1f - GROUND_BAND)).coerceIn(0f, 1f)
            return MAX_ALTITUDE * t.pow(1.3f)
        }

        /** Arrivals per second for a speed in metres per second and a resistance fraction. */
        fun spawnRate(speed: Float, resistance: Float): Float {
            val moving = (speed / FULL_RATE_SPEED).coerceIn(0f, 1f)
            return moving * (BASE_RATE + (FULL_RATE - BASE_RATE) * resistance.coerceIn(0f, 1f))
        }
    }
}
