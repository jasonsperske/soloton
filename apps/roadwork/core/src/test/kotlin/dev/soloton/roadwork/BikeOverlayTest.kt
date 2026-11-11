package dev.soloton.roadwork

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector3
import dev.soloton.roadwork.env.riverfront.JoggerMotion
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertTrue

class BikeOverlayTest {
    private val width = 1920f
    private val height = 1080f

    /**
     * Projects an eye-space point (camera at the origin looking down -z, vertical field of view
     * [fov]) to screen pixels with y up, like libGDX's PerspectiveCamera (whose update() needs
     * native code that unit tests do not load).
     */
    /** Into the eye space of a camera at the origin that looks down by [degrees]. */
    private fun pitchDown(p: Vector3, degrees: Float) {
        val a = Math.toRadians(degrees.toDouble())
        val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
        p.set(p.x, p.y * c - p.z * s, p.y * s + p.z * c)
    }

    private fun project(p: Vector3, fov: Float) {
        val f = (height / 2f) / tan(Math.toRadians(fov / 2.0)).toFloat()
        val d = -p.z
        p.set(width / 2f + f * p.x / d, height / 2f + f * p.y / d, d)
    }

    /**
     * Projects every triangle of the overlay at 1920x1080 over a full wheel and crank turn at
     * several speeds and head sways, and checks each one's screen bounding box: inside
     * [BikeOverlay.ALLOWED] and clear of every HUD panel and button.
     */
    @Test
    fun handlebarsStayInTheirStripAndClearOfTheHud() {
        val hud = HudLayout()
        hud.layout(1920, 1080)
        val w = MeshWriter(BikeOverlay.MAX_VERTICES)
        val overlay = BikeOverlay()
        val p = Vector3()
        val box = Rectangle()
        val bounds = Rectangle(Float.MAX_VALUE, Float.MAX_VALUE, 0f, 0f)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (speed in floatArrayOf(0f, 3f, 6f)) {
            for (i in 0 until 24) {
                val a = i * (2 * Math.PI / 24).toFloat()
                overlay.build(w, a * 1.7f, a, speed, RideState.sway(a, speed), RideState.bob(a, speed))
                assertTrue(w.vertexCount in 300..BikeOverlay.MAX_VERTICES, "${w.vertexCount} vertices")
                for (t in 0 until w.triangleCount) {
                    var bx0 = Float.MAX_VALUE; var by0 = Float.MAX_VALUE; var bx1 = -Float.MAX_VALUE; var by1 = -Float.MAX_VALUE
                    var visible = false
                    for (k in 0 until 3) {
                        val v = (t * 3 + k) * MeshWriter.STRIDE
                        p.set(w.floats[v], w.floats[v + 1], w.floats[v + 2])
                        assertTrue(p.z < -BikeOverlay.NEAR, "vertex behind the near plane")
                        pitchDown(p, BikeOverlay.PITCH_DEGREES)
                        project(p, BikeOverlay.FOV)
                        bx0 = minOf(bx0, p.x); by0 = minOf(by0, p.y); bx1 = maxOf(bx1, p.x); by1 = maxOf(by1, p.y)
                    }
                    // Clip to the screen: parts below the bottom edge are simply not drawn.
                    if (by1 < 0f || bx1 < 0f || bx0 > width) continue
                    by0 = maxOf(by0, 0f)
                    visible = true
                    box.set(bx0, by0, bx1 - bx0, by1 - by0)
                    assertTrue(
                        bx0 >= BikeOverlay.ALLOWED.x && bx1 <= BikeOverlay.ALLOWED.x + BikeOverlay.ALLOWED.width &&
                            by1 <= BikeOverlay.ALLOWED.y + BikeOverlay.ALLOWED.height,
                        "triangle $t at ($bx0..$bx1, $by0..$by1) leaves the allowed strip",
                    )
                    for (r in hud.all()) {
                        assertTrue(!box.overlaps(r), "triangle $t at ($bx0..$bx1, $by0..$by1) overlaps HUD rect $r")
                    }
                    if (visible) { minX = minOf(minX, bx0); minY = minOf(minY, by0); maxX = maxOf(maxX, bx1); maxY = maxOf(maxY, by1) }
                }
            }
        }
        bounds.set(minX, minY, maxX - minX, maxY - minY)
        println("handlebar overlay screen bounds at 1920x1080: $bounds, ${w.vertexCount} vertices")
        // It is really there: reaches the bottom edge (the wheel) and is wide enough to read as handlebars.
        assertTrue(minY <= 1f, "wheel reaches the bottom edge")
        assertTrue(maxX - minX > 450f, "handlebars span ${maxX - minX} px")
    }

    @Test
    fun overlayStaysBelowTheJoggerAtTheClosestGap() {
        // The jogger's feet at the minimum gap, seen from the rider's camera.
        // Into the rider camera's eye space: move to the eye, then undo the downward pitch.
        val pitch = kotlin.math.atan(RiderCamera.PITCH)
        val y = 0f - RiderCamera.EYE_HEIGHT
        val z = -JoggerMotion.MIN_GAP
        val c = kotlin.math.cos(pitch); val s = kotlin.math.sin(pitch)
        val feet = Vector3(JoggerMotion.lateral(0.0) - RiderCamera.X, y * c - z * s, y * s + z * c)
        project(feet, RiderCamera.FOV)
        val top = BikeOverlay.ALLOWED.y + BikeOverlay.ALLOWED.height
        assertTrue(feet.y > top, "jogger's feet at ${feet.y} px, overlay strip top at $top px")
    }
}
