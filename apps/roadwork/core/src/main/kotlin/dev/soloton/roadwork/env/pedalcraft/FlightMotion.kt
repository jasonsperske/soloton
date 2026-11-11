package dev.soloton.roadwork.env.pedalcraft

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
 * Where the rider's craft is: which [Leg] of the tour, how far along it, and from those its
 * position, height, heading, bank and pitch. Pure maths; everything advances only in [step].
 *
 * The tour is three legs that join end to end, so it repeats for as long as the ride lasts:
 * the glider from the hill strip to the beach, the paramotor from the beach to the airfield, and
 * the plane from the airfield back to the hill strip. Each is sized to take about
 * [LEG_MINUTES] minutes at [REFERENCE_SPEED].
 *
 * - **Pedalling** drives the propeller: the craft covers [Craft.gearing] metres for every metre
 *   ridden, and stands still in the air when the rider stops.
 * - **Height** is the route's own cruising height ([FlightRoute.cruise]) plus lift.
 * - **Lift** is [Craft.liftRange] times the resistance fraction, eased over a few seconds. It
 *   needs pedalling: below [FULL_LIFT_SPEED] it fades, and a stopped rider sinks back to the
 *   cruising height.
 */
class FlightMotion(val terrain: IslandTerrain) {
    val legs: List<Leg> = listOf(
        // Each craft circles the way that brings it onto its landing strip already lined up: the
        // glider clockwise, arriving up the west shore; the other two anticlockwise.
        Leg(Craft.GLIDER, route(Craft.GLIDER, terrain.hill, 1f, terrain.beach, -1f, clockwise = true, orbit = 1.02f, wobble = 0.4f)),
        Leg(Craft.PARAMOTOR, route(Craft.PARAMOTOR, terrain.beach, 1f, terrain.airfield, 1f, clockwise = false, orbit = 0.72f, wobble = 2.3f)),
        Leg(Craft.PLANE, route(Craft.PLANE, terrain.airfield, 1f, terrain.hill, -1f, clockwise = false, orbit = 1.42f, wobble = 4.1f)),
    )

    private fun route(craft: Craft, from: Spot, fromSign: Float, to: Spot, toSign: Float, clockwise: Boolean, orbit: Float, wobble: Float) =
        FlightRoute(terrain, from, fromSign, to, toSign, clockwise, orbit, craft.gearing * REFERENCE_SPEED * LEG_MINUTES * 60f, craft.clearance, wobble)

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

    val leg: Leg get() = legs[legIndex]
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
            legIndex = (legIndex + 1) % legs.size
            legsFlown++
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
        private const val TURN_LOOK = 20f
        private const val GRAVITY = 9.8f
    }
}
