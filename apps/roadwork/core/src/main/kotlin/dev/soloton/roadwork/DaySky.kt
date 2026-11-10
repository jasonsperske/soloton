package dev.soloton.roadwork

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sunny midday over the river. Over [DRIFT_SECONDS] the sun creeps a little lower and further
 * round and the light warms slightly, so a long ride never looks frozen; after that it holds.
 * There is no night. All outputs are mutable fields updated in place by [update].
 */
class DaySky {
    val zenith = Color()
    val horizon = Color()
    val sunColor = Color()
    val ambientSky = Color()
    val ambientGround = Color()
    /** Unit vector pointing toward the sun. */
    val sunDirection = Vector3()
    /** 0 at the start of a session, 1 once the drift is complete. */
    var progress = 0f
        private set

    fun update(seconds: Double) {
        val x = (seconds / DRIFT_SECONDS).toFloat().coerceIn(0f, 1f)
        progress = x * x * (3f - 2f * x)
        blend(zenith, ZENITH, progress)
        blend(horizon, HORIZON, progress)
        blend(sunColor, SUN, progress)
        blend(ambientSky, AMBIENT_SKY, progress)
        blend(ambientGround, AMBIENT_GROUND, progress)
        val elevation = Math.toRadians((ELEVATION_START + (ELEVATION_END - ELEVATION_START) * progress).toDouble())
        val azimuth = Math.toRadians((AZIMUTH_START + (AZIMUTH_END - AZIMUTH_START) * progress).toDouble())
        // Azimuth measured from straight ahead (-z) toward the right (+x). The sun sits behind and
        // to the left, so the jogger's back, the path and the far bank's facades are all sunlit.
        sunDirection.set(
            (cos(elevation) * sin(azimuth)).toFloat(),
            sin(elevation).toFloat(),
            (-cos(elevation) * cos(azimuth)).toFloat(),
        ).nor()
    }

    private fun blend(out: Color, keys: Array<Color>, p: Float) {
        out.set(keys[0]).lerp(keys[1], p)
    }

    companion object {
        /** The light drifts over the first 20 minutes. */
        const val DRIFT_SECONDS = 1200.0
        const val ELEVATION_START = 62f
        const val ELEVATION_END = 54f
        const val AZIMUTH_START = -128f
        const val AZIMUTH_END = -112f

        // Start and end of the drift: clear saturated blue, very slightly warmer later.
        private val ZENITH = arrayOf(Color(0.16f, 0.46f, 0.86f, 1f), Color(0.18f, 0.47f, 0.84f, 1f))
        private val HORIZON = arrayOf(Color(0.70f, 0.84f, 0.96f, 1f), Color(0.75f, 0.85f, 0.94f, 1f))
        private val SUN = arrayOf(Color(1.00f, 0.97f, 0.90f, 1f), Color(1.00f, 0.93f, 0.82f, 1f))
        private val AMBIENT_SKY = arrayOf(Color(0.50f, 0.58f, 0.70f, 1f), Color(0.51f, 0.57f, 0.67f, 1f))
        private val AMBIENT_GROUND = arrayOf(Color(0.34f, 0.32f, 0.29f, 1f), Color(0.36f, 0.33f, 0.29f, 1f))
    }
}
