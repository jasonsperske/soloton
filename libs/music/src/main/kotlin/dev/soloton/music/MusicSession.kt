package dev.soloton.music

import dev.soloton.api.MusicState
import dev.soloton.api.Track
import java.io.File
import kotlin.random.Random

/**
 * The platform-free playback state machine: which folder, which tracks, what plays next, and when to give up.
 * The Android controller feeds it events (scan finished, track failed, track ended, pause) and plays the
 * files it returns. Not thread-safe: confine to one thread (the player's application thread).
 */
class MusicSession(
    private val random: Random = Random.Default,
    failureCap: Int = FailureCounter.DEFAULT_CAP,
) {
    private val failures = FailureCounter(failureCap)
    private var bag: ShuffleBag<File>? = null

    var folder: String = ""
        private set
    var trackCount: Int = 0
        private set
    var currentFile: File? = null
        private set
    var currentTrack: Track? = null
        private set
    var paused: Boolean = false
        private set
    var problem: MusicState.Problem? = null
        private set

    /** True when the last problem was missing storage permission, so granting it should trigger a rescan. */
    var needsPermission: Boolean = false
        private set

    /** The immutable snapshot to publish. */
    val state: MusicState
        get() {
            problem?.let { return it }
            val track = currentTrack ?: return MusicState.Idle
            return if (paused) MusicState.Paused(track) else MusicState.Playing(track)
        }

    /** A new scan of [folder] has started. Playback stops until [scanFinished]. */
    fun beginScan(folder: String) {
        this.folder = folder
        bag = null
        trackCount = 0
        currentFile = null
        currentTrack = null
        problem = null
        needsPermission = false
        failures.recordSuccess()
    }

    /** @return the first file to play, or null if there is nothing (state is then [MusicState.Problem]). */
    fun scanFinished(result: ScanResult, permissionGranted: Boolean = true): File? {
        if (result !is ScanResult.Found) {
            if (!permissionGranted) {
                permissionDenied()
            } else {
                stop(MusicMessages.noMusic(folder, result))
            }
            return null
        }
        trackCount = result.files.size
        bag = ShuffleBag(result.files, random)
        return advance()
    }

    fun permissionDenied() {
        stop(MusicMessages.permission(folder))
        needsPermission = true
    }

    /** Next track after the current one ended or the user skipped. Null if there is no library. */
    fun advance(): File? {
        if (problem != null) return null
        val file = bag?.next() ?: return null
        currentFile = file
        currentTrack = fallbackTrack(file)
        return file
    }

    /** The current file could not be read or decoded. @return the next file to try, or null if stopped. */
    fun trackFailed(): File? {
        if (bag == null || problem != null) return null
        if (failures.recordFailure()) {
            stop(MusicMessages.tooManyFailures(folder, failures.cap))
            return null
        }
        return advance()
    }

    /** The current file decoded and is ready to play. Resets the failure count. */
    fun trackReady() {
        failures.recordSuccess()
    }

    /** Tags for [file] arrived; ignored if another track has started since. */
    fun tagsRead(file: File, title: String?, artist: String?, album: String?) {
        if (file != currentFile) return
        currentTrack = withTags(fallbackTrack(file), title, artist, album)
    }

    fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    private fun stop(problem: MusicState.Problem) {
        this.problem = problem
        currentFile = null
        currentTrack = null
        bag = null
        needsPermission = false
    }

    companion object {
        /** Title from the file name, album from the parent folder name. */
        fun fallbackTrack(file: File): Track = Track(
            title = file.nameWithoutExtension.ifBlank { file.name },
            artist = null,
            album = file.parentFile?.name?.takeIf { it.isNotBlank() },
            path = file.absolutePath,
        )

        /** Tag values win when present and not blank. */
        fun withTags(fallback: Track, title: String?, artist: String?, album: String?): Track = fallback.copy(
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: fallback.title,
            artist = artist?.trim()?.takeIf { it.isNotEmpty() } ?: fallback.artist,
            album = album?.trim()?.takeIf { it.isNotEmpty() } ?: fallback.album,
        )
    }
}

/** User-facing problem messages. Each says what is wrong and where. */
object MusicMessages {
    // FLAC is still scanned (it plays on Android 8.1+), but is not suggested: Android 7.0 cannot decode it.
    private val formats = (MusicScanner.EXTENSIONS - "flac").sorted().joinToString(", ")

    fun noMusic(folder: String, result: ScanResult): MusicState.Problem {
        val why = when (result) {
            ScanResult.Missing -> "The folder does not exist. Copy audio files there or choose another folder in music settings."
            ScanResult.NotADirectory -> "That path is a file, not a folder. Choose a folder in music settings."
            ScanResult.Unreadable -> "The folder cannot be read. Check storage permission in music settings."
            ScanResult.Empty, is ScanResult.Found -> "Copy $formats files there or choose another folder in music settings."
        }
        return MusicState.Problem("No music found in $folder. $why", folder)
    }

    fun permission(folder: String): MusicState.Problem = MusicState.Problem(
        "Music needs permission to read storage. Open music settings and allow it to play files from $folder.",
        folder,
    )

    fun tooManyFailures(folder: String, count: Int): MusicState.Problem = MusicState.Problem(
        "Music stopped: $count files in a row in $folder could not be played. Check the files or choose another folder.",
        folder,
    )
}
