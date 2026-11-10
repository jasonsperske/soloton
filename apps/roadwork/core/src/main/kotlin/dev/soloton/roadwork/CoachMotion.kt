package dev.soloton.roadwork

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the coach is and how the bicycle turns, as plain maths so it can be unit tested.
 *
 * The coach holds a gap of roughly 6-10 m ahead of the runner that drifts slowly, and wanders a
 * little across the bike lane. The bike's speed is the runner's speed plus the rate the gap changes;
 * the wheels turn by distance / radius and the cranks by wheel angle / [GEAR_RATIO].
 */
class CoachMotion {
    /** Rear and front wheel rotation in radians (unbounded, wrapped to 2π). */
    var wheelAngle: Float = 0f
        private set
    /** Crank rotation in radians; 0 = right pedal at the bottom. */
    var crankAngle: Float = 0f
        private set
    var speed: Float = 0f
        private set

    fun step(dt: Float, t: Double, runnerSpeed: Float) {
        speed = max(0f, runnerSpeed + gapRate(t))
        wheelAngle = wrap(wheelAngle + wheelAngleFor(speed * dt))
        crankAngle = wrap(crankAngle + crankAngleFor(speed * dt))
    }

    companion object {
        const val WHEEL_RADIUS = 0.34f
        /** Wheel turns per crank turn: an easy gear, so the coach pedals at a relaxed cadence. */
        const val GEAR_RATIO = 2.1f
        const val CRANK_LENGTH = 0.17f
        private const val TWO_PI = (2 * PI).toFloat()

        fun wrap(a: Float): Float {
            val r = a % TWO_PI
            return if (r < 0f) r + TWO_PI else r
        }

        fun wheelAngleFor(meters: Float): Float = meters / WHEEL_RADIUS

        fun crankAngleFor(meters: Float): Float = meters / WHEEL_RADIUS / GEAR_RATIO

        /** Gap ahead of the runner in metres; stays within about 5.8..8.6 m. */
        fun gap(t: Double): Float =
            (7.2 + 1.0 * sin(0.083 * t) + 0.4 * sin(0.21 * t + 1.3)).toFloat()

        /** d(gap)/dt, so wheel speed matches the drift. */
        fun gapRate(t: Double): Float =
            (1.0 * 0.083 * cos(0.083 * t) + 0.4 * 0.21 * cos(0.21 * t + 1.3)).toFloat()

        /** Sideways position (x, metres) in the bike lane. */
        fun lateral(t: Double): Float =
            (0.95 + 0.32 * sin(0.047 * t + 0.7) + 0.07 * sin(0.29 * t)).toFloat()

        /** Small steering yaw in degrees that follows the sideways drift. */
        fun steerDegrees(t: Double): Float =
            (-(0.32 * 0.047 * cos(0.047 * t + 0.7) + 0.07 * 0.29 * cos(0.29 * t)) * 60.0).toFloat()

        /**
         * Pedal centre for a crank angle in the bike's side plane: returns z (forward is -z) in
         * out[0] and y in out[1], relative to the bottom bracket.
         */
        fun pedal(crank: Float, out: FloatArray) {
            out[0] = CRANK_LENGTH * sin(crank)
            out[1] = -CRANK_LENGTH * cos(crank)
        }

        private fun smooth(x: Float): Float {
            val c = x.coerceIn(0f, 1f)
            return c * c * (3f - 2f * c)
        }

        /** 0..1 envelope that rises over [ramp] s at [start], holds, and falls by [end]. */
        fun envelope(p: Float, start: Float, end: Float, ramp: Float): Float =
            smooth((p - start) / ramp) * smooth((end - p) / ramp)

        /** How far the coach is glancing back over his right shoulder (0..1). Every 19 s, for ~2.5 s. */
        fun glance(t: Double): Float = envelope((t % 19.0).toFloat(), 6f, 8.6f, 0.6f)

        /** How far the right arm is raised in a wave (0..1). Every 43 s, for ~3.5 s. */
        fun wave(t: Double): Float = envelope((t % 43.0).toFloat(), 23f, 26.8f, 0.7f)

        /** Head yaw in degrees (positive turns the face toward the runner over the right shoulder). */
        fun headYaw(t: Double): Float = 105f * max(glance(t), 0.85f * wave(t))

        /**
         * Two-bone inverse kinematics. Places the middle joint (knee, elbow) for a root at s, an end
         * target at t, bone lengths [a] and [b], bending toward pole direction p. If the target is out
         * of reach the limb straightens toward it. Writes the joint into out[0..2] and the reached
         * end point into out[3..5].
         */
        fun twoBoneIk(
            sx: Float, sy: Float, sz: Float,
            tx: Float, ty: Float, tz: Float,
            a: Float, b: Float,
            px: Float, py: Float, pz: Float,
            out: FloatArray,
        ) {
            var dx = tx - sx
            var dy = ty - sy
            var dz = tz - sz
            var d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < 1e-5f) { dx = 0f; dy = -1f; dz = 0f; d = 1e-5f }
            val ux = dx / d
            val uy = dy / d
            val uz = dz / d
            val reach = d.coerceIn(kotlin.math.abs(a - b) + 1e-4f, a + b - 1e-4f)
            val along = (a * a - b * b + reach * reach) / (2f * reach)
            val h = sqrt(max(0f, a * a - along * along))
            // Pole direction made perpendicular to the limb axis.
            val pd = px * ux + py * uy + pz * uz
            var qx = px - pd * ux
            var qy = py - pd * uy
            var qz = pz - pd * uz
            val ql = sqrt(qx * qx + qy * qy + qz * qz)
            if (ql < 1e-5f) { qx = 0f; qy = 0f; qz = -1f } else { qx /= ql; qy /= ql; qz /= ql }
            out[0] = sx + ux * along + qx * h
            out[1] = sy + uy * along + qy * h
            out[2] = sz + uz * along + qz * h
            out[3] = sx + ux * reach
            out[4] = sy + uy * reach
            out[5] = sz + uz * reach
        }
    }
}
