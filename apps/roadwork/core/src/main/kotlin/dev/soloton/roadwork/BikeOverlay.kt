package dev.soloton.roadwork

import com.badlogic.gdx.math.Rectangle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The rider's own bike as seen from the saddle: a flat bar with a small blank head unit on the
 * stem, two gloved hands on the grips, and below them the fork crown and the top of the front tyre
 * running down to the bottom edge between the fork legs, its tread turning with the wheel.
 *
 * It is built in eye space (eye at the origin, y up, forward -z) and drawn after the world with
 * its own camera (looking down by [PITCH_DEGREES]) and a cleared depth buffer, so it never clips
 * into the scenery. Proportions are pulled forward from a real bike so that the bar, hands and
 * tyre all fit in a low strip at the bottom centre of the screen: [ALLOWED] at 1920x1080, clear of
 * the HUD panels and below the jogger. Rebuilt each frame; no allocation.
 */
class BikeOverlay {
    /**
     * [wheelAngle] turns the wheel (radians), [crank] and [speed] drive a small rocking of the
     * hands, and [swayX] / [bobY] are the rider's head movement, which the bike counters (the head
     * moves, the bike stays put underneath it).
     */
    fun build(w: MeshWriter, wheelAngle: Float, crank: Float, speed: Float, swayX: Float, bobY: Float) {
        w.reset()
        w.transform.idt().translate(-swayX * 0.5f, -bobY * 0.6f, 0f)
        wheel(w, wheelAngle)
        frame(w)
        hands(w, crank, speed)
    }

    private fun wheel(w: MeshWriter, angle: Float) {
        val cy = AXLE_Y
        val cz = AXLE_Z
        val r = WHEEL_R
        // The arc from the top of the tyre back toward the rider (the part on screen);
        // a = 0 at the top, positive forward (-z).
        val from = (-50.0 * PI / 180).toFloat()
        val to = (12.0 * PI / 180).toFloat()
        val n = 10
        for (i in 0 until n) {
            val a0 = from + (to - from) * i / n
            val a1 = from + (to - from) * (i + 1) / n
            w.beam(0f, cy + r * cos(a0), cz - r * sin(a0), 0f, cy + r * cos(a1), cz - r * sin(a1), TYRE_W, 0.04f, TYRE)
        }
        // Tread blocks on the crown of the tyre turn with the wheel (rolling forward moves the top
        // away from the rider), so the spin reads at any speed; every sixth one is pale.
        val marks = 30
        for (k in 0 until marks) {
            val a = RideState.wrap(-angle + k * (2 * PI / marks).toFloat())
            val aa = if (a > PI) a - (2 * PI).toFloat() else a
            if (aa < from + 0.03f || aa > to - 0.03f) continue
            val rr = r + 0.021f
            w.boxC(0f, cy + rr * cos(aa), cz - rr * sin(aa), TYRE_W * 0.42f, 0.004f, 0.012f, if (k % 6 == 0) TREAD_MARK else TREAD)
        }
    }

    private fun frame(w: MeshWriter) {
        // Fork legs either side of the tyre, from the crown down past the bottom of the screen.
        for (s in -1..1 step 2) {
            w.beam(s * 0.048f, CROWN_Y, CROWN_Z, s * 0.05f, AXLE_Y + 0.1f, AXLE_Z + 0.02f, 0.026f, 0.032f, FRAME)
        }
        w.boxC(0f, CROWN_Y + 0.006f, CROWN_Z, 0.066f, 0.018f, 0.032f, FRAME)
        // Steerer and headset up from the crown to the stem.
        w.beam(0f, CROWN_Y + 0.02f, CROWN_Z + 0.005f, 0f, STEERER_Y - 0.01f, STEERER_Z, 0.036f, 0.036f, BLACK)
        w.beam(0f, CROWN_Y + 0.02f, CROWN_Z + 0.005f, 0f, CROWN_Y + 0.05f, CROWN_Z + 0.02f, 0.046f, 0.046f, FRAME)
        // Stem forward to the bar clamp, with a small blank head unit on top (no text).
        w.beam(0f, STEERER_Y, STEERER_Z, 0f, BAR_Y, BAR_Z, 0.036f, 0.032f, BLACK)
        w.boxC(0f, BAR_Y, BAR_Z, 0.028f, 0.02f, 0.02f, BLACK)
        val uz = (STEERER_Z + BAR_Z) * 0.5f + 0.004f
        w.boxC(0f, BAR_Y + 0.022f, uz, 0.028f, 0.007f, 0.036f, BLACK)
        w.boxC(0f, BAR_Y + 0.0295f, uz, 0.022f, 0.001f, 0.028f, SCREEN)
        // Flat bar with a slight rise and backsweep, grips, bright end caps and brake levers.
        for (s in -1..1 step 2) {
            w.beam(0f, BAR_Y, BAR_Z, s * 0.10f, BAR_Y + 0.004f, BAR_Z + 0.002f, 0.024f, 0.024f, BLACK)
            w.beam(s * 0.10f, BAR_Y + 0.004f, BAR_Z + 0.002f, s * GRIP_IN, GRIP_Y, GRIP_Z, 0.024f, 0.024f, BLACK)
            w.beam(s * GRIP_IN, GRIP_Y, GRIP_Z, s * GRIP_OUT, GRIP_Y, GRIP_Z + 0.012f, 0.034f, 0.034f, GRIP)
            w.beam(s * GRIP_OUT, GRIP_Y, GRIP_Z + 0.012f, s * (GRIP_OUT + 0.012f), GRIP_Y, GRIP_Z + 0.013f, 0.036f, 0.036f, FRAME)
            w.beam(s * (GRIP_IN - 0.005f), GRIP_Y + 0.01f, GRIP_Z - 0.01f, s * (GRIP_IN + 0.07f), GRIP_Y - 0.004f, GRIP_Z - 0.035f, 0.012f, 0.008f, LEVER)
        }
    }

    private fun hands(w: MeshWriter, crank: Float, speed: Float) {
        val effort = (speed / 3f).coerceIn(0f, 1.2f)
        for (s in -1..1 step 2) {
            // Each hand rocks very slightly with its own side's pedal stroke.
            val push = 0.0025f * effort * sin(crank + if (s > 0) 0f else PI.toFloat())
            val gx = s * (GRIP_IN + GRIP_OUT) * 0.5f + s * 0.004f
            val gy = GRIP_Y + push
            val gz = GRIP_Z + 0.006f
            // Back of the glove over the grip, fingers curled over the front, a pale stripe across
            // the knuckles, and a thumb wrapped under the inner side.
            w.boxC(gx, gy + 0.022f, gz + 0.012f, 0.045f, 0.021f, 0.032f, GLOVE)
            w.boxC(gx, gy + 0.002f, gz - 0.024f, 0.044f, 0.026f, 0.012f, GLOVE)
            w.boxC(gx, gy + 0.0435f, gz - 0.006f, 0.04f, 0.002f, 0.007f, GLOVE_STRIPE)
            w.boxC(gx - s * 0.042f, gy - 0.004f, gz + 0.006f, 0.012f, 0.015f, 0.022f, GLOVE)
            // A short cuff at the wrist, angling back toward the rider.
            w.beam(gx + s * 0.002f, gy + 0.024f, gz + 0.04f, gx + s * 0.006f, gy + 0.03f, gz + 0.07f, 0.07f, 0.044f, CUFF)
        }
    }

    companion object {
        const val FOV = 60f
        const val NEAR = 0.05f
        const val FAR = 5f
        /** The overlay camera looks down by this much. */
        const val PITCH_DEGREES = 0f
        const val MAX_VERTICES = 3000

        const val WHEEL_R = 0.34f
        const val TYRE_W = 0.052f
        const val AXLE_Y = -0.805f
        const val AXLE_Z = -1.08f
        const val CROWN_Y = -0.448f
        const val CROWN_Z = -1.05f
        const val STEERER_Y = -0.312f
        const val STEERER_Z = -0.86f
        const val BAR_Y = -0.300f
        const val BAR_Z = -0.94f
        const val GRIP_Y = -0.288f
        const val GRIP_Z = -0.905f
        const val GRIP_IN = 0.17f
        const val GRIP_OUT = 0.265f

        /**
         * Where the overlay may draw on a 1920x1080 screen (y up from the bottom): a strip at the
         * bottom centre that stays below the jogger's feet.
         */
        val ALLOWED = Rectangle(560f, 0f, 800f, 330f)

        private val TYRE = MeshWriter.pack(0.10f, 0.10f, 0.11f)
        private val TREAD = MeshWriter.pack(0.20f, 0.20f, 0.21f)
        private val TREAD_MARK = MeshWriter.pack(0.80f, 0.80f, 0.78f)
        private val FRAME = MeshWriter.pack(0.88f, 0.42f, 0.12f)
        private val BLACK = MeshWriter.pack(0.12f, 0.12f, 0.13f)
        private val SCREEN = MeshWriter.pack(0.55f, 0.62f, 0.60f)
        private val GRIP = MeshWriter.pack(0.20f, 0.20f, 0.21f)
        private val LEVER = MeshWriter.pack(0.62f, 0.63f, 0.65f)
        private val GLOVE = MeshWriter.pack(0.17f, 0.17f, 0.19f)
        private val GLOVE_STRIPE = MeshWriter.pack(0.10f, 0.62f, 0.66f)
        private val CUFF = MeshWriter.pack(0.24f, 0.25f, 0.27f)
    }
}
