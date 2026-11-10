package dev.soloton.roadwork

/**
 * Accumulates real frame time and runs the simulation in fixed steps, so the scene evolves the
 * same way at 30 fps on the bike, 60 fps on a desktop, and in headless screenshot mode.
 *
 * Large frame gaps (a GC pause, the app returning from background) are clamped to [maxFrameSeconds]
 * so the simulation never tries to catch up on minutes of missed time.
 */
class FixedTimestep(
    val stepSeconds: Float = 1f / 60f,
    private val maxFrameSeconds: Float = 0.25f,
) {
    init {
        require(stepSeconds > 0f) { "stepSeconds must be positive" }
        require(maxFrameSeconds >= stepSeconds) { "maxFrameSeconds must be at least one step" }
    }

    private var accumulator = 0f

    /** Number of steps taken since creation. Simulated time is `steps * stepSeconds`. */
    var steps: Long = 0L
        private set

    val simulatedSeconds: Double get() = steps * stepSeconds.toDouble()

    /**
     * Adds [frameSeconds] of real time and calls [step] once per whole fixed step available.
     * Returns the number of steps run.
     */
    inline fun advance(frameSeconds: Float, step: (Float) -> Unit): Int {
        val n = consume(frameSeconds)
        repeat(n) { step(stepSeconds) }
        return n
    }

    /** Runs fixed steps until simulated time reaches [seconds] (no clamping). Returns steps run. */
    inline fun advanceTo(seconds: Double, step: (Float) -> Unit): Int {
        val n = stepsUntil(seconds)
        repeat(n) { step(stepSeconds) }
        return n
    }

    @PublishedApi
    internal fun consume(frameSeconds: Float): Int {
        accumulator += frameSeconds.coerceIn(0f, maxFrameSeconds)
        var n = 0
        while (accumulator >= stepSeconds) {
            accumulator -= stepSeconds
            n++
        }
        steps += n
        return n
    }

    @PublishedApi
    internal fun stepsUntil(seconds: Double): Int {
        // Epsilon (in steps) so 2.0 s at 1/60 s lands on exactly 120 steps despite float rounding.
        val target = kotlin.math.floor(seconds / stepSeconds + 1e-3).toLong()
        val n = (target - steps).coerceAtLeast(0L).toInt()
        steps += n
        return n
    }
}
