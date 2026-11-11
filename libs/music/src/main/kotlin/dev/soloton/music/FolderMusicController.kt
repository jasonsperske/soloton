package dev.soloton.music

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.soloton.api.MusicController
import dev.soloton.api.MusicState
import dev.soloton.api.PlaylistRef
import dev.soloton.api.PlaylistSummary
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.random.Random

/**
 * Shuffled playback of the audio files in a folder, using Media3 ExoPlayer.
 *
 * Threading: every public method returns immediately and may be called from any thread (including the
 * libGDX render thread). Work is posted to the main looper, which is the only thread that touches ExoPlayer
 * and the [MusicSession]. Scanning and tag reading run on one background thread. [state] is a `@Volatile`
 * immutable snapshot, so polling it every frame is a single field read.
 *
 * Wiring from a host Activity (call in `onCreate`):
 * ```
 * music = FolderMusicController(this).also { it.attachTo(this) }
 * music.start()
 * ```
 * [attachTo] pauses on `onStop`, resumes on `onStart`, rescans after storage permission is granted,
 * asks for that permission on first launch, and releases the player on `onDestroy`. Hosts that prefer
 * manual wiring call [onHostStart], [onHostStop] and [release] instead.
 *
 * Audio focus (loss, transient loss, ducking, regain) and headphone unplugging are handled by ExoPlayer.
 * The music root comes from [MusicSettings]; changing it there (for example in [MusicSettingsActivity]) or
 * via [setFolder] rescans and restarts playback. [usePlaylist] narrows playback to the folders and songs
 * saved for a playlist ([MusicSettings.selections]); folders inside the root are found again under a new
 * root, and a change to the selection while playing takes effect at once, without restarting a track that
 * is still selected.
 */
class FolderMusicController @JvmOverloads constructor(
    context: Context,
    private val settings: MusicSettings = MusicSettings(context),
    random: Random = Random.Default,
) : MusicController {
    private val app: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "soloton-music").apply { isDaemon = true }
    }

    // Main-thread state.
    private val session = MusicSession(random)
    private var player: ExoPlayer? = null
    private var started = false
    private var released = false
    private var scanGeneration = 0
    private var hostStopped = false
    private var resumeOnHostStart = false
    private var activePlaylist: PlaylistRef? = null
    private var permissionSeen = MusicPermission.isGranted(app)

    @Volatile
    override var state: MusicState = MusicState.Idle
        private set

    /** Number of playable files found by the last scan (0 while scanning or on a problem). */
    @Volatile
    var trackCount: Int = 0
        private set

    /** How many times a track decoded and became ready to play. For tests and diagnostics. */
    @Volatile
    internal var readyCount: Int = 0
        private set

    /** The music root currently in use (from [MusicSettings] until [start] or [setFolder]). */
    @Volatile
    var root: String = settings.folder
        private set

    /** The playlist whose selection plays, or null when the whole root plays. */
    @Volatile
    var playlist: PlaylistRef? = null
        private set

    /** The folder problems are reported against: [root], or the playlist's (first) folder. */
    @Volatile
    var folder: String = settings.folder
        private set

    // Summaries for menus, worked out on the background thread. Stale entries stay readable until replaced.
    private val summaries = ConcurrentHashMap<String, PlaylistSummary>()
    private val staleSummaries: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingSummaries: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile
    private var allSummariesStaleAt = 0L
    private val summaryComputedAt = ConcurrentHashMap<String, Long>()

    // SharedPreferences holds listeners weakly, so keep strong references.
    private val folderListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == MusicSettings.KEY_FOLDER) {
            invalidateSummaries()
            val newRoot = settings.folder
            post { if (started && newRoot != session.root) rescan(newRoot) }
        }
    }
    private val selectionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) staleSummaries += key else invalidateSummaries()
        post { if (started && (key == null || key == playlist?.id)) reloadSelection() }
    }
    private val selectionPrefs = settings.selectionPrefs

    init {
        settings.prefs.registerOnSharedPreferenceChangeListener(folderListener)
        selectionPrefs.registerOnSharedPreferenceChangeListener(selectionListener)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = guarded {
            when (playbackState) {
                Player.STATE_READY -> {
                    session.trackReady()
                    readyCount++
                }
                Player.STATE_ENDED -> play(session.advance())
            }
            publish()
        }

        override fun onPlayerError(error: PlaybackException) = guarded {
            Log.w(TAG, "Skipping ${session.currentFile}: ${error.errorCodeName}", error)
            currentFileFailed()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = guarded {
            // Permanent audio focus loss or headphones unplugged: ExoPlayer paused; reflect it.
            if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                session.setPaused(true)
                resumeOnHostStart = false
            }
            publish()
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = guarded { publish() }
    }

    /** Starts scanning and playing. Calling again after a problem retries; otherwise a no-op. */
    override fun start() = post {
        if (!started) {
            started = true
            rescan(settings.folder)
        } else if (session.problem != null) {
            rescan(session.root)
        }
    }

    override fun stop() = post {
        if (!started) return@post
        started = false
        scanGeneration++ // a scan in flight must not start anything
        resumeOnHostStart = false
        stopPlayer()
        session.reset()
        Log.i(TAG, "Stopped")
        publish()
    }

    override fun usePlaylist(playlist: PlaylistRef?) {
        this.playlist = playlist // at once, so a caller reading it back on its own thread sees it
        post {
            if (playlist == activePlaylist) return@post
            activePlaylist = playlist
            if (started) rescan(session.root) else folder = defaultFolderOf(settings.folder, playlist)
        }
    }

    override fun playlistSummary(playlist: PlaylistRef): PlaylistSummary {
        val id = playlist.id
        val known = summaries[id]
        val stale = known == null || id in staleSummaries || (summaryComputedAt[id] ?: 0L) < allSummariesStaleAt
        if (stale && pendingSummaries.add(id)) {
            val submitted = submit {
                try {
                    staleSummaries -= id
                    val startedAt = System.nanoTime()
                    val root = settings.folder
                    val resolved = SelectionResolver.resolve(root, SelectionResolver.effective(settings.selections, root, playlist))
                    summaries[id] = resolved.summary(MusicPermission.isGranted(app))
                    summaryComputedAt[id] = startedAt
                } finally {
                    pendingSummaries -= id
                }
            }
            if (!submitted) pendingSummaries -= id
        }
        return known ?: PlaylistSummary.PENDING
    }

    /** Makes every [playlistSummary] look at the disk again: files may have been copied over meanwhile. */
    fun invalidateSummaries() {
        allSummariesStaleAt = System.nanoTime()
    }

    override fun skip() = post {
        if (started && session.problem == null && session.trackCount > 0) play(session.advance())
    }

    override fun pause() = post {
        session.setPaused(true)
        resumeOnHostStart = false
        player?.playWhenReady = false
        publish()
    }

    override fun resume() = post {
        if (session.problem != null) return@post
        session.setPaused(false)
        if (!hostStopped) player?.playWhenReady = true else resumeOnHostStart = true
        publish()
    }

    /**
     * Saves [path] as the music root, rescans (the playlist folder under it, if one is set) and restarts
     * playback. Starts the controller if needed.
     */
    fun setFolder(path: String) = post {
        val absolute = File(path.trim()).absolutePath
        started = true
        // An explicit folder change means "play this": undo a user pause (a host stop still holds).
        if (hostStopped) resumeOnHostStart = true else session.setPaused(false)
        rescan(absolute) // before saving, so the preference listener sees no change
        if (settings.folder != absolute) settings.folder = absolute
    }

    /** Rescans the current folder (for example after the storage permission was granted). */
    fun refresh() = post { if (started) rescan(session.root.ifEmpty { settings.folder }) }

    /** Host Activity became visible: resume if music was playing when it stopped; retry after a problem. */
    fun onHostStart() = post {
        hostStopped = false
        invalidateSummaries()
        val resume = resumeOnHostStart
        resumeOnHostStart = false
        if (resume) session.setPaused(false)
        if (!started) return@post
        if (session.problem != null) {
            rescan(session.root)
        } else if (resume) {
            player?.playWhenReady = true
            publish()
        }
    }

    /** Host Activity is no longer visible: pause, remembering whether to resume. */
    fun onHostStop() = post {
        hostStopped = true
        if (!started) return@post
        if (!session.paused) resumeOnHostStart = true
        session.setPaused(true)
        player?.playWhenReady = false
        publish()
    }

    /** Stops playback and frees the player and background thread. The controller is unusable afterwards. */
    fun release() {
        main.post {
            if (released) return@post
            released = true
            settings.prefs.unregisterOnSharedPreferenceChangeListener(folderListener)
            selectionPrefs.unregisterOnSharedPreferenceChangeListener(selectionListener)
            try {
                player?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Player release failed", e)
            }
            player = null
            worker.shutdownNow()
            trackCount = 0
            state = MusicState.Idle
        }
    }

    /**
     * Ties this controller to [activity]'s lifecycle: start/stop pause and resume, resume after the
     * storage permission is granted, release on destroy. With [requestPermission], asks for the storage
     * permission the first time the app runs. Call from the Activity's `onCreate`.
     */
    @JvmOverloads
    fun attachTo(activity: Activity, requestPermission: Boolean = true) {
        val application = activity.application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            // API 24 has no default methods on this interface, so every one is implemented.
            override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(a: Activity) {
                if (a === activity) onHostStart()
            }
            override fun onActivityResumed(a: Activity) {
                if (a === activity) post {
                    if (MusicPermission.isGranted(app) != permissionSeen) {
                        permissionSeen = !permissionSeen
                        invalidateSummaries()
                    }
                    if (started && session.needsPermission && MusicPermission.isGranted(app)) rescan(session.root)
                }
            }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {
                if (a === activity) onHostStop()
            }
            override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {
                if (a === activity) {
                    application.unregisterActivityLifecycleCallbacks(this)
                    release()
                }
            }
        })
        if (requestPermission) MusicPermission.requestOnFirstLaunch(activity)
    }

    // ---- main thread ----

    /** Finds what the playlist plays under [newRoot] (or scans the whole root) and plays from it. */
    private fun rescan(newRoot: String) {
        val generation = ++scanGeneration
        val ref = activePlaylist
        stopPlayer()
        session.beginScan(newRoot, defaultFolderOf(newRoot, ref))
        root = newRoot
        folder = session.folder
        Log.i(TAG, if (ref == null) "Scanning $newRoot" else "Loading playlist ${ref.id} under $newRoot")
        publish()
        submit {
            val granted = MusicPermission.isGranted(app)
            if (ref == null) {
                val result = MusicScanner.scan(File(newRoot))
                post {
                    if (generation != scanGeneration) return@post // a newer scan replaced this one
                    play(session.scanFinished(result, granted))
                }
            } else {
                val resolved = resolve(newRoot, ref)
                post {
                    if (generation != scanGeneration) return@post
                    logSelection(ref, resolved)
                    play(session.filesLoaded(resolved.files, resolved.folderLabel, resolved.problem(ref.title), granted))
                    folder = session.folder
                }
            }
        }
    }

    /** The saved selection changed (settings screen): follow it, letting a still-selected track play on. */
    private fun reloadSelection() {
        val ref = activePlaylist ?: return
        val generation = ++scanGeneration
        val root = session.root
        submit {
            val granted = MusicPermission.isGranted(app)
            val resolved = resolve(root, ref)
            post {
                if (generation != scanGeneration) return@post
                logSelection(ref, resolved)
                val carriesOn = session.filesChanged(resolved.files, resolved.folderLabel, resolved.problem(ref.title), granted)
                folder = session.folder
                if (carriesOn) publish() else play(session.currentFile)
            }
        }
    }

    private fun resolve(root: String, ref: PlaylistRef): ResolvedSelection =
        SelectionResolver.resolve(root, SelectionResolver.effective(settings.selections, root, ref))

    private fun logSelection(ref: PlaylistRef, resolved: ResolvedSelection) {
        Log.i(TAG, "Playlist ${ref.id}: ${resolved.selectedCount} of ${resolved.totalCount} songs from ${resolved.folders.size} folder(s)")
    }

    private fun defaultFolderOf(root: String, ref: PlaylistRef?): String =
        if (ref == null) root else Playlist.folder(root, ref.defaultFolder)

    private fun play(file: File?) {
        if (file == null) {
            stopPlayer()
            publish()
            return
        }
        if (!file.isFile) {
            // Gone since the scan. Checked here because the player retries a missing file for
            // several seconds before it reports an error.
            Log.w(TAG, "Missing ${file.path}")
            currentFileFailed()
            return
        }
        Log.i(TAG, "Playing ${file.path}")
        val p = player ?: createPlayer().also { player = it }
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.prepare()
        p.playWhenReady = !session.paused && !hostStopped
        publish()
        submit {
            val tags = TagReader.read(file)
            post {
                session.tagsRead(file, tags.title, tags.artist, tags.album)
                publish()
            }
        }
    }

    /** The current file cannot be played: move on, or stop with a problem. */
    private fun currentFileFailed() {
        if (!started) return
        if (session.folder.isNotEmpty() && !File(session.folder).isDirectory) {
            // The folder itself went away (renamed, deleted, storage removed): rescan, so the problem
            // says the folder is missing instead of failing file after file up to the cap.
            rescan(session.root)
        } else {
            play(session.trackFailed())
        }
    }

    private fun createPlayer(): ExoPlayer {
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // Built inside a main-looper post, so the player's application looper is the main looper.
        return ExoPlayer.Builder(app)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { it.addListener(playerListener) }
    }

    private fun stopPlayer() {
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    private fun publish() {
        val s = session.state
        val suppressed = (player?.playbackSuppressionReason ?: Player.PLAYBACK_SUPPRESSION_REASON_NONE) !=
            Player.PLAYBACK_SUPPRESSION_REASON_NONE
        state = if (s is MusicState.Playing && suppressed) MusicState.Paused(s.track) else s
        trackCount = session.trackCount
    }

    /** Runs [block] on the main thread, never throwing into the caller or the looper. */
    private fun post(block: () -> Unit) {
        main.post { guarded(block) }
    }

    private fun guarded(block: () -> Unit) {
        if (released) return
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Music error", e)
            try {
                stopPlayer()
            } catch (ignored: Exception) {
            }
            state = MusicState.Problem("Music stopped after an error: ${e.message ?: e.javaClass.simpleName}", folder)
        }
    }

    private fun submit(task: () -> Unit): Boolean = try {
        worker.execute {
            try {
                task()
            } catch (e: Exception) {
                Log.e(TAG, "Music background task failed", e)
            }
        }
        true
    } catch (e: RejectedExecutionException) {
        false
    }

    private companion object {
        const val TAG = "SolotonMusic"
    }
}
