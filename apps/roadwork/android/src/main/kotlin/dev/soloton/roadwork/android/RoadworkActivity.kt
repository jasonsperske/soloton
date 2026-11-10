package dev.soloton.roadwork.android

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import dev.soloton.platform.applyBikeScreen
import dev.soloton.platform.reapplyImmersive
import dev.soloton.roadwork.RoadworkGame

/** Launcher: runs the libGDX scene fullscreen, landscape, with the screen kept on. */
class RoadworkActivity : AndroidApplication() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyBikeScreen()
        val config = AndroidApplicationConfiguration().apply {
            useImmersiveMode = true
            useGL30 = false // the device budget is OpenGL ES 2.0
            useAccelerometer = false
            useCompass = false
            useGyroscope = false
        }
        initialize(RoadworkGame(), config)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) reapplyImmersive()
    }
}
