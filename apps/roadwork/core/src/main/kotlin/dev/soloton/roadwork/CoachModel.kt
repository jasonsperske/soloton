package dev.soloton.roadwork

import com.badlogic.gdx.math.Matrix4
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The coach on his bicycle, rebuilt each frame from primitives into a [MeshWriter].
 *
 * An original design: an older coach in a teal windbreaker with a white towel round his neck, a
 * rust-orange knit hat and a grey beard, riding an upright mustard-yellow town bike with a rear
 * rack. Bike-local space: forward is -z, ground at y = 0. No allocation per frame.
 */
class CoachModel {
    private val base = Matrix4()
    private val tmp = Matrix4()
    private val ik = FloatArray(6)
    private val pd = FloatArray(2)

    fun build(w: MeshWriter, t: Double, motion: CoachMotion, x: Float, z: Float) {
        w.reset()
        val crank = motion.crankAngle
        val steer = CoachMotion.steerDegrees(t)
        val lean = 1.2f * sin(crank)

        // Soft contact shadow on the path (not leaning with the bike).
        w.transform.idt().translate(x, 0f, z).rotate(0f, 1f, 0f, steer)
        shadow(w)

        base.idt().translate(x, 0f, z).rotate(0f, 1f, 0f, steer).rotate(0f, 0f, 1f, lean)
        w.transform.set(base)
        bike(w, motion.wheelAngle, crank)
        rider(w, t, crank)
    }

    private fun shadow(w: MeshWriter) {
        val n = 10
        val y = 0.015f
        for (i in 0 until n) {
            val a0 = (2 * PI * i / n).toFloat()
            val a1 = (2 * PI * (i + 1) / n).toFloat()
            // Ellipse 0.45 wide, 1.5 long, offset a little behind (sun is ahead).
            w.tri(0f, y, 0.15f,
                0.32f * cos(a0), y, 0.15f - 0.95f * sin(a0),
                0.32f * cos(a1), y, 0.15f - 0.95f * sin(a1), SHADOW)
        }
    }

    private fun bike(w: MeshWriter, wheel: Float, crank: Float) {
        wheel(w, REAR_Z, wheel)
        wheel(w, FRONT_Z, wheel)
        val f = FRAME
        val t = 0.05f
        // Frame tubes.
        w.beam(0f, BB_Y, BB_Z, 0f, 0.88f, 0.22f, t, t, f) // seat tube
        w.beam(0f, 0.84f, 0.21f, 0f, 0.86f, -0.37f, t, t, f) // top tube
        w.beam(0f, BB_Y, BB_Z, 0f, 0.72f, -0.40f, 0.055f, 0.055f, f) // down tube
        w.beam(0f, 0.92f, -0.37f, 0f, 0.70f, -0.42f, 0.06f, 0.06f, f) // head tube
        for (s in -1..1 step 2) {
            val sx = s * 0.05f
            w.beam(sx, BB_Y, BB_Z, sx, WHEEL_R, REAR_Z, 0.03f, 0.03f, f) // chain stay
            w.beam(sx * 0.6f, 0.84f, 0.21f, sx, WHEEL_R, REAR_Z, 0.03f, 0.03f, f) // seat stay
            w.beam(sx * 0.8f, 0.72f, -0.42f, sx, WHEEL_R, FRONT_Z, 0.03f, 0.03f, f) // fork
            // Mudguard stays and rack legs.
            w.beam(sx * 1.2f, WHEEL_R, REAR_Z, sx * 1.4f, 0.80f, 0.52f, 0.02f, 0.02f, METAL)
        }
        // Rear rack with a small canvas bag, and a red reflector facing back.
        w.box(-0.09f, 0.79f, 0.30f, 0.09f, 0.82f, 0.74f, METAL)
        w.box(-0.11f, 0.82f, 0.38f, 0.11f, 0.98f, 0.68f, BAG)
        w.box(-0.05f, 0.70f, 0.74f, 0.05f, 0.77f, 0.76f, REFLECTOR)
        // Mudguard arc over the rear wheel.
        arc(w, REAR_Z, WHEEL_R + 0.05f, 0.4f, 2.7f, 0.06f, FRAME_DARK)
        arc(w, FRONT_Z, WHEEL_R + 0.05f, -0.4f, 0.9f, 0.06f, FRAME_DARK)
        // Seat post and saddle.
        w.beam(0f, 0.86f, 0.22f, 0f, 0.96f, 0.25f, 0.03f, 0.03f, METAL)
        w.box(-0.08f, 0.96f, 0.16f, 0.08f, 1.02f, 0.36f, SADDLE, bottom = true)
        // Stem and swept-back handlebar with dark grips.
        w.beam(0f, 0.92f, -0.37f, 0f, 1.00f, -0.40f, 0.035f, 0.035f, METAL)
        w.beam(-0.20f, 1.00f, -0.36f, 0.20f, 1.00f, -0.36f, 0.03f, 0.03f, METAL)
        for (s in -1..1 step 2) {
            w.beam(s * 0.20f, 1.0f, -0.36f, s * 0.27f, 1.0f, -0.28f, 0.03f, 0.03f, METAL)
            w.beam(s * 0.27f, 1.0f, -0.28f, s * 0.29f, 1.0f, -0.20f, 0.04f, 0.04f, GRIP)
        }
        // Chainring and cranks.
        val ringR = 0.10f
        for (i in 0 until 10) {
            val a0 = (2 * PI * i / 10).toFloat()
            val a1 = (2 * PI * (i + 1) / 10).toFloat()
            w.beam(0.07f, BB_Y - ringR * cos(a0), BB_Z + ringR * sin(a0), 0.07f, BB_Y - ringR * cos(a1), BB_Z + ringR * sin(a1), 0.015f, 0.02f, METAL)
        }
        for (s in -1..1 step 2) {
            val a = if (s > 0) crank else crank + PI.toFloat()
            CoachMotion.pedal(a, pd)
            val px = s * 0.09f
            w.beam(px, BB_Y, BB_Z, px, BB_Y + pd[1], BB_Z + pd[0], 0.025f, 0.035f, METAL)
            w.boxC(px + s * 0.05f, BB_Y + pd[1], BB_Z + pd[0], 0.05f, 0.015f, 0.04f, GRIP)
        }
    }

    private fun arc(w: MeshWriter, cz: Float, r: Float, from: Float, to: Float, width: Float, color: Float) {
        val n = 6
        for (i in 0 until n) {
            val a0 = from + (to - from) * i / n
            val a1 = from + (to - from) * (i + 1) / n
            w.beam(0f, WHEEL_R + r * cos(a0), cz + r * sin(a0), 0f, WHEEL_R + r * cos(a1), cz + r * sin(a1), width, 0.012f, color)
        }
    }

    private fun wheel(w: MeshWriter, cz: Float, angle: Float) {
        val n = 14
        val r = WHEEL_R - 0.02f
        for (i in 0 until n) {
            val a0 = (2 * PI * i / n).toFloat()
            val a1 = (2 * PI * (i + 1) / n).toFloat()
            w.beam(0f, WHEEL_R + r * cos(a0), cz + r * sin(a0), 0f, WHEEL_R + r * cos(a1), cz + r * sin(a1), 0.045f, 0.045f, TYRE)
            val rr = r - 0.035f
            w.beam(0f, WHEEL_R + rr * cos(a0), cz + rr * sin(a0), 0f, WHEEL_R + rr * cos(a1), cz + rr * sin(a1), 0.025f, 0.02f, RIM)
        }
        // Spokes turn with the wheel: rolling forward (-z) turns the top of the wheel forward.
        val rs = r - 0.05f
        for (k in 0 until 3) {
            val a = -angle + k * (PI / 3).toFloat()
            val dz = rs * sin(a)
            val dy = rs * cos(a)
            w.beam(0f, WHEEL_R - dy, cz - dz, 0f, WHEEL_R + dy, cz + dz, 0.016f, 0.016f, SPOKE)
        }
        // A bright reflector clipped to one spoke makes the spin easy to read.
        val ra = -angle
        w.boxC(0f, WHEEL_R + 0.2f * cos(ra), cz + 0.2f * sin(ra), 0.03f, 0.04f, 0.04f, REFLECTOR_AMBER)
        w.boxC(0f, WHEEL_R, cz, 0.05f, 0.04f, 0.04f, METAL)
    }

    private fun rider(w: MeshWriter, t: Double, crank: Float) {
        val roll = 0.045f * sin(crank) // body sway with each pedal stroke, radians
        val bob = 0.012f * cos(2f * crank)
        val pelvisY = 1.04f + bob
        val pelvisZ = 0.26f
        // Upper body positions rotated about the pelvis by the sway roll.
        val neckY = pelvisY + 0.50f
        val neckZ = 0.0f
        val neckX = -sin(roll) * 0.50f
        val sx = cos(roll)
        val shoulderY = pelvisY + 0.46f
        val shoulderZ = 0.02f

        // Legs: thigh from hip to knee, shin from knee to ankle above the pedal.
        for (s in -1..1 step 2) {
            val hx = s * 0.10f
            val a = if (s > 0) crank else crank + PI.toFloat()
            CoachMotion.pedal(a, pd)
            val fx = s * 0.13f
            val fy = BB_Y + pd[1] + 0.07f
            val fz = BB_Z + pd[0] + 0.02f
            CoachMotion.twoBoneIk(hx, pelvisY - 0.04f, pelvisZ, fx, fy, fz, THIGH, SHIN, s * 0.15f, 0.2f, -1f, ik)
            w.beam(hx, pelvisY - 0.04f, pelvisZ, ik[0], ik[1], ik[2], 0.15f, 0.15f, TROUSERS)
            w.beam(ik[0], ik[1], ik[2], ik[3], ik[4], ik[5], 0.11f, 0.11f, TROUSERS)
            // Shoe along the pedal.
            w.box(ik[3] - 0.05f, ik[4] - 0.08f, ik[5] - 0.17f, ik[3] + 0.05f, ik[4] - 0.0f, ik[5] + 0.08f, SHOE, bottom = true)
        }

        // Pelvis and torso (windbreaker).
        w.boxC(0f, pelvisY, pelvisZ, 0.17f, 0.09f, 0.12f, TROUSERS)
        w.beam(0f, pelvisY - 0.02f, pelvisZ, neckX, neckY - 0.06f, neckZ + 0.04f, 0.38f, 0.24f, JACKET)
        // Lighter band across the back of the jacket and a towel round the neck.
        w.beam(0f, pelvisY + 0.10f, pelvisZ - 0.04f + 0.02f, neckX * 0.3f, pelvisY + 0.17f, pelvisZ - 0.07f, 0.39f, 0.245f, JACKET_BAND)
        w.beam(neckX - 0.17f, neckY - 0.04f, neckZ + 0.06f, neckX + 0.17f, neckY - 0.04f, neckZ + 0.06f, 0.09f, 0.10f, TOWEL)

        // Arms: shoulder -> elbow -> hand on the grip, or raised in a wave.
        val wave = CoachMotion.wave(t)
        for (s in -1..1 step 2) {
            val shx = s * 0.21f * sx + neckX * 0.9f
            val shy = shoulderY + s * sin(roll) * 0.21f
            var tx = s * 0.29f
            var ty = 1.02f
            var tz = -0.22f
            var poleX = s * 1f; var poleY = -0.4f; var poleZ = 0.6f
            if (s > 0 && wave > 0f) {
                val sway = 0.13f * sin((t * 7.5).toFloat())
                tx += (shx + 0.36f + sway - tx) * wave
                ty += (shy + 0.42f - ty) * wave
                tz += (shoulderZ + 0.08f - tz) * wave
                poleX = 1f; poleY = -1f + 0.6f * (1f - wave); poleZ = 0.6f * (1f - wave)
            }
            CoachMotion.twoBoneIk(shx, shy, shoulderZ, tx, ty, tz, UPPER_ARM, FOREARM, poleX, poleY, poleZ, ik)
            w.beam(shx, shy, shoulderZ, ik[0], ik[1], ik[2], 0.12f, 0.12f, JACKET)
            w.beam(ik[0], ik[1], ik[2], ik[3], ik[4], ik[5], 0.10f, 0.10f, JACKET)
            w.boxC(ik[3], ik[4], ik[5], 0.045f, 0.05f, 0.045f, SKIN)
        }

        // Head, turned toward the runner when glancing back or waving.
        tmp.set(base).translate(neckX, neckY, neckZ).rotate(0f, 1f, 0f, -CoachMotion.headYaw(t)).rotate(1f, 0f, 0f, -8f)
        w.transform.set(tmp)
        head(w)
        w.transform.set(base)
    }

    /** Head in its own frame: origin at the neck, face toward -z. */
    private fun head(w: MeshWriter) {
        w.boxC(0f, 0.04f, 0f, 0.06f, 0.06f, 0.06f, SKIN)
        w.boxC(0f, 0.17f, -0.02f, 0.095f, 0.115f, 0.11f, SKIN)
        // Grey hair at the back and sides under the hat.
        w.box(-0.1f, 0.12f, 0.0f, 0.1f, 0.24f, 0.095f, HAIR)
        // Beard and moustache.
        w.box(-0.09f, 0.05f, -0.135f, 0.09f, 0.15f, -0.06f, HAIR, bottom = true)
        w.boxC(0f, 0.17f, -0.135f, 0.022f, 0.03f, 0.03f, SKIN) // nose
        w.boxC(-0.045f, 0.205f, -0.131f, 0.017f, 0.012f, 0.004f, EYE)
        w.boxC(0.045f, 0.205f, -0.131f, 0.017f, 0.012f, 0.004f, EYE)
        // Rust-orange knit hat with a turned-up brim and a bobble.
        w.prism(0f, 0.22f, -0.02f, 0.112f, 0.112f, 0.05f, 9, HAT_BRIM)
        w.prism(0f, 0.27f, -0.02f, 0.105f, 0.05f, 0.09f, 9, HAT)
        w.icosa(0f, 0.38f, -0.02f, 0.04f, 0.04f, 0.04f, HAT_BRIM)
    }

    companion object {
        const val WHEEL_R = CoachMotion.WHEEL_RADIUS
        const val REAR_Z = 0.52f
        const val FRONT_Z = -0.50f
        const val BB_Y = 0.29f
        const val BB_Z = 0.08f
        const val THIGH = 0.47f
        const val SHIN = 0.46f
        const val UPPER_ARM = 0.34f
        const val FOREARM = 0.33f

        /** Upper bound of vertices one frame of the coach needs (checked by tests). */
        const val MAX_VERTICES = 6000

        private val FRAME = MeshWriter.pack(0.86f, 0.66f, 0.18f)
        private val FRAME_DARK = MeshWriter.pack(0.20f, 0.22f, 0.24f)
        private val METAL = MeshWriter.pack(0.62f, 0.63f, 0.65f)
        private val TYRE = MeshWriter.pack(0.08f, 0.08f, 0.09f)
        private val RIM = MeshWriter.pack(0.72f, 0.73f, 0.75f)
        private val SPOKE = MeshWriter.pack(0.80f, 0.80f, 0.82f)
        private val GRIP = MeshWriter.pack(0.15f, 0.12f, 0.10f)
        private val SADDLE = MeshWriter.pack(0.30f, 0.20f, 0.13f)
        private val BAG = MeshWriter.pack(0.42f, 0.40f, 0.30f)
        private val REFLECTOR = MeshWriter.pack(0.85f, 0.10f, 0.08f, emissive = 0.6f)
        private val REFLECTOR_AMBER = MeshWriter.pack(1f, 0.62f, 0.12f, emissive = 0.5f)
        private val JACKET = MeshWriter.pack(0.10f, 0.45f, 0.52f)
        private val JACKET_BAND = MeshWriter.pack(0.86f, 0.88f, 0.84f)
        private val TOWEL = MeshWriter.pack(0.95f, 0.95f, 0.92f)
        private val TROUSERS = MeshWriter.pack(0.19f, 0.20f, 0.24f)
        private val SHOE = MeshWriter.pack(0.86f, 0.86f, 0.84f)
        private val SKIN = MeshWriter.pack(0.66f, 0.47f, 0.36f)
        private val HAIR = MeshWriter.pack(0.80f, 0.79f, 0.76f)
        private val EYE = MeshWriter.pack(0.10f, 0.08f, 0.07f)
        private val HAT = MeshWriter.pack(0.80f, 0.33f, 0.14f)
        private val HAT_BRIM = MeshWriter.pack(0.70f, 0.27f, 0.11f)
        private val SHADOW = MeshWriter.pack(0.29f, 0.21f, 0.19f)
    }
}
