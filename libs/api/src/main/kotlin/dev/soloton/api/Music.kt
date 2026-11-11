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
    /** Nothing can play: folder missing, empty, unreadable, permission denied, nothing selected, or too many failed files in a row. */
    data class Problem(val message: String, val folder: String) : MusicState
}

/**
 * Names a playlist: the songs one thing (in Roadwork, one environment) plays. The platform keeps the
 * user's choice of folders and songs under [id]; the caller only says what the playlist is called
 * and where it starts out.
 *
 * @param id stable key the user's selection is saved under (an environment id)
 * @param title what the playlist belongs to, as shown to the user ("Riverfront Roadwork")
 * @param defaultFolder the sub-folder of the music root that plays until the user chooses otherwise,
 *   or null for everything under the root
 */
data class PlaylistRef(val id: String, val title: String, val defaultFolder: String?)

/**
 * A short account of a playlist for a menu: how many folders it draws on and how many of their songs
 * are selected. [problem] is set when nothing would play; [pending] while the answer is still being
 * worked out.
 */
data class PlaylistSummary(
    val folderCount: Int,
    val selectedCount: Int,
    val totalCount: Int,
    val problem: String? = null,
    val pending: Boolean = false,
) {
    /** One line: "14 of 20 songs from 1 folder", the problem, or a placeholder while [pending]. */
    val text: String
        get() = when {
            pending -> "Looking for music..."
            problem != null -> problem
            else -> "$selectedCount of $totalCount ${if (totalCount == 1) "song" else "songs"} from " +
                "$folderCount ${if (folderCount == 1) "folder" else "folders"}"
        }

    companion object {
        val PENDING = PlaylistSummary(0, 0, 0, pending = true)
    }
}

/**
 * Shuffled background music. Implementations are free to do work on other threads,
 * but every method here must return immediately and be safe to call from the render thread.
 */
interface MusicController {
    val state: MusicState

    /** Starts playing the current playlist. Calling again after a problem retries; otherwise a no-op. */
    fun start()

    /** Stops playing and returns to [MusicState.Idle]. [start] plays again, from a new shuffle. */
    fun stop()

    /**
     * Plays only the songs selected for [playlist]: the user's saved folders and songs for its id, or
     * its default folder with every song until they choose. `null` means the whole music root,
     * including its sub-folders. Takes effect whether it is called before or after [start]; a change
     * after [start] starts a track from the new playlist. A playlist with nothing to play is a
     * [MusicState.Problem].
     */
    fun usePlaylist(playlist: PlaylistRef?)

    /**
     * What [playlist] would play, for a menu. Cheap and thread-safe: it returns the last known answer
     * (or [PlaylistSummary.PENDING]) at once and refreshes it in the background, so poll it.
     */
    fun playlistSummary(playlist: PlaylistRef): PlaylistSummary

    fun skip()
    fun pause()
    fun resume()
}

/** A controller that never plays anything. Useful on desktop and in tests. */
class SilentMusicController(private val folder: String = "(none)") : MusicController {
    @Volatile
    override var state: MusicState = MusicState.Idle
        private set
    /** The last playlist passed to [usePlaylist]. */
    @Volatile
    var playlist: PlaylistRef? = null
        private set
    override fun start() { state = MusicState.Problem("Music is not available on this platform.", folder) }
    override fun stop() { state = MusicState.Idle }
    override fun usePlaylist(playlist: PlaylistRef?) { this.playlist = playlist }
    override fun playlistSummary(playlist: PlaylistRef): PlaylistSummary =
        PlaylistSummary(0, 0, 0, problem = "Music is not available on this platform.")
    override fun skip() {}
    override fun pause() {}
    override fun resume() {}
}
