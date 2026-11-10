package dev.soloton.roadwork.android

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import dev.soloton.api.PlatformActions
import dev.soloton.music.FolderMusicController
import dev.soloton.music.MusicSettingsActivity
import dev.soloton.platform.applyBikeScreen
import dev.soloton.platform.reapplyImmersive
import dev.soloton.roadwork.RoadworkGame

/**
 * Launcher: runs the libGDX scene fullscreen, landscape, with the screen kept on, and plays music from
 * the configured folder.
 *
 * Lifecycle:
 * - Music: [FolderMusicController.attachTo] pauses on stop, resumes on start (for example on returning
 *   from settings or from the home screen), asks for the storage permission on first launch, rescans
 *   after a grant and releases the player on destroy. [RoadworkGame.create] calls `music.start()` once;
 *   the Activity does not call it, and a second call would only retry after a problem anyway.
 * - Back is not caught by libGDX, so it finishes the Activity, which disposes the scene and the player.
 */
class RoadworkActivity : AndroidApplication() {
    private lateinit var music: FolderMusicController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyBikeScreen()
        music = FolderMusicController(this).also { it.attachTo(this) }
        val actions = object : PlatformActions {
            // Called from the render thread; startActivity belongs on the UI thread.
            override fun openMusicSettings() = runOnUiThread {
                if (!isFinishing) startActivity(MusicSettingsActivity.intent(this@RoadworkActivity))
            }
        }
        initialize(RoadworkGame(music = music, actions = actions), gpuConfig())
    }

    override fun onResume() {
        super.onResume()
        reapplyImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) reapplyImmersive()
    }

    companion object {
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
