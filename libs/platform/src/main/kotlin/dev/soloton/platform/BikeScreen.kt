package dev.soloton.platform

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import android.view.Window
import android.view.WindowManager

/**
 * Display helpers for the bike tablet: landscape only, immersive fullscreen, screen kept on.
 *
 * Works on any Activity (including libGDX's AndroidApplication, which cannot extend
 * [BikeActivity]). Call [applyBikeScreen] in onCreate and [reapplyImmersive] from
 * onResume and onWindowFocusChanged(true): Android drops immersive mode on focus loss.
 *
 * API 24 has no WindowInsetsController, so this uses the system UI visibility flags.
 */
object BikeScreen {
    /** Sticky immersive: hides status and navigation bars; a swipe shows them briefly. */
    @Suppress("DEPRECATION")
    const val IMMERSIVE_FLAGS: Int =
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

    fun lockLandscape(activity: Activity) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    fun keepScreenOn(window: Window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Suppress("DEPRECATION")
    fun enterImmersive(window: Window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = IMMERSIVE_FLAGS
    }
}

/** Landscape + keep screen on + immersive. Call from onCreate. */
fun Activity.applyBikeScreen() {
    BikeScreen.lockLandscape(this)
    BikeScreen.keepScreenOn(window)
    BikeScreen.enterImmersive(window)
}

/** Re-enter immersive mode. Call from onResume and onWindowFocusChanged(hasFocus = true). */
fun Activity.reapplyImmersive() {
    BikeScreen.enterImmersive(window)
}

/** Base Activity for non-libGDX screens (settings, folder browser) with the bike display rules. */
open class BikeActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        applyBikeScreen()
    }

    override fun onResume() {
        super.onResume()
        reapplyImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) reapplyImmersive()
    }
}
