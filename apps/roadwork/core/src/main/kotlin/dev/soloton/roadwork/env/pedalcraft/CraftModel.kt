package dev.soloton.roadwork.env.pedalcraft

import dev.soloton.roadwork.MeshWriter
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The three pedal-powered craft, rebuilt each frame from primitives into a [MeshWriter]. All are
 * original designs sharing one pilot (blue flight suit, yellow helmet) whose legs turn the cranks
 * with the rider's, and one two-bladed propeller that spins with them.
 *
 * - **Pedal glider:** a teal and yellow delta wing over a three-wheeled seat frame, propeller behind.
 * - **Pedal paramotor:** an orange and cream arched canopy on lines above a seat with a caged propeller.
 * - **Pedal plane:** a high-winged white ultralight with blue wing tips, a tail boom and a nose propeller.
 *
 * Craft space: forward is -z, right is +x, the pilot's seat is at the origin. No allocation per frame.
 */
object CraftModel {
    /** Upper bound of vertices one frame of any craft needs (checked by tests). */
    const val MAX_VERTICES = 2400
    const val SHADOW_VERTICES = 36

    private val SUIT = MeshWriter.pack(0.16f, 0.34f, 0.70f)
    private val HELMET = MeshWriter.pack(0.98f, 0.82f, 0.16f)
    private val SKIN = MeshWriter.pack(0.86f, 0.66f, 0.52f)
    private val FRAME = MeshWriter.pack(0.72f, 0.74f, 0.78f)
    private val DARK = MeshWriter.pack(0.16f, 0.17f, 0.20f)
    private val BLADE = MeshWriter.pack(0.92f, 0.90f, 0.84f)
    private val TEAL = MeshWriter.pack(0.08f, 0.62f, 0.62f)
    private val YELLOW = MeshWriter.pack(0.98f, 0.84f, 0.22f)
    private val ORANGE = MeshWriter.pack(0.96f, 0.48f, 0.12f)
    private val CREAM = MeshWriter.pack(0.97f, 0.93f, 0.82f)
    private val WHITE = MeshWriter.pack(0.95f, 0.95f, 0.96f)
    private val BLUE = MeshWriter.pack(0.14f, 0.36f, 0.78f)
    private val SHADOW = MeshWriter.pack(0.17f, 0.24f, 0.21f)

    /**
     * @param x,y,z the seat's position; [dirX], [dirZ] the direction of travel on the map
     * @param crank the rider's crank angle in radians
     */
    fun build(
        w: MeshWriter, craft: Craft,
        x: Float, y: Float, z: Float, dirX: Float, dirZ: Float,
        bankDegrees: Float, pitchDegrees: Float, crank: Float,
    ) {
        w.reset()
        val yaw = Math.toDegrees(atan2(-dirX, -dirZ).toDouble()).toFloat()
        w.transform.idt().translate(x, y, z)
            .rotate(0f, 1f, 0f, yaw)
            .rotate(1f, 0f, 0f, pitchDegrees)
            .rotate(0f, 0f, 1f, -bankDegrees)
        val spin = crank * PROPELLER_TURNS
        when (craft) {
            Craft.GLIDER -> glider(w, crank, spin)
            Craft.PARAMOTOR -> paramotor(w, crank, spin)
            Craft.PLANE -> plane(w, spin)
        }
        w.transform.idt()
    }

    /** A dark patch on the ground or sea under the craft, [groundY] high, smaller the higher the craft is above it. */
    fun buildShadow(w: MeshWriter, craft: Craft, x: Float, groundY: Float, z: Float, heightAbove: Float) {
        w.reset()
        val size = (if (craft == Craft.PLANE) 2.6f else 1.7f) / (1f + heightAbove * 0.01f)
        val n = SHADOW_VERTICES / 3
        for (i in 0 until n) {
            val a0 = (2 * PI * i / n).toFloat()
            val a1 = (2 * PI * (i + 1) / n).toFloat()
            w.tri(x, groundY, z, x + size * cos(a1), groundY, z + size * sin(a1), x + size * cos(a0), groundY, z + size * sin(a0), SHADOW)
        }
    }

    /** The pilot, seated and leaning back, pedalling a crank ahead of the seat. */
    private fun pilot(w: MeshWriter, crank: Float, legs: Boolean) {
        w.beam(0f, 0.05f, 0.05f, 0f, 0.62f, 0.26f, 0.40f, 0.24f, SUIT)
        w.boxC(0f, 0.70f, 0.28f, 0.06f, 0.06f, 0.06f, SKIN)
        w.icosa(0f, 0.86f, 0.28f, 0.15f, 0.16f, 0.17f, HELMET)
        for (s in -1..1 step 2) {
            // Arms forward to the control bar.
            w.beam(s * 0.22f, 0.52f, 0.20f, s * 0.26f, 0.42f, -0.30f, 0.10f, 0.10f, SUIT)
            if (!legs) continue
            val a = crank + if (s > 0) 0f else PI.toFloat()
            val px = s * 0.12f
            val py = CRANK_Y + CRANK_RADIUS * sin(a)
            val pz = CRANK_Z - CRANK_RADIUS * cos(a)
            val kx = s * 0.13f
            val ky = (0.10f + py) / 2f + 0.22f
            val kz = (0.0f + pz) / 2f
            w.beam(s * 0.11f, 0.10f, 0f, kx, ky, kz, 0.14f, 0.14f, SUIT)
            w.beam(kx, ky, kz, px, py, pz, 0.11f, 0.11f, SUIT)
            w.boxC(px, py, pz - 0.04f, 0.05f, 0.03f, 0.10f, DARK)
        }
        if (legs) w.boxC(0f, CRANK_Y, CRANK_Z, 0.03f, 0.06f, 0.06f, DARK)
    }

    /** Two blades turning about the fore-aft axis through (0, [y], [z]). */
    private fun propeller(w: MeshWriter, y: Float, z: Float, radius: Float, spin: Float) {
        val c = cos(spin) * radius
        val s = sin(spin) * radius
        w.beam(-c, y - s, z, c, y + s, z, 0.16f, 0.05f, BLADE)
        w.boxC(0f, y, z, 0.08f, 0.08f, 0.10f, DARK)
    }

    /**
     * A triangle of fabric. It is written once, wound so its normal points up, and the scene draws
     * the craft without culling: seen from below it is then lit as from above, the way sunlight
     * glows through a sail, instead of going dark.
     */
    private fun sail(w: MeshWriter, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, color: Float) {
        val up = (bz - az) * (cx - ax) - (bx - ax) * (cz - az)
        if (up >= 0f) w.tri(ax, ay, az, bx, by, bz, cx, cy, cz, color) else w.tri(ax, ay, az, cx, cy, cz, bx, by, bz, color)
    }

    private fun seatFrame(w: MeshWriter) {
        w.beam(0f, -0.15f, 0.45f, 0f, 0.0f, -1.1f, 0.08f, 0.08f, FRAME)
        w.beam(-0.65f, -0.55f, 0.35f, 0.65f, -0.55f, 0.35f, 0.07f, 0.07f, FRAME)
        w.beam(0f, -0.15f, 0.35f, 0f, -0.55f, 0.35f, 0.07f, 0.07f, FRAME)
        w.beam(0f, 0f, -1.1f, 0f, -0.55f, -1.2f, 0.07f, 0.07f, FRAME)
        w.boxC(-0.65f, -0.68f, 0.35f, 0.05f, 0.20f, 0.20f, DARK)
        w.boxC(0.65f, -0.68f, 0.35f, 0.05f, 0.20f, 0.20f, DARK)
        w.boxC(0f, -0.68f, -1.2f, 0.05f, 0.20f, 0.20f, DARK)
    }

    private fun glider(w: MeshWriter, crank: Float, spin: Float) {
        seatFrame(w)
        pilot(w, crank, legs = true)
        // The delta wing: yellow inner panels, teal outer panels, swept back from the nose.
        val wy = 2.0f
        sail(w, 0f, wy + 0.25f, -1.9f, -2.3f, wy, 0.4f, 0f, wy + 0.1f, 1.5f, YELLOW)
        sail(w, 0f, wy + 0.25f, -1.9f, 0f, wy + 0.1f, 1.5f, 2.3f, wy, 0.4f, YELLOW)
        sail(w, 0f, wy + 0.25f, -1.9f, -5.0f, wy - 0.15f, 1.6f, -2.3f, wy, 0.4f, TEAL)
        sail(w, 0f, wy + 0.25f, -1.9f, 2.3f, wy, 0.4f, 5.0f, wy - 0.15f, 1.6f, TEAL)
        sail(w, -2.3f, wy, 0.4f, -5.0f, wy - 0.15f, 1.6f, 0f, wy + 0.1f, 1.5f, TEAL)
        sail(w, 2.3f, wy, 0.4f, 0f, wy + 0.1f, 1.5f, 5.0f, wy - 0.15f, 1.6f, TEAL)
        // Keel, the mast up from the seat and the two down-tubes to the control bar.
        w.beam(0f, wy + 0.27f, -1.9f, 0f, wy + 0.12f, 1.9f, 0.07f, 0.07f, FRAME)
        w.beam(0f, 0.55f, 0.42f, 0f, wy + 0.12f, 0.5f, 0.08f, 0.08f, FRAME)
        w.beam(-0.55f, 0.42f, -0.32f, 0f, wy + 0.2f, -0.6f, 0.05f, 0.05f, FRAME)
        w.beam(0.55f, 0.42f, -0.32f, 0f, wy + 0.2f, -0.6f, 0.05f, 0.05f, FRAME)
        w.beam(-0.55f, 0.42f, -0.32f, 0.55f, 0.42f, -0.32f, 0.05f, 0.05f, FRAME)
        propeller(w, 0.55f, 0.95f, 0.75f, spin)
    }

    private fun paramotor(w: MeshWriter, crank: Float, spin: Float) {
        seatFrame(w)
        pilot(w, crank, legs = true)
        // The canopy: an arch of panels high over the pilot, orange and cream by turns.
        val radius = 6.2f
        val top = 7.4f
        val step = CANOPY_SPAN / CANOPY_PANELS
        for (i in 0 until CANOPY_PANELS) {
            val a0 = -CANOPY_SPAN / 2f + i * step
            val a1 = a0 + step
            val x0 = radius * sin(a0); val y0 = top - radius * (1f - cos(a0))
            val x1 = radius * sin(a1); val y1 = top - radius * (1f - cos(a1))
            val c = if (i % 2 == 0) ORANGE else CREAM
            sail(w, x0, y0, -1.5f, x1, y1, -1.5f, x1, y1 - 0.12f, 1.3f, c)
            sail(w, x0, y0, -1.5f, x1, y1 - 0.12f, 1.3f, x0, y0 - 0.12f, 1.3f, c)
        }
        // Lines from the pilot's shoulders up to the canopy.
        for (s in -1..1 step 2) {
            for (a in floatArrayOf(0.25f, 0.62f)) {
                val lx = s * radius * sin(a)
                val ly = top - radius * (1f - cos(a)) - 0.06f
                w.beam(s * 0.30f, 0.75f, 0.15f, lx, ly, -0.2f, 0.03f, 0.03f, DARK)
            }
        }
        // The propeller cage behind the seat.
        val cage = 0.95f
        val spokes = 8
        for (i in 0 until spokes) {
            val a0 = (2 * PI * i / spokes).toFloat()
            val a1 = (2 * PI * (i + 1) / spokes).toFloat()
            w.beam(cage * cos(a0), 0.45f + cage * sin(a0), 0.85f, cage * cos(a1), 0.45f + cage * sin(a1), 0.85f, 0.05f, 0.05f, FRAME)
        }
        propeller(w, 0.45f, 0.95f, 0.8f, spin)
    }

    private fun plane(w: MeshWriter, spin: Float) {
        // Cabin pod, open at the top, with the pilot's head and shoulders showing.
        w.boxC(0f, 0.05f, -0.45f, 0.38f, 0.42f, 1.25f, WHITE)
        w.boxC(0f, 0.05f, -1.85f, 0.26f, 0.30f, 0.20f, BLUE)
        pilot(w, 0f, legs = false)
        // High wing on struts: white with blue tips.
        w.box(-3.6f, 1.22f, -0.75f, 3.6f, 1.36f, 0.55f, WHITE, bottom = true)
        w.box(-5.6f, 1.24f, -0.65f, -3.6f, 1.36f, 0.45f, BLUE, bottom = true)
        w.box(3.6f, 1.24f, -0.65f, 5.6f, 1.36f, 0.45f, BLUE, bottom = true)
        for (s in -1..1 step 2) {
            w.beam(s * 0.38f, -0.2f, -0.3f, s * 2.4f, 1.24f, -0.1f, 0.06f, 0.06f, FRAME)
            w.beam(s * 0.3f, 0.47f, -1.0f, s * 0.3f, 1.24f, -0.6f, 0.06f, 0.06f, FRAME)
            w.beam(s * 0.3f, 0.47f, 0.6f, s * 0.3f, 1.24f, 0.4f, 0.06f, 0.06f, FRAME)
        }
        // Tail boom, tailplane and fin.
        w.beam(0f, 0.25f, 0.7f, 0f, 0.55f, 4.2f, 0.16f, 0.18f, WHITE)
        w.box(-1.6f, 0.56f, 3.5f, 1.6f, 0.64f, 4.4f, WHITE, bottom = true)
        w.box(-0.05f, 0.58f, 3.6f, 0.05f, 1.55f, 4.4f, BLUE, bottom = true)
        // Wheels.
        w.beam(-0.9f, -0.62f, -0.2f, 0.9f, -0.62f, -0.2f, 0.06f, 0.06f, FRAME)
        w.boxC(-0.9f, -0.62f, -0.2f, 0.06f, 0.22f, 0.22f, DARK)
        w.boxC(0.9f, -0.62f, -0.2f, 0.06f, 0.22f, 0.22f, DARK)
        w.boxC(0f, -0.66f, -1.6f, 0.05f, 0.16f, 0.16f, DARK)
        propeller(w, 0.05f, -2.12f, 0.95f, spin)
    }

    /** Propeller turns per turn of the cranks. */
    const val PROPELLER_TURNS = 5f
    private const val CRANK_Y = 0.12f
    private const val CRANK_Z = -0.78f
    private const val CRANK_RADIUS = 0.17f
    private const val CANOPY_PANELS = 9
    private const val CANOPY_SPAN = 1.9f
}
