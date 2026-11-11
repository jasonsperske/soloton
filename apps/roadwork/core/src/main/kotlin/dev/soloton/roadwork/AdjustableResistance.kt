package dev.soloton.roadwork

import dev.soloton.api.ResistanceSource
import kotlin.math.roundToInt

/**
 * Resistance set by hand, for when there is no bike to read it from: the desktop launcher's
 * `--resistance`, and the R and F keys during a ride. Moves in [step] increments between 0 and 1.
 * Safe to read from any thread.
 */
class AdjustableResistance(
    initial: Float = 0f,
    val step: Float = 0.05f,
) : ResistanceSource {
    init {
        require(step > 0f && step <= 1f) { "step must be in (0, 1]" }
    }

    @Volatile
    override var fraction: Float = snap(initial)
        private set

    fun lower() { fraction = snap(fraction - step) }

    fun raise() { fraction = snap(fraction + step) }

    fun set(value: Float) { fraction = snap(value) }

    /** Rounds to a whole number of steps (so repeated presses do not drift) and clamps to 0..1. */
    private fun snap(value: Float): Float =
        if (value.isNaN()) 0f else ((value / step).roundToInt() * step).coerceIn(0f, 1f)
}
