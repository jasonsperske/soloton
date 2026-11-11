package dev.soloton.roadwork.env.skylift

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector3
import dev.soloton.roadwork.SceneLight
import kotlin.math.cos
import kotlin.math.sin

/**
 * The mesa's light: a teal sky warming to amber at the horizon, with the sun behind and to the
 * left, so the runner's back and the faces of the spires ahead are lit. It does not change during
 * a ride.
 */
class MesaSky : SceneLight {
    val zenith = Color(0.08f, 0.33f, 0.52f, 1f)
    val horizon = Color(0.98f, 0.80f, 0.58f, 1f)
    override val sunColor = Color(1.00f, 0.90f, 0.72f, 1f)
    override val ambientSky = Color(0.52f, 0.56f, 0.62f, 1f)
    override val ambientGround = Color(0.46f, 0.37f, 0.30f, 1f)

    /** Unit vector pointing toward the sun: [ELEVATION] degrees up, [AZIMUTH] degrees round from straight ahead (negative is to the left). */
    override val sunDirection = Vector3().apply {
        val elevation = Math.toRadians(ELEVATION.toDouble())
        val azimuth = Math.toRadians(AZIMUTH.toDouble())
        set(
            (cos(elevation) * sin(azimuth)).toFloat(),
            sin(elevation).toFloat(),
            (-cos(elevation) * cos(azimuth)).toFloat(),
        ).nor()
    }

    companion object {
        const val ELEVATION = 48f
        const val AZIMUTH = -140f
    }
}
