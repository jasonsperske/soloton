package dev.soloton.roadwork

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the jogger is and how their body moves, as plain maths so it can be unit tested.
 *
 * The jogger paces the rider: they run at the rider's speed plus a small correction that eases the
 * gap toward a target. The target drifts slowly between about 5.9 and 8.1 m and opens up a little
 * at higher speed, so a pace change makes the gap ease to its new value instead of snapping. The
 * gap never leaves [MIN_GAP]..[MAX_GAP].
 *
 * Timing of the glance back and the shadow-boxing bursts comes from the seed, so a seed always
 * gives the same performance.
 */
class JoggerMotion(seed: Long = RoadworkGame.DEFAULT_SEED) {
    /** Metres ahead of the rider (along the path). */
    var gap: Float = targetGap(0.0, 0f)
        private set
    /** The jogger's own forward speed in m/s. */
    var speed: Float = 0f
        private set
    /** Run-cycle phase in radians: one full turn is two steps (left and right foot). */
    var phase: Float = 1f
        private set

    /** Seconds into each 20 s cycle when the glance back starts. */
    val glanceAt: Float = 4f + 8f * Rng.hash01(seed, 101, 7)
    /** Seconds into each 40 s cycle when the shadow-boxing burst starts (between two glances). */
    val punchAt: Float = (glanceAt + 10f) % PUNCH_PERIOD

    private var started = false

    fun step(dt: Float, t: Double, riderSpeed: Float) {
        if (!started) {
            gap = targetGap(t, riderSpeed)
            started = true
        }
        val target = targetGap(t, riderSpeed)
        val rate = ((target - gap) / GAP_EASE_SECONDS).coerceIn(-MAX_GAP_RATE, MAX_GAP_RATE)
        gap = (gap + rate * dt).coerceIn(MIN_GAP, MAX_GAP)
        speed = max(0f, riderSpeed + rate)
        phase = wrap(phase + PI.toFloat() * stepsPerSecond(speed) * dt)
    }

    /** How far the jogger is glancing back over the left shoulder (0..1). Every 20 s, for ~2.4 s. */
    fun glance(t: Double): Float = envelope((t % GLANCE_PERIOD).toFloat() - glanceAt, 0f, 2.4f, 0.5f)

    /** How far into the guard / shadow-boxing pose the arms are (0..1). Every 40 s, for ~3.6 s. */
    fun punching(t: Double): Float = envelope(wrapPeriod((t % PUNCH_PERIOD).toFloat() - punchAt, PUNCH_PERIOD), 0f, 3.6f, 0.45f)

    /**
     * Extension of the punching arm (0..1) and which arm (-1 left, +1 right) during a burst:
     * jab, jab, cross, jab, cross... about 2.5 punches a second.
     */
    fun punch(t: Double, out: FloatArray) {
        val p = wrapPeriod((t % PUNCH_PERIOD).toFloat() - punchAt, PUNCH_PERIOD) - 0.45f
        if (p < 0f || p > 3.0f) { out[0] = 0f; out[1] = -1f; return }
        val k = p * PUNCHES_PER_SECOND
        val n = k.toInt()
        val f = k - n
        // Fast out, slower back.
        out[0] = if (f < 0.35f) smooth(f / 0.35f) else smooth((1f - f) / 0.65f)
        out[1] = if (n % 3 == 2) 1f else -1f
    }

    /** Head yaw in degrees (positive turns the face left, toward the rider behind). */
    fun headYaw(t: Double): Float = 100f * glance(t)

    companion object {
        const val MIN_GAP = 5f
        const val MAX_GAP = 9f
        const val GAP_EASE_SECONDS = 4f
        const val MAX_GAP_RATE = 0.5f
        const val GLANCE_PERIOD = 20.0
        const val PUNCH_PERIOD = 40f
        const val PUNCHES_PER_SECOND = 2.5f
        private const val TWO_PI = (2 * PI).toFloat()

        // Body measurements (metres) shared by the model and the run-cycle maths.
        const val HIP_Y = 0.93f
        const val THIGH = 0.46f
        const val SHIN = 0.46f
        const val UPPER_ARM = 0.30f
        const val FOREARM = 0.29f
        const val FOOT_Y = 0.07f

        fun wrap(a: Float): Float {
            val r = a % TWO_PI
            return if (r < 0f) r + TWO_PI else r
        }

        private fun wrapPeriod(x: Float, period: Float): Float {
            val r = x % period
            return if (r < 0f) r + period else r
        }

        /**
         * Target gap for time [t] and rider speed: a slow drift of about ±0.8 m around a base that
         * grows from 6.3 m at a crawl to 7.3 m at a brisk ride.
         */
        fun targetGap(t: Double, speed: Float): Float {
            val base = 6.3f + 0.35f * (speed - 1.5f).coerceIn(0f, 3f)
            val drift = 0.55 * sin(0.061 * t + 0.4) + 0.25 * sin(0.17 * t + 2.1)
            return base + drift.toFloat()
        }

        /** Sideways position (x, metres): drifting slowly within the right half of the ride lane. */
        fun lateral(t: Double): Float =
            (0.95 + 0.28 * sin(0.043 * t + 0.7) + 0.08 * sin(0.23 * t)).toFloat()

        /** Small heading change in degrees that follows the sideways drift (positive turns left). */
        fun headingDegrees(t: Double): Float =
            (-(0.28 * 0.043 * cos(0.043 * t + 0.7) + 0.08 * 0.23 * cos(0.23 * t)) * 60.0).toFloat()

        /** Running cadence: about 170 steps a minute at an easy jog, quicker when faster. */
        fun stepsPerSecond(speed: Float): Float =
            if (speed < 0.2f) 0f else (2.45f + 0.16f * speed).coerceAtMost(3.4f)

        /** How far each foot swings ahead of / behind the hip, in metres. */
        fun halfStride(speed: Float): Float {
            val sps = stepsPerSecond(speed)
            if (sps <= 0f) return 0f
            return (speed / (2f * sps)).coerceAtMost(0.40f)
        }

        /**
         * Foot of one leg for run phase [phase]; [side] is -1 for the left leg, +1 for the right.
         * Writes the forward offset from the hip (metres, positive = ahead) into out[0] and the
         * lift above the ground into out[1]. The legs are half a cycle apart, so one foot is
         * forward while the other is back, and only the foot swinging forward is lifted.
         */
        fun foot(phase: Float, side: Int, speed: Float, out: FloatArray) {
            val p = if (side < 0) phase else phase + PI.toFloat()
            val hs = halfStride(speed)
            out[0] = hs * sin(p)
            // Moving forward (cos > 0) means the swing phase; the heel kicks up early in the swing.
            val lift = (0.10f + 0.07f * speed).coerceAtMost(0.42f)
            out[1] = lift * max(0f, cos(p - 0.55f)) * (if (hs > 0f) 1f else 0f)
        }

        /** Arm swing (positive = hand forward) for an arm; opposite to the leg on the same side. */
        fun armSwing(phase: Float, side: Int): Float = -sin(if (side < 0) phase else phase + PI.toFloat())

        /** Vertical bob of the hips: lowest at mid-stance of each step, two bumps per cycle, mean zero. */
        fun bob(phase: Float, speed: Float): Float = -0.035f * (speed / 3f).coerceIn(0f, 1.3f) * cos(2f * phase)

        /** Hip yaw in degrees; the shoulders turn the other way. */
        fun hipYaw(phase: Float, speed: Float): Float = 9f * (speed / 3f).coerceIn(0f, 1.2f) * sin(phase)

        private fun smooth(x: Float): Float {
            val c = x.coerceIn(0f, 1f)
            return c * c * (3f - 2f * c)
        }

        /** 0..1 envelope that rises over [ramp] s at [start], holds, and falls by [end]. */
        fun envelope(p: Float, start: Float, end: Float, ramp: Float): Float =
            smooth((p - start) / ramp) * smooth((end - p) / ramp)

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
            val reach = d.coerceIn(abs(a - b) + 1e-4f, a + b - 1e-4f)
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
