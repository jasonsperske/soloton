package dev.soloton.roadwork.desktop

import dev.soloton.api.MusicController
import dev.soloton.api.MusicState
import dev.soloton.api.PlaylistRef
import dev.soloton.api.PlaylistSummary
import dev.soloton.api.Track
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop stand-in for the music player: plays nothing, but steps through track names so the
 * picker's music line and the HUD's now-playing line, skip and pause can be seen.
 *
 * Without a [musicDir] the tracks are a few made-up names, the same for every playlist (this is
 * what committed screenshots show). With one (the `SOLOTON_MUSIC_DIR` environment variable: a
 * folder holding one sub-folder per game, as on the tablet) each playlist lists the audio files in
 * its default folder there, so names and counts are real. The tablet's saved selections are not
 * available here: every song in the default folder counts as selected.
 */
class DemoMusicController(private val musicDir: File? = null) : MusicController {
    private val madeUp = listOf(
        Track("Harbour Lights", "The Early Risers", "Morning Tides", "/demo/01.ogg"),
        Track("Second Wind", "Lantern Street", "Long Way Round", "/demo/02.ogg"),
        Track("Tidewater Run", "Gulls and Gears", null, "/demo/03.ogg"),
    )
    private var tracks: List<Track> = madeUp
    private var folder: File? = musicDir
    private var index = 0
    private val libraries = ConcurrentHashMap<PlaylistRef, List<Track>>()

    @Volatile
    override var state: MusicState = MusicState.Idle
        private set

    /** The playlist the game asked for. */
    @Volatile
    var playlist: PlaylistRef? = null
        private set

    override fun usePlaylist(playlist: PlaylistRef?) {
        this.playlist = playlist
        if (musicDir == null) return
        folder = folderOf(playlist)
        tracks = if (playlist == null) tracksIn(musicDir, recursive = true) else library(playlist)
        index = 0
    }

    override fun playlistSummary(playlist: PlaylistRef): PlaylistSummary {
        if (musicDir == null) return PlaylistSummary(1, madeUp.size, madeUp.size)
        val count = library(playlist).size
        val problem = when {
            count > 0 -> null
            folderOf(playlist)?.isDirectory == true -> "No songs in the chosen folder"
            else -> "Music folder not found"
        }
        return PlaylistSummary(1, count, count, problem)
    }

    override fun start() {
        state = tracks.getOrNull(index)?.let { MusicState.Playing(it) }
            ?: MusicState.Problem("No music found in ${folder?.path}. Copy audio files there.", folder?.path ?: "(none)")
    }

    override fun stop() {
        state = MusicState.Idle
    }

    override fun skip() {
        if (tracks.isEmpty()) return
        index = (index + 1) % tracks.size
        state = when (state) {
            is MusicState.Paused -> MusicState.Paused(tracks[index])
            is MusicState.Playing -> MusicState.Playing(tracks[index])
            else -> state
        }
    }

    override fun pause() {
        (state as? MusicState.Playing)?.let { state = MusicState.Paused(it.track) }
    }

    override fun resume() {
        (state as? MusicState.Paused)?.let { state = MusicState.Playing(it.track) }
    }

    private fun folderOf(playlist: PlaylistRef?): File? {
        val dir = musicDir ?: return null
        return playlist?.defaultFolder?.let { File(dir, it) } ?: dir
    }

    /** Read once per playlist, so the picker can ask every frame. */
    private fun library(playlist: PlaylistRef): List<Track> = libraries.getOrPut(playlist) {
        tracksIn(folderOf(playlist) ?: return@getOrPut emptyList(), recursive = playlist.defaultFolder == null)
    }

    companion object {
        private val EXTENSIONS = setOf("mp3", "ogg", "opus", "m4a", "aac", "wav", "flac")
        private val TRACK_NUMBER = Regex("""^\d{1,3}(?:\s*[-_]+\s*|\.\s*(?=\D))(\S.*)$""")

        /** The audio files in [dir] by name, titled as the tablet titles untagged files: without a leading track number. */
        internal fun tracksIn(dir: File, recursive: Boolean): List<Track> {
            val files = (if (recursive) dir.walkTopDown().maxDepth(8).toList() else dir.listFiles()?.toList().orEmpty())
                .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in EXTENSIONS }
                .sortedBy { it.path.lowercase() }
            return files.map { f ->
                val name = f.nameWithoutExtension.trim()
                Track(TRACK_NUMBER.matchEntire(name)?.groupValues?.get(1)?.trim() ?: name, null, f.parentFile?.name, f.absolutePath)
            }
        }

        /** The folder named by `SOLOTON_MUSIC_DIR`, if it is set and exists. */
        fun musicDirFromEnvironment(value: String? = System.getenv("SOLOTON_MUSIC_DIR")): File? =
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isDirectory }
    }
}
