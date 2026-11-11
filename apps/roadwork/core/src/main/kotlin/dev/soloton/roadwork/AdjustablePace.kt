package dev.soloton.roadwork

import dev.soloton.bike.PaceSource
import kotlin.math.roundToInt

/**
 * The default pace: a steady easy ride that the rider nudges with the HUD's slower/faster buttons.
 * Speeds move in [step] increments and stay within [min]..[max] metres per second.
 * Safe to read from any thread.
 */
class AdjustablePace(
    initial: Float = DEFAULT_METERS_PER_SECOND,
    val step: Float = 0.1f,
    val min: Float = 1.5f,
    val max: Float = 6f,
) : PaceSource {
    init {
        require(step > 0f) { "step must be positive" }
        require(min in 0f..max) { "need 0 <= min <= max" }
    }

    @Volatile
    override var metersPerSecond: Float = snap(initial)
        private set

    fun slower() { metersPerSecond = snap(metersPerSecond - step) }

    fun faster() { metersPerSecond = snap(metersPerSecond + step) }

    fun set(value: Float) { metersPerSecond = snap(value) }

    val canGoSlower: Boolean get() = metersPerSecond > min + step * 0.01f
    val canGoFaster: Boolean get() = metersPerSecond < max - step * 0.01f

    /** Rounds to a whole number of steps (so repeated taps do not drift) and clamps to the range. */
    private fun snap(value: Float): Float = ((value / step).roundToInt() * step).coerceIn(min, max)

    companion object {
        /** 10.8 km/h: an easy ride, and a pace the jogger ahead can hold. */
        const val DEFAULT_METERS_PER_SECOND = 3.0f
    }
}
