package dev.soloton.roadwork.env.riverfront

import dev.soloton.roadwork.MeshWriter
import com.badlogic.gdx.math.Matrix4
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The jogger, rebuilt each frame from primitives into a [MeshWriter].
 *
 * An original design: a young boxer out on roadwork in a charcoal hoodie (hood down), navy shorts
 * over grey leggings, white hand wraps, short dark hair and lime-green trainers. Jogger-local
 * space: forward is -z, ground at y = 0. No allocation per frame.
 */
class JoggerModel {
    private val base = Matrix4()
    private val torso = Matrix4()
    private val tmp = Matrix4()
    private val ik = FloatArray(6)
    private val ft = FloatArray(2)
    private val pc = FloatArray(2)

    fun build(w: MeshWriter, t: Double, motion: JoggerMotion, x: Float, z: Float, heading: Float) {
        w.reset()
        val phase = motion.phase
        val speed = motion.speed
        base.idt().translate(x, 0f, z).rotate(0f, 1f, 0f, heading)
        w.transform.set(base)
        shadow(w)

        val bob = JoggerMotion.bob(phase, speed)
        val hipY = JoggerMotion.HIP_Y + bob
        val hipYaw = JoggerMotion.hipYaw(phase, speed)
        val glance = motion.glance(t)
        val guard = motion.punching(t)
        motion.punch(t, pc)

        // Legs, in a frame turned with the hips.
        tmp.set(base).rotate(0f, 1f, 0f, hipYaw)
        w.transform.set(tmp)
        for (s in -1..1 step 2) {
            val hx = s * 0.095f
            JoggerMotion.foot(phase, s, speed, ft)
            val fy = JoggerMotion.FOOT_Y + ft[1]
            val fz = -ft[0]
            JoggerMotion.twoBoneIk(hx, hipY, 0f, s * 0.11f, fy, fz, JoggerMotion.THIGH, JoggerMotion.SHIN, 0f, 0f, -1f, ik)
            // Grey leggings, navy shorts over the upper thigh.
            w.beam(hx, hipY, 0f, ik[0], ik[1], ik[2], 0.12f, 0.13f, LEGGINGS)
            val mx = hx + (ik[0] - hx) * 0.55f
            val my = hipY + (ik[1] - hipY) * 0.55f
            val mz = ik[2] * 0.55f
            w.beam(hx, hipY + 0.04f, 0f, mx, my, mz, 0.16f, 0.17f, SHORTS)
            w.beam(ik[0], ik[1], ik[2], ik[3], ik[4], ik[5], 0.10f, 0.10f, LEGGINGS)
            // Trainer: toes point forward and tip up a little when the foot is lifted.
            val ax = ik[3]; val ay = ik[4]; val az = ik[5]
            w.box(ax - 0.055f, ay - 0.07f, az - 0.17f, ax + 0.055f, ay - 0.045f, az + 0.07f, SOLE, bottom = true)
            w.box(ax - 0.05f, ay - 0.045f, az - 0.15f, ax + 0.05f, ay + 0.03f, az + 0.06f, SHOE)
        }
        w.boxC(0f, hipY + 0.01f, 0f, 0.17f, 0.08f, 0.11f, SHORTS)

        // Upper body: leans forward a little, counter-rotates against the hips, and twists when
        // glancing back over the left shoulder.
        val shoulderYaw = -hipYaw * 1.1f + 22f * glance
        torso.set(base).translate(0f, hipY, 0f).rotate(0f, 1f, 0f, shoulderYaw).rotate(1f, 0f, 0f, -7f + 3f * guard)
        w.transform.set(torso)
        // Hoodie body, a darker ribbed hem, the folded-down hood behind the neck.
        w.beam(0f, 0.02f, 0f, 0f, 0.50f, 0f, 0.38f, 0.23f, HOODIE)
        w.beam(0f, 0.0f, 0f, 0f, 0.07f, 0f, 0.39f, 0.24f, HOODIE_DARK)
        w.boxC(0f, 0.50f, 0.10f, 0.12f, 0.06f, 0.05f, HOODIE_DARK)
        w.boxC(0f, 0.47f, 0.14f, 0.10f, 0.08f, 0.03f, HOODIE_DARK)
        // Drawstrings at the front.
        w.beam(-0.04f, 0.50f, -0.118f, -0.045f, 0.36f, -0.12f, 0.015f, 0.01f, STRING)
        w.beam(0.04f, 0.50f, -0.118f, 0.045f, 0.36f, -0.12f, 0.015f, 0.01f, STRING)

        val shY = 0.45f
        for (s in -1..1 step 2) {
            val shx = s * 0.20f
            // Running arm: elbow bent about 90 degrees, swinging opposite to the legs.
            val swing = JoggerMotion.armSwing(phase, s) * (speed / 3f).coerceIn(0.3f, 1.2f)
            var tx = s * 0.15f
            var ty = shY - 0.30f + 0.07f * maxOf(0f, swing)
            var tz = -0.12f - 0.17f * swing
            var px = s * 0.3f; var py = -0.2f; var pz = 1f
            if (guard > 0f) {
                // Guard: wrapped fists up by the chin; the punching arm extends straight ahead.
                val ext = if (pc[1].toInt() == s) pc[0] else 0f
                val gx = s * 0.11f + (-s * 0.07f) * ext
                val gy = shY + 0.04f + 0.02f * ext
                val gz = -0.22f - 0.42f * ext
                tx += (gx - tx) * guard
                ty += (gy - ty) * guard
                tz += (gz - tz) * guard
                px += (s * 0.6f - px) * guard; py += (-1f - py) * guard; pz += (0.2f - pz) * guard
            }
            JoggerMotion.twoBoneIk(shx, shY, 0f, tx, ty, tz, JoggerMotion.UPPER_ARM, JoggerMotion.FOREARM, px, py, pz, ik)
            w.beam(shx, shY, 0f, ik[0], ik[1], ik[2], 0.11f, 0.11f, HOODIE)
            w.beam(ik[0], ik[1], ik[2], ik[3], ik[4], ik[5], 0.095f, 0.095f, HOODIE)
            // Wrapped hand: a white fist a touch bigger than the cuff.
            w.boxC(ik[3], ik[4], ik[5], 0.05f, 0.055f, 0.055f, WRAPS)
        }

        // Head, turned toward the rider when glancing back.
        tmp.set(torso).translate(0f, 0.52f, -0.01f).rotate(0f, 1f, 0f, motion.headYaw(t) - shoulderYaw * 0.4f * glance).rotate(1f, 0f, 0f, 6f)
        w.transform.set(tmp)
        head(w)
        w.transform.set(base)
    }

    private fun shadow(w: MeshWriter) {
        val n = 10
        val y = 0.012f
        for (i in 0 until n) {
            val a0 = (2 * PI * i / n).toFloat()
            val a1 = (2 * PI * (i + 1) / n).toFloat()
            // Ellipse under the jogger, nudged forward-right (the sun is behind and to the left).
            w.tri(0.05f, y, -0.12f,
                0.05f + 0.34f * cos(a0), y, -0.12f - 0.48f * sin(a0),
                0.05f + 0.34f * cos(a1), y, -0.12f - 0.48f * sin(a1), SHADOW)
        }
    }

    /** Head in its own frame: origin at the base of the neck, face toward -z. */
    private fun head(w: MeshWriter) {
        w.boxC(0f, 0.04f, 0f, 0.055f, 0.06f, 0.055f, SKIN)
        w.boxC(0f, 0.17f, -0.01f, 0.09f, 0.11f, 0.105f, SKIN)
        // Short dark hair: a cap over the top and back.
        w.box(-0.097f, 0.22f, -0.10f, 0.097f, 0.30f, 0.105f, HAIR)
        w.box(-0.097f, 0.10f, 0.02f, 0.097f, 0.22f, 0.105f, HAIR)
        // Ears, nose and eyes.
        w.boxC(-0.095f, 0.16f, 0.0f, 0.012f, 0.03f, 0.022f, SKIN)
        w.boxC(0.095f, 0.16f, 0.0f, 0.012f, 0.03f, 0.022f, SKIN)
        w.boxC(0f, 0.16f, -0.125f, 0.018f, 0.028f, 0.025f, SKIN)
        w.boxC(-0.04f, 0.195f, -0.117f, 0.016f, 0.01f, 0.004f, EYE)
        w.boxC(0.04f, 0.195f, -0.117f, 0.016f, 0.01f, 0.004f, EYE)
    }

    companion object {
        /** Upper bound of vertices one frame of the jogger needs (checked by tests). */
        const val MAX_VERTICES = 1600

        private val HOODIE = MeshWriter.pack(0.21f, 0.22f, 0.25f)
        private val HOODIE_DARK = MeshWriter.pack(0.15f, 0.16f, 0.18f)
        private val STRING = MeshWriter.pack(0.86f, 0.86f, 0.84f)
        private val SHORTS = MeshWriter.pack(0.10f, 0.15f, 0.36f)
        private val LEGGINGS = MeshWriter.pack(0.47f, 0.48f, 0.50f)
        private val WRAPS = MeshWriter.pack(0.96f, 0.96f, 0.94f)
        private val SHOE = MeshWriter.pack(0.60f, 0.88f, 0.14f)
        private val SOLE = MeshWriter.pack(0.96f, 0.96f, 0.96f)
        private val SKIN = MeshWriter.pack(0.56f, 0.38f, 0.27f)
        private val HAIR = MeshWriter.pack(0.07f, 0.06f, 0.06f)
        private val EYE = MeshWriter.pack(0.08f, 0.06f, 0.05f)
        private val SHADOW = MeshWriter.pack(0.20f, 0.12f, 0.10f)
    }
}
