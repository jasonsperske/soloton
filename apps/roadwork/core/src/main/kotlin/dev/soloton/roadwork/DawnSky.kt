package dev.soloton.roadwork

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Time of day: from early dawn (sun on the horizon, amber haze, lamps still lit) to clear morning
 * over [DAWN_SECONDS], then it holds. All outputs are mutable fields updated in place by [update].
 */
class DawnSky {
    val zenith = Color()
    val horizon = Color()
    val sunColor = Color()
    val ambientSky = Color()
    val ambientGround = Color()
    /** Unit vector pointing toward the sun. */
    val sunDirection = Vector3()
    /** 1 when street lamps glow at full strength, 0 when they are off. */
    var lampGlow = 1f
        private set
    /** 0 at the start of a session, 1 once morning is reached. */
    var progress = 0f
        private set

    fun update(seconds: Double) {
        val x = (seconds / DAWN_SECONDS).toFloat().coerceIn(0f, 1f)
        progress = x * x * (3f - 2f * x)
        blend(zenith, ZENITH, progress)
        blend(horizon, HORIZON, progress)
        blend(sunColor, SUN, progress)
        blend(ambientSky, AMBIENT_SKY, progress)
        blend(ambientGround, AMBIENT_GROUND, progress)
        lampGlow = (1f - progress * 1.8f).coerceIn(0f, 1f)
        val elevation = Math.toRadians((1.5f + 16.5f * progress).toDouble())
        val azimuth = Math.toRadians(SUN_AZIMUTH_DEGREES.toDouble())
        // Azimuth measured from straight ahead (-z) toward the right (+x).
        sunDirection.set(
            (cos(elevation) * sin(azimuth)).toFloat(),
            sin(elevation).toFloat(),
            (-cos(elevation) * cos(azimuth)).toFloat(),
        ).nor()
    }

    private fun blend(out: Color, keys: Array<Color>, p: Float) {
        val f = p * (keys.size - 1)
        val i = f.toInt().coerceAtMost(keys.size - 2)
        out.set(keys[i]).lerp(keys[i + 1], f - i)
    }

    companion object {
        /** About 20 minutes from first light to morning. */
        const val DAWN_SECONDS = 1200.0
        const val SUN_AZIMUTH_DEGREES = 11f

        // Keyframes: early dawn, sunrise, morning. Amber-to-blue, deliberately avoiding pink and purple.
        private val ZENITH = arrayOf(Color(0.11f, 0.22f, 0.34f, 1f), Color(0.21f, 0.40f, 0.58f, 1f), Color(0.33f, 0.57f, 0.80f, 1f))
        private val HORIZON = arrayOf(Color(0.95f, 0.70f, 0.40f, 1f), Color(0.97f, 0.83f, 0.60f, 1f), Color(0.80f, 0.87f, 0.91f, 1f))
        private val SUN = arrayOf(Color(0.95f, 0.58f, 0.32f, 1f), Color(1.00f, 0.80f, 0.58f, 1f), Color(1.00f, 0.95f, 0.86f, 1f))
        private val AMBIENT_SKY = arrayOf(Color(0.42f, 0.44f, 0.50f, 1f), Color(0.50f, 0.54f, 0.64f, 1f), Color(0.58f, 0.66f, 0.74f, 1f))
        private val AMBIENT_GROUND = arrayOf(Color(0.26f, 0.21f, 0.18f, 1f), Color(0.32f, 0.28f, 0.23f, 1f), Color(0.36f, 0.34f, 0.29f, 1f))
    }
}
