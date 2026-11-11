package dev.soloton.roadwork.env.pedalcraft

import dev.soloton.roadwork.Rng
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.exp

/**
 * The three pedal-powered craft, in the order they are flown.
 *
 * @param gearing metres flown per metre the rider pedals: the propeller's gearing
 * @param clearance cruising height above the highest ground near the route, in metres
 * @param liftRange extra height at full resistance, in metres
 * @param seat height of the pilot's seat above the wheels (or feet), in metres
 * @param cameraBack,cameraUp where the chase camera sits behind and above the craft
 */
enum class Craft(
    val title: String,
    val gearing: Float,
    val clearance: Float,
    val liftRange: Float,
    val seat: Float,
    val cameraBack: Float,
    val cameraUp: Float,
) {
    GLIDER("Pedal glider", 5f, 70f, 160f, 0.9f, 13f, 4.2f),
    PARAMOTOR("Pedal paramotor", 3f, 30f, 90f, 0.9f, 13f, 5.5f),
    PLANE("Pedal plane", 7f, 130f, 300f, 0.8f, 17f, 5f),
}

/** One craft flown along one route. */
class Leg(val craft: Craft, val route: FlightRoute)

/**
 * Which craft flies each leg of the tour: the rider's choice in the environment's settings.
 *
 * @param id the choice's id in settings, so never rename it
 */
enum class Fleet(val id: String, val title: String) {
    IN_TURN("tour", "In turn: glider, paramotor, plane"),
    RANDOM("random", "A random one each leg"),
    GLIDER("glider", "Always the pedal glider"),
    PARAMOTOR("paramotor", "Always the pedal paramotor"),
    PLANE("plane", "Always the pedal plane");

    companion object {
        fun byId(id: String?): Fleet = entries.firstOrNull { it.id == id } ?: IN_TURN
    }
}

/**
 * Where the rider's craft is: which [Leg] of the tour, how far along it, and from those its
 * position, height, heading, bank and pitch. Pure maths; everything advances only in [step].
 *
 * The tour is three legs that join end to end, so it repeats for as long as the ride lasts:
 * from the hill strip to the beach, from the beach to the airfield, and from the airfield back to
 * the hill strip. Which craft flies each is the rider's choice ([Fleet]); left alone it is the
 * glider, then the paramotor, then the plane. Any craft can fly any leg: each has its own route
 * between the two spots, at its own distance from the shore, sized to take about [LEG_MINUTES]
 * minutes at [REFERENCE_SPEED]. Routes are worked out the first time they are flown.
 *
 * - **Pedalling** drives the propeller: the craft covers [Craft.gearing] metres for every metre
 *   ridden, and stands still in the air when the rider stops.
 * - **Height** is the route's own cruising height ([FlightRoute.cruise]) plus lift.
 * - **Lift** is [Craft.liftRange] times the resistance fraction, eased over a few seconds. It
 *   needs pedalling: below [FULL_LIFT_SPEED] it fades, and a stopped rider sinks back to the
 *   cruising height.
 */
class FlightMotion(val terrain: IslandTerrain, private val seed: Long = 0L) {
    /** The spot each leg of the tour starts from; it lands at the next one round. */
    private val spots = listOf(terrain.hill, terrain.beach, terrain.airfield)
    private val routes = arrayOfNulls<FlightRoute>(Craft.entries.size * LEGS)

    /**
     * The route [craft] flies on leg [index] of the tour, built the first time it is asked for.
     * Each leg circles the way that brings a craft onto its landing strip already lined up: the
     * first clockwise, arriving up the west shore; the other two anticlockwise.
     */
    fun routeFor(craft: Craft, index: Int): FlightRoute {
        val slot = craft.ordinal * LEGS + index
        routes[slot]?.let { return it }
        val built = FlightRoute(
            terrain, spots[index], 1f, spots[(index + 1) % LEGS], LANDING_SIGN[index], CLOCKWISE[index],
            ORBIT[craft.ordinal], craft.gearing * REFERENCE_SPEED * LEG_MINUTES * 60f, craft.clearance, WOBBLE[craft.ordinal],
        )
        routes[slot] = built
        return built
    }

    /** The tour as it is flown when the rider has not chosen otherwise: glider, paramotor, plane. */
    val legs: List<Leg> = List(LEGS) { Leg(Craft.entries[it], routeFor(Craft.entries[it], it)) }

    /** Which craft flies each leg. A change takes effect on the next leg, or at once if this one has not set off. */
    var fleet: Fleet = Fleet.IN_TURN
        set(value) {
            if (field == value) return
            field = value
            if (travel == 0f) {
                leg = legFor(legIndex)
                lift = 0f
                place(0f, snap = true)
            }
        }

    /** The craft for leg [index] under the current [fleet]; a random one is drawn from the seed and the number of legs flown. */
    private fun legFor(index: Int): Leg {
        val craft = when (fleet) {
            Fleet.IN_TURN -> Craft.entries[index]
            Fleet.RANDOM -> Craft.entries[(Rng.hash01(legsFlown.toLong(), 31, seed) * Craft.entries.size).toInt().coerceIn(0, Craft.entries.size - 1)]
            Fleet.GLIDER -> Craft.GLIDER
            Fleet.PARAMOTOR -> Craft.PARAMOTOR
            Fleet.PLANE -> Craft.PLANE
        }
        return if (craft == legs[index].craft) legs[index] else Leg(craft, routeFor(craft, index))
    }

    var legIndex = 0
        private set

    /** How many legs have been flown to the end since the scene started. */
    var legsFlown = 0
        private set

    /** Metres flown along the current leg's route. */
    var travel = 0f
        private set

    /** Extra metres of height the rider is holding with resistance. */
    var lift = 0f
        private set

    var x = 0f
        private set

    /** Height of the pilot's seat above the sea. */
    var y = 0f
        private set
    var z = 0f
        private set

    /** Direction of travel: a unit vector on the map. */
    var dirX = 1f
        private set
    var dirZ = 0f
        private set

    /** Roll into the turn, in degrees; positive is right wing down. */
    var bankDegrees = 0f
        private set

    /** Nose up, in degrees. */
    var pitchDegrees = 0f
        private set

    /** Airspeed in metres per second. */
    var airspeed = 0f
        private set

    /** The leg being flown. */
    var leg: Leg = legs[0]
        private set
    val craft: Craft get() = leg.craft

    /** Height of the seat above the ground or sea right under the craft. */
    val heightAboveGround: Float get() = y - terrain.ground(x, z)

    init {
        place(0f, snap = true)
        bankDegrees = 0f
        pitchDegrees = 0f
    }

    fun step(dt: Float, speed: Float, resistance: Float) {
        airspeed = speed.coerceAtLeast(0f) * craft.gearing
        travel += airspeed * dt
        if (travel >= leg.route.length) {
            legIndex = (legIndex + 1) % LEGS
            legsFlown++
            leg = legFor(legIndex)
            travel = 0f
            lift = 0f
        }
        val pedalling = (speed / FULL_LIFT_SPEED).coerceIn(0f, 1f)
        val wanted = craft.liftRange * (if (resistance.isNaN()) 0f else resistance.coerceIn(0f, 1f)) * pedalling
        lift += (wanted - lift) * (1f - exp(-dt / LIFT_EASE_SECONDS))
        place(dt, snap = travel == 0f)
    }

    /**
     * Works out the pose at [travel]. Height eases toward the route's ([snap] jumps straight to
     * it, at the start of a leg) but is never below the ground; [dt] also eases the bank and pitch.
     */
    private fun place(dt: Float, snap: Boolean) {
        val r = leg.route
        val before = y
        x = r.x(travel)
        z = r.z(travel)
        val wanted = r.heightAt(travel, lift, craft.seat)
        y = if (snap || dt <= 0f) wanted else y + (wanted - y) * (1f - exp(-dt / HEIGHT_EASE_SECONDS))
        y = maxOf(y, terrain.ground(x, z) + craft.seat)
        val heading = r.heading(travel)
        dirX = kotlin.math.cos(heading)
        dirZ = kotlin.math.sin(heading)
        if (dt <= 0f) return

        // Bank: what a coordinated turn at this airspeed and this rate of turn would need.
        var turn = r.heading(travel + TURN_LOOK) - r.heading(travel - TURN_LOOK)
        while (turn > PI) turn -= (2 * PI).toFloat()
        while (turn < -PI) turn += (2 * PI).toFloat()
        val perMetre = turn / (2f * TURN_LOOK)
        val bank = Math.toDegrees(atan(perMetre * airspeed * airspeed / GRAVITY).toDouble()).toFloat() * r.airborne(travel)
        val ease = 1f - exp(-dt / ATTITUDE_EASE_SECONDS)
        bankDegrees += (bank.coerceIn(-MAX_BANK, MAX_BANK) - bankDegrees) * ease

        val flown = airspeed * dt
        val climb = if (flown > 1e-4f) Math.toDegrees(atan2(y - before, flown).toDouble()).toFloat() else 0f
        pitchDegrees += (climb.coerceIn(-MAX_PITCH, MAX_PITCH) - pitchDegrees) * ease
    }

    companion object {
        /** A leg takes about this long at [REFERENCE_SPEED] metres per second on the bike. */
        const val LEG_MINUTES = 20f
        const val REFERENCE_SPEED = 3f

        /** Pedalling slower than this, the lift from resistance fades; stopped, there is none. */
        const val FULL_LIFT_SPEED = 1.5f
        const val LIFT_EASE_SECONDS = 2.5f
        const val HEIGHT_EASE_SECONDS = 0.6f
        const val ATTITUDE_EASE_SECONDS = 0.9f
        const val MAX_BANK = 32f
        const val MAX_PITCH = 18f
        /** Legs in the tour: one from each landing spot to the next. */
        const val LEGS = 3

        // Per leg: which way along the strip a craft lands, and which way round the island it circles.
        private val LANDING_SIGN = floatArrayOf(-1f, 1f, -1f)
        private val CLOCKWISE = booleanArrayOf(true, false, false)

        // Per craft: how far out it circles, as a multiple of the shore's distance, and what varies that.
        private val ORBIT = floatArrayOf(1.02f, 0.72f, 1.42f)
        private val WOBBLE = floatArrayOf(0.4f, 2.3f, 4.1f)
        private const val TURN_LOOK = 20f
        private const val GRAVITY = 9.8f
    }
}
