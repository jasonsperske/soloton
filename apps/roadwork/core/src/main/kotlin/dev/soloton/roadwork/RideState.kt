package dev.soloton.roadwork

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The rider on the bike: elapsed time, distance, current speed, and how far the wheels and cranks
 * have turned. Pure maths, no rendering.
 *
 * Speed eases toward the pace source over [SPEED_EASE_SECONDS] so a button tap or a cadence change
 * does not jolt the camera. Distance is kept in double so hours of riding stay precise. The wheels
 * turn by distance / radius and the cranks by wheel angle / [GEAR_RATIO].
 *
 * There are two speeds. [speed] and [distanceMeters] are the bike's own: what the rider is really
 * doing, what the HUD shows and what a goal counts. [worldSpeed] and [worldDistanceMeters] are
 * those times [worldScale]: how fast the scenery goes by. A scene moves its world by the world
 * values, so an environment can feel as fast as it should without the numbers on screen lying.
 */
class RideState(initialSpeed: Float) {
    var elapsedSeconds: Double = 0.0
        private set
    var distanceMeters: Double = 0.0
        private set
    var speed: Float = initialSpeed.coerceAtLeast(0f)
        private set

    /**
     * How many metres of world go by for each metre the bike covers. Set by the game from the
     * environment's settings; 1 until then. Changing it during a ride changes the speed from then
     * on and leaves the distance already covered as it is.
     */
    var worldScale: Float = 1f
        set(value) {
            field = if (value.isNaN()) 1f else value.coerceIn(MIN_WORLD_SCALE, MAX_WORLD_SCALE)
        }

    /** How fast the scenery goes by, in metres per second: [speed] times [worldScale]. */
    val worldSpeed: Float get() = speed * worldScale

    /** How far the scenery has gone by, in metres. */
    var worldDistanceMeters: Double = 0.0
        private set

    /** Front and rear wheel rotation in radians, wrapped to [0, 2π). */
    var wheelAngle: Float = 0f
        private set

    /** Crank rotation in radians, wrapped to [0, 2π); 0 = right pedal at the bottom. */
    var crankAngle: Float = 0f
        private set

    /**
     * How hard the bike is set, 0 to 1, eased over [RESISTANCE_EASE_SECONDS] so the knob's coarse
     * updates do not jolt a scene that uses it. 0 when nothing reports a resistance.
     */
    var resistance: Float = 0f
        private set

    fun step(dt: Float, targetSpeed: Float, targetResistance: Float = 0f) {
        val wanted = if (targetResistance.isNaN()) 0f else targetResistance.coerceIn(0f, 1f)
        resistance += (wanted - resistance) * (1f - exp(-dt / RESISTANCE_EASE_SECONDS))
        if (kotlin.math.abs(resistance - wanted) < 1e-4f) resistance = wanted
        val target = targetSpeed.coerceAtLeast(0f)
        val k = 1f - exp(-dt / SPEED_EASE_SECONDS)
        speed += (target - speed) * k
        if (kotlin.math.abs(speed - target) < 1e-4f) speed = target
        elapsedSeconds += dt
        val metres = speed * dt
        distanceMeters += metres.toDouble()
        worldDistanceMeters += (metres * worldScale).toDouble()
        wheelAngle = wrap(wheelAngle + wheelAngleFor(metres))
        crankAngle = wrap(crankAngle + crankAngleFor(metres))
    }

    companion object {
        const val SPEED_EASE_SECONDS = 1.2f
        const val RESISTANCE_EASE_SECONDS = 0.6f

        /** The least and most an environment's world may be scaled against the bike. */
        const val MIN_WORLD_SCALE = 0.25f
        const val MAX_WORLD_SCALE = 100f
        /** A 700c road wheel. */
        const val WHEEL_RADIUS = 0.34f
        /**
         * Wheel turns per crank turn: an easy gear, so cadence stays comfortable (about 55 rpm at
         * 3 m/s, 110 rpm at 6 m/s).
         */
        const val GEAR_RATIO = 1.55f
        private const val TWO_PI = (2 * PI).toFloat()

        fun wrap(a: Float): Float {
            val r = a % TWO_PI
            return if (r < 0f) r + TWO_PI else r
        }

        fun wheelAngleFor(meters: Float): Float = meters / WHEEL_RADIUS

        fun crankAngleFor(meters: Float): Float = meters / WHEEL_RADIUS / GEAR_RATIO

        /** Cadence in crank revolutions per minute for a speed. */
        fun cadenceRpm(speed: Float): Float = speed / (TWO_PI * WHEEL_RADIUS) / GEAR_RATIO * 60f

        /** How much the rider rocks at a speed: nothing when stopped, full by a steady cruise. */
        private fun effort(speed: Float): Float = (speed / 2.5f).coerceIn(0f, 1.3f)

        /**
         * Side-to-side sway of the head in metres. One slow rock per crank turn (left push, right
         * push), so it is far gentler and lower in frequency than a running bob.
         */
        fun sway(crank: Float, speed: Float): Float = 0.014f * effort(speed) * sin(crank)

        /** Vertical bob in metres: a tiny dip on each pedal stroke (two per crank turn), mean zero. */
        fun bob(crank: Float, speed: Float): Float = 0.005f * effort(speed) * cos(2f * crank)

        /** Roll of the view in degrees, in step with the sway. */
        fun roll(crank: Float, speed: Float): Float = 0.7f * effort(speed) * sin(crank)
    }
}
