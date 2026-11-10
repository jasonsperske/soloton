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
import java.io.File
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
 * The folder comes from [MusicSettings]; changing it there (for example in [MusicSettingsActivity]) or via
 * [setFolder] rescans and restarts playback.
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

    /** The folder currently in use (from [MusicSettings] until [start] or [setFolder]). */
    @Volatile
    var folder: String = settings.folder
        private set

    // SharedPreferences holds listeners weakly, so keep a strong reference.
    private val folderListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == MusicSettings.KEY_FOLDER) {
            val newFolder = settings.folder
            post { if (started && newFolder != session.folder) rescan(newFolder) }
        }
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
            play(session.trackFailed())
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
            settings.prefs.registerOnSharedPreferenceChangeListener(folderListener)
            rescan(settings.folder)
        } else if (session.problem != null) {
            rescan(session.folder)
        }
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

    /** Saves [path] as the music folder, rescans it and restarts playback. Starts the controller if needed. */
    fun setFolder(path: String) = post {
        val absolute = File(path.trim()).absolutePath
        if (!started) {
            started = true
            settings.prefs.registerOnSharedPreferenceChangeListener(folderListener)
        }
        // An explicit folder change means "play this": undo a user pause (a host stop still holds).
        if (hostStopped) resumeOnHostStart = true else session.setPaused(false)
        rescan(absolute) // before saving, so the preference listener sees no change
        if (settings.folder != absolute) settings.folder = absolute
    }

    /** Rescans the current folder (for example after the storage permission was granted). */
    fun refresh() = post { if (started) rescan(session.folder.ifEmpty { settings.folder }) }

    /** Host Activity became visible: resume if music was playing when it stopped; retry after a problem. */
    fun onHostStart() = post {
        hostStopped = false
        if (!started) return@post
        if (session.problem != null) {
            rescan(session.folder)
        } else if (resumeOnHostStart) {
            resumeOnHostStart = false
            session.setPaused(false)
            player?.playWhenReady = true
            publish()
        }
    }

    /** Host Activity is no longer visible: pause, remembering whether to resume. */
    fun onHostStop() = post {
        hostStopped = true
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
                    if (session.needsPermission && MusicPermission.isGranted(app)) rescan(session.folder)
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

    private fun rescan(path: String) {
        val generation = ++scanGeneration
        stopPlayer()
        session.beginScan(path)
        folder = path
        publish()
        submit {
            val result = MusicScanner.scan(File(path))
            val granted = MusicPermission.isGranted(app)
            post {
                if (generation != scanGeneration) return@post // a newer scan replaced this one
                play(session.scanFinished(result, granted))
            }
        }
    }

    private fun play(file: File?) {
        if (file == null) {
            stopPlayer()
            publish()
            return
        }
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
