package dev.soloton.music

/** Counts consecutive playback failures; [recordFailure] returns true once the cap is reached. */
class FailureCounter(val cap: Int = DEFAULT_CAP) {
    init {
        require(cap > 0)
    }

    var consecutive: Int = 0
        private set

    /** @return true if playback should stop. */
    fun recordFailure(): Boolean {
        consecutive++
        return consecutive >= cap
    }

    fun recordSuccess() {
        consecutive = 0
    }

    companion object {
        const val DEFAULT_CAP = 5
    }
}
