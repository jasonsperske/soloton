package dev.soloton.roadwork

import com.badlogic.gdx.graphics.PerspectiveCamera
import kotlin.math.cos
import kotlin.math.sin

/**
 * The rider's eyes: where the camera sits on the bike and how it rocks with the pedalling. Shared
 * by every environment so the view from the saddle, and the handlebars drawn under it, feel the
 * same everywhere. The rider is at the origin looking down -z; scenery moves past.
 */
object RiderCamera {
    const val FOV = 60f
    const val X = -0.15f
    const val EYE_HEIGHT = 1.55f
    /** Forward lean: the view tips down a little, as from a bike saddle. */
    const val PITCH = 0.10f
    const val YAW = 0.0f

    /** Sets [camera]'s position, direction and up for [ride]. The caller calls `update()`. */
    fun place(camera: PerspectiveCamera, ride: RideState) {
        val crank = ride.crankAngle
        val speed = ride.speed
        val sway = RideState.sway(crank, speed)
        val bob = RideState.bob(crank, speed)
        val roll = Math.toRadians(RideState.roll(crank, speed).toDouble()).toFloat()
        camera.position.set(X + sway, EYE_HEIGHT + bob, 0f)
        camera.direction.set(sway * 0.08f + YAW, -PITCH, -1f).nor()
        camera.up.set(sin(roll), cos(roll), 0f)
        camera.normalizeUp()
    }
}
