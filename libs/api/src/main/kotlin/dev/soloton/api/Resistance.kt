package dev.soloton.api

/**
 * How hard the rider has set the bike. Polled every frame from the render thread; reads must be
 * cheap and thread-safe. Environments may use it as a difficulty or intensity setting.
 */
interface ResistanceSource {
    /** 0 (no resistance) to 1 (the knob at its end). */
    val fraction: Float
}
