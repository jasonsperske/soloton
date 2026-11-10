package dev.soloton.api

/** How fast the runner is moving. Polled every frame from the render thread; reads must be cheap and thread-safe. */
interface PaceSource {
    /** Current forward speed in metres per second, 0 or more. */
    val metersPerSecond: Float
}
