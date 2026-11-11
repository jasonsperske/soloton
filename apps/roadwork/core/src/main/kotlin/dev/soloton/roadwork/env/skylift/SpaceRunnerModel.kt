package dev.soloton.roadwork.env.skylift

import com.badlogic.gdx.math.Matrix4
import dev.soloton.roadwork.MeshWriter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The space runner the rider follows on the mesa, rebuilt each frame from primitives into a
 * [MeshWriter].
 *
 * An original design: a white pressure suit with teal boots, gloves and belt, a round white helmet
 * with a dark visor and a small teal crest, and a grey thruster pack on the back. On the ground the
 * runner runs, one stride cycle per turn of the rider's cranks; in the air the body tips forward,
 * the legs trail, the arms sweep back and the pack's two thrusters burn. The runner's shadow stays
 * on the ground, shrinking with height.
 *
 * Runner space: forward is -z, the feet are on y = 0 when standing. No allocation per frame.
 */
class SpaceRunnerModel {
    private val base = Matrix4()
    private val body = Matrix4()

    /**
     * @param x sideways position, [altitude] metres the feet are above the ground
     * @param stride stride cycle in radians (the rider's crank angle)
     * @param speed metres per second; a stopped rider's runner stands still
     * @param leanDegrees roll into a swerve, positive to the right
     * @param flicker a steadily rising angle that makes the thruster flames flutter
     */
    fun build(w: MeshWriter, x: Float, altitude: Float, stride: Float, speed: Float, leanDegrees: Float, flicker: Float) {
        w.reset()
        val flight = flightFor(altitude)
        val run = (speed / 3f).coerceIn(0f, 1.15f) * (1f - flight)

        // The shadow, flat on the ground under the runner.
        val shade = 1f / (1f + altitude * 0.08f)
        base.idt().translate(x, 0f, 0f)
        w.transform.set(base)
        val n = 10
        for (i in 0 until n) {
            val a0 = (2 * PI * i / n).toFloat()
            val a1 = (2 * PI * (i + 1) / n).toFloat()
            w.tri(0f, SHADOW_Y, 0f,
                0.42f * shade * cos(a0), SHADOW_Y, -0.55f * shade * sin(a0),
                0.42f * shade * cos(a1), SHADOW_Y, -0.55f * shade * sin(a1), SHADOW)
        }

        // The body frame: origin at the hips, spine along +y, tipped forward in flight.
        val hipY = HIP_Y + 0.03f * cos(2f * stride) * run
        body.idt().translate(x, altitude + hipY, 0f)
            .rotate(0f, 0f, 1f, -leanDegrees)
            .rotate(1f, 0f, 0f, -(RUN_LEAN_DEGREES + (FLIGHT_LEAN_DEGREES - RUN_LEAN_DEGREES) * flight))
        w.transform.set(body)

        // Legs: thigh and shin as two beams, a boot at the ankle.
        for (s in -1..1 step 2) {
            val p = stride + if (s > 0) 0f else PI.toFloat()
            val thigh = mix(0.7f * sin(p) * run, 0.08f * s, flight)
            val knee = mix(0.2f + 1.0f * maxOf(0f, cos(p)) * run, 0.15f, flight)
            val shin = thigh - knee
            val hx = s * 0.10f
            val kx = hx; val ky = -THIGH * cos(thigh); val kz = -THIGH * sin(thigh)
            val ax = hx; val ay = ky - SHIN * cos(shin); val az = kz - SHIN * sin(shin)
            w.beam(hx, 0f, 0f, kx, ky, kz, 0.15f, 0.16f, SUIT)
            w.beam(kx, ky, kz, ax, ay, az, 0.12f, 0.13f, SUIT)
            w.boxC(ax, ay - 0.02f, az - 0.05f, 0.065f, 0.06f, 0.13f, TRIM)
        }

        // Torso with a teal belt, and the thruster pack on the back.
        w.beam(0f, 0.02f, 0f, 0f, 0.54f, 0f, 0.40f, 0.24f, SUIT)
        w.beam(0f, -0.02f, 0f, 0f, 0.07f, 0f, 0.42f, 0.26f, TRIM)
        w.boxC(0f, 0.31f, 0.19f, 0.16f, 0.20f, 0.08f, PACK)
        for (s in -1..1 step 2) {
            val nx = s * 0.085f
            w.boxC(nx, 0.09f, 0.20f, 0.045f, 0.05f, 0.05f, NOZZLE)
            if (flight > 0.05f) {
                val length = (0.45f + 0.14f * sin(flicker * 23f + s)) * flight
                w.beam(nx, 0.05f, 0.20f, nx, 0.05f - length * 0.55f, 0.21f, 0.075f, 0.075f, FLAME)
                w.beam(nx, 0.05f - length * 0.5f, 0.21f, nx, 0.05f - length, 0.22f, 0.04f, 0.04f, FLAME_TIP)
            }
        }

        // Arms: pumping with the stride on the ground, swept back along the body in the air.
        for (s in -1..1 step 2) {
            val p = stride + if (s > 0) PI.toFloat() else 0f
            val upper = mix(0.6f * sin(p) * run, 0.2f, flight)
            val fore = upper + mix(0.25f + 1.05f * run, 0.1f, flight)
            val sx = s * 0.25f
            val spread = s * 0.10f * flight
            val ex = sx + spread; val ey = SHOULDER_Y - UPPER_ARM * cos(upper); val ez = -UPPER_ARM * sin(upper)
            val wx = ex + spread; val wy = ey - FOREARM * cos(fore); val wz = ez - FOREARM * sin(fore)
            w.beam(sx, SHOULDER_Y, 0f, ex, ey, ez, 0.12f, 0.12f, SUIT)
            w.beam(ex, ey, ez, wx, wy, wz, 0.10f, 0.10f, SUIT)
            w.boxC(wx, wy, wz, 0.055f, 0.06f, 0.06f, TRIM)
        }

        // Helmet: a round shell, a dark visor at the front and a small crest on top.
        w.icosa(0f, 0.74f, -0.01f, 0.16f, 0.17f, 0.17f, HELMET)
        w.boxC(0f, 0.75f, -0.13f, 0.11f, 0.055f, 0.05f, VISOR)
        w.boxC(0f, 0.92f, 0.03f, 0.018f, 0.035f, 0.08f, TRIM)
    }

    companion object {
        /** Upper bound of vertices one frame of the runner needs (checked by tests). */
        const val MAX_VERTICES = 1100
        /** Thigh plus shin plus the boot's sole, so a straight leg just reaches the ground. */
        const val HIP_Y = 1.0f
        const val THIGH = 0.45f
        const val SHIN = 0.47f
        const val SHOULDER_Y = 0.47f
        const val UPPER_ARM = 0.27f
        const val FOREARM = 0.25f
        const val RUN_LEAN_DEGREES = 8f
        const val FLIGHT_LEAN_DEGREES = 66f
        private const val SHADOW_Y = 0.03f

        /** How far the runner has gone from running (0) to flying (1) at an altitude in metres. */
        fun flightFor(altitude: Float): Float {
            val t = (altitude / FULL_FLIGHT_ALTITUDE).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** By this height the runner is fully in the flying pose. */
        const val FULL_FLIGHT_ALTITUDE = 2.5f

        private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

        private val SUIT = MeshWriter.pack(0.90f, 0.91f, 0.93f, MeshWriter.OBJECT)
        private val TRIM = MeshWriter.pack(0.10f, 0.62f, 0.64f, MeshWriter.OBJECT)
        private val HELMET = MeshWriter.pack(0.95f, 0.95f, 0.96f, MeshWriter.OBJECT)
        private val VISOR = MeshWriter.pack(0.08f, 0.10f, 0.16f, MeshWriter.OBJECT)
        private val PACK = MeshWriter.pack(0.62f, 0.65f, 0.70f, MeshWriter.OBJECT)
        private val NOZZLE = MeshWriter.pack(0.20f, 0.21f, 0.24f, MeshWriter.OBJECT)
        private val FLAME = MeshWriter.pack(1.00f, 0.62f, 0.16f, MeshWriter.OBJECT)
        private val FLAME_TIP = MeshWriter.pack(1.00f, 0.90f, 0.50f, MeshWriter.OBJECT)
        private val SHADOW = MeshWriter.pack(0.42f, 0.30f, 0.22f)
    }
}
