package dev.soloton.roadwork.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import dev.soloton.api.PlatformActions
import dev.soloton.api.PlaylistRef
import dev.soloton.api.SettingsStore
import dev.soloton.bike.BikePaceSource
import dev.soloton.music.FolderMusicController
import dev.soloton.music.MusicSettingsActivity
import dev.soloton.platform.applyBikeScreen
import dev.soloton.platform.reapplyImmersive
import dev.soloton.roadwork.AdjustablePace
import dev.soloton.roadwork.AdjustableResistance
import dev.soloton.roadwork.Environments
import dev.soloton.roadwork.RoadworkGame

/**
 * Launcher: runs the libGDX app fullscreen, landscape, with the screen kept on. It opens on the
 * environment picker; a ride plays the songs chosen for its environment in music settings.
 * Each environment's settings (units, goal, its own options) are saved in this app's preferences.
 *
 * Environment: the [EXTRA_ENVIRONMENT] launch extra, if it names a known environment, skips the picker
 * and rides it:
 * `adb shell am start -n dev.soloton.roadwork/.android.RoadworkActivity --es environment <id>`.
 * Otherwise the picker is shown with the environment ridden last (saved in this app's preferences)
 * outlined.
 *
 * Lifecycle:
 * - Music: [FolderMusicController.attachTo] pauses on stop, resumes on start (for example on returning
 *   from settings or from the home screen), asks for the storage permission on first launch, rescans
 *   after a grant and releases the player on destroy. The game starts music when a ride starts and
 *   stops it on the way back to the picker; the Activity never does.
 * - Back is caught by the game: during a ride it returns to the picker, and on the picker it exits,
 *   which finishes the Activity and so disposes the scene and the player.
 */
class RoadworkActivity : AndroidApplication() {
    private lateinit var music: FolderMusicController
    /** Pace from the bike's own cadence when its sensor service answers; otherwise the on-screen buttons. */
    private lateinit var bike: BikePaceSource
    private lateinit var game: RoadworkGame

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyBikeScreen()
        music = FolderMusicController(this).also { it.attachTo(this) }
        bike = BikePaceSource(applicationContext)
        val buttonsPace = AdjustablePace()
        val keysResistance = AdjustableResistance()
        val actions = object : PlatformActions {
            // Called from the render thread; startActivity belongs on the UI thread.
            override fun openMusicSettings(playlist: PlaylistRef) = runOnUiThread {
                if (isFinishing) return@runOnUiThread
                startActivity(MusicSettingsActivity.intent(this@RoadworkActivity, playlist))
            }
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Each environment's units, goal and options, kept in this app's preferences. apply() is safe from the render thread.
        val settingsStore = object : SettingsStore {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
        }
        val requested = Environments.find(intent?.getStringExtra(EXTRA_ENVIRONMENT))
        val environment = requested ?: Environments.byId(prefs.getString(KEY_ENVIRONMENT, null))
        Log.i(TAG, if (requested != null) "Riding ${environment.id}" else "Picker, last environment ${environment.id}")
        game = RoadworkGame(
            music = music,
            pace = bike.orElse(buttonsPace),
            manualPace = buttonsPace,
            manualPaceActive = { !bike.isLive },
            // The bike's resistance knob; without a bike, the R and F keys of an attached keyboard.
            resistance = bike.resistanceOrElse(keysResistance),
            manualResistance = keysResistance,
            settingsStore = settingsStore,
            actions = actions,
            initialEnvironment = environment,
            startOnPicker = requested == null,
            // Called on the render thread when a ride starts; apply() is safe from any thread.
            onEnvironmentChanged = {
                Log.i(TAG, "Riding ${it.id}")
                prefs.edit().putString(KEY_ENVIRONMENT, it.id).apply()
            },
        )
        initialize(game, gpuConfig())
    }

    /** Launched again while running (the Activity is singleTask): an environment extra starts that ride. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val id = intent?.getStringExtra(EXTRA_ENVIRONMENT) ?: return
        if (Environments.find(id) != null) postRunnable { game.rideEnvironment(id) }
    }

    /** What the music player is doing; for tests and diagnostics. */
    internal fun musicState() = music.state

    /** Which screen is showing; for tests. */
    internal fun screen() = game.screen

    /** Runs [action] with the game on the render thread; for tests. */
    internal fun onRenderThread(action: (RoadworkGame) -> Unit) = postRunnable { action(game) }

    override fun onResume() {
        super.onResume()
        reapplyImmersive()
        bike.start()
    }

    override fun onPause() {
        bike.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) reapplyImmersive()
    }

    companion object {
        private const val TAG = "Roadwork"
        const val PREFS_NAME = "dev.soloton.roadwork"
        /** Preference: id of the environment ridden last. */
        const val KEY_ENVIRONMENT = "environment"
        /** Optional launch extra: id of an environment to ride at once, skipping the picker. */
        const val EXTRA_ENVIRONMENT = "environment"

        /**
         * Surface and backend settings for an old OpenGL ES 2.0 tablet GPU: no MSAA, no stencil or
         * destination alpha, 16-bit depth. Colour stays RGB888 because the sky is a long, smooth
         * gradient that bands visibly in RGB565.
         */
        fun gpuConfig() = AndroidApplicationConfiguration().apply {
            useGL30 = false
            r = 8
            g = 8
            b = 8
            a = 0
            depth = 16
            stencil = 0
            numSamples = 0
            useImmersiveMode = true
            useWakelock = false // FLAG_KEEP_SCREEN_ON from applyBikeScreen() does this without a permission
            useAccelerometer = false
            useGyroscope = false
            useRotationVectorSensor = false
            useCompass = false
            disableAudio = true // music uses ExoPlayer; the scene has no sound effects
        }
    }
}
