package dev.soloton.api

/** A playable audio file found in the configured music folder. */
data class Track(
    val title: String,
    val artist: String?,
    val album: String?,
    /** Absolute path on the device. */
    val path: String,
)

/** What the music player is doing. Read by the UI every frame, so implementations must make reads cheap and thread-safe. */
sealed interface MusicState {
    data object Idle : MusicState
    data class Playing(val track: Track) : MusicState
    data class Paused(val track: Track) : MusicState
    /** Nothing can play: folder missing, empty, unreadable, permission denied, or too many failed files in a row. */
    data class Problem(val message: String, val folder: String) : MusicState
}

/**
 * Shuffled background music. Implementations are free to do work on other threads,
 * but every method here must return immediately and be safe to call from the render thread.
 */
interface MusicController {
    val state: MusicState
    fun start()
    fun skip()
    fun pause()
    fun resume()
}

/** A controller that never plays anything. Useful on desktop and in tests. */
class SilentMusicController(private val folder: String = "(none)") : MusicController {
    override var state: MusicState = MusicState.Idle
        private set
    override fun start() { state = MusicState.Problem("Music is not available on this platform.", folder) }
    override fun skip() {}
    override fun pause() {}
    override fun resume() {}
}
