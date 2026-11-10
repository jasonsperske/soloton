package dev.soloton.roadwork

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * The runner: elapsed time, distance, current speed and stride phase. Pure maths, no rendering.
 *
 * Speed eases toward the pace source over [SPEED_EASE_SECONDS] so a button tap or a cadence
 * change does not jolt the camera. Distance is kept in double so hours of running stay precise.
 */
class RunState(initialSpeed: Float) {
    var elapsedSeconds: Double = 0.0
        private set
    var distanceMeters: Double = 0.0
        private set
    var speed: Float = initialSpeed.coerceAtLeast(0f)
        private set

    /** Stride phase in radians: one full turn is two steps (left and right foot). */
    var stridePhase: Float = 0f
        private set

    fun step(dt: Float, targetSpeed: Float) {
        val target = targetSpeed.coerceAtLeast(0f)
        val k = 1f - exp(-dt / SPEED_EASE_SECONDS)
        speed += (target - speed) * k
        if (abs(speed - target) < 1e-4f) speed = target
        elapsedSeconds += dt
        distanceMeters += speed.toDouble() * dt
        stridePhase = ((stridePhase + PI.toFloat() * stepsPerSecond(speed) * dt) % TWO_PI)
    }

    companion object {
        const val SPEED_EASE_SECONDS = 1.2f
        private const val TWO_PI = (2 * PI).toFloat()

        /** Running cadence: about 160 steps per minute at an easy jog, a little quicker when faster. */
        fun stepsPerSecond(speed: Float): Float =
            if (speed < 0.2f) 0f else (2.25f + 0.18f * speed).coerceAtMost(3.3f)

        /** Vertical head bob in metres for a stride phase and speed: one bump per step. */
        fun bob(phase: Float, speed: Float): Float {
            val amp = (0.018f + 0.009f * speed).coerceAtMost(0.07f) * (speed / 1.5f).coerceAtMost(1f)
            // |sin| peaks mid-stance on each foot; subtract the mean so the eye height stays put.
            return amp * (abs(sin(phase)) - 0.6366f)
        }

        /** Side-to-side head sway in metres: one cycle per two steps. */
        fun sway(phase: Float, speed: Float): Float =
            0.022f * (speed / 2.7f).coerceIn(0f, 1.4f) * sin(phase)
    }
}
