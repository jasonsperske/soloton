package dev.soloton.roadwork.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.badlogic.gdx.Gdx
import dev.soloton.api.MusicState
import dev.soloton.roadwork.AppFlow
import dev.soloton.roadwork.Environments
import dev.soloton.roadwork.PickerLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Launches Roadwork and walks through it for 60 seconds: the picker shows and music is silent; a tap on
 * Ride starts the default environment's scene and music; the scene keeps drawing; back returns to the
 * picker and stops the music; back again exits. A screenshot of the ride is saved to [SCREENSHOT]
 * (outside the app's own storage, so it survives the uninstall that follows `connectedAndroidTest`).
 * Pull it with `adb pull /sdcard/roadwork-smoke.png`.
 *
 * A crash of the app process fails the run, since the test runs in that process.
 */
@RunWith(AndroidJUnit4::class)
class RoadworkSmokeTest {
    // Granted up front, so no permission dialog covers the scene. Without music the HUD shows the problem line.
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_EXTERNAL_STORAGE)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences(RoadworkActivity.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun prepareDevice() {
        // Wake and unlock the device, and skip the one-time "viewing full screen" hint.
        shell("input keyevent KEYCODE_WAKEUP")
        shell("input keyevent 82")
        shell("settings put secure immersive_mode_confirmations confirmed")
    }

    @Test
    fun pickerThenRideDrawsForSixtySecondsThenBackToThePicker() {
        // Start from a clean slate, with a saved id that no longer exists: the picker must still open.
        prefs.edit().putString(RoadworkActivity.KEY_ENVIRONMENT, "an-environment-that-was-removed").commit()
        val intent = Intent(context, RoadworkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as RoadworkActivity
        try {
            // ---- the picker ----
            awaitTrue("the picker") { activity.screen() == AppFlow.Screen.PICKER }
            SystemClock.sleep(PICKER_MS)
            assertEquals(AppFlow.Screen.PICKER, activity.screen())
            assertEquals("music does not play on the picker", MusicState.Idle, activity.musicState())
            val surface = findGlSurface(activity)
            assertNotNull("No GLSurfaceView in the Activity", surface)
            assertTrue("GL surface is not shown", isShown(surface!!))
            val pickerFrames = Gdx.graphics.frameId
            SystemClock.sleep(1_000)
            assertTrue("The picker is not drawing", Gdx.graphics.frameId > pickerFrames)
            assertEquals(
                "nothing is remembered until a ride starts",
                "an-environment-that-was-removed", prefs.getString(RoadworkActivity.KEY_ENVIRONMENT, null),
            )

            // ---- tap Ride on the first card ----
            val layout = PickerLayout().apply { layout(Environments.all.size, surface.width, surface.height) }
            val ride = layout.cards.first().ride
            val x = ((ride.x + ride.width / 2f) * surface.width / layout.width).toInt()
            val y = (surface.height - (ride.y + ride.height / 2f) * surface.height / PickerLayout.HEIGHT).toInt()
            shell("input tap $x $y")
            awaitTrue("the ride after tapping Ride at $x,$y") { activity.screen() == AppFlow.Screen.RIDE }
            SystemClock.sleep(WARMUP_MS)

            // The environment is the default one, is remembered, and music is narrowed to its folder.
            assertEquals(Environments.default.id, prefs.getString(RoadworkActivity.KEY_ENVIRONMENT, null))
            val musicFolder = Environments.default.musicFolder
            assertNotNull(musicFolder)
            when (val music = activity.musicState()) {
                is MusicState.Problem -> assertTrue(music.folder, music.folder.endsWith("/$musicFolder"))
                is MusicState.Playing -> assertTrue(music.track.path, "/$musicFolder/" in music.track.path)
                is MusicState.Paused -> assertTrue(music.track.path, "/$musicFolder/" in music.track.path)
                MusicState.Idle -> throw AssertionError("music never started")
            }
            val startFrame = Gdx.graphics.frameId
            val startTime = SystemClock.elapsedRealtime()

            SystemClock.sleep(RUN_MS - WARMUP_MS - PICKER_MS)

            assertFalse("Activity finished early", activity.isFinishing || activity.isDestroyed)
            assertEquals(AppFlow.Screen.RIDE, activity.screen())
            assertTrue("GL surface is no longer shown", isShown(surface))
            val frames = Gdx.graphics.frameId - startFrame
            val seconds = (SystemClock.elapsedRealtime() - startTime) / 1000.0
            val fps = frames / seconds
            Log.i(TAG, "Rendered $frames frames in %.1f s: %.1f fps average".format(seconds, fps))
            assertTrue("Scene stopped rendering ($frames frames in $seconds s)", frames > seconds)

            shell("screencap -p $SCREENSHOT")
            val size = shell("stat -c %s $SCREENSHOT").trim()
            Log.i(TAG, "Screenshot $SCREENSHOT ($size bytes)")
            assertTrue("Screenshot not written: '$size'", (size.toLongOrNull() ?: 0L) > 0L)
            assertEquals("Not landscape", Configuration.ORIENTATION_LANDSCAPE, activity.resources.configuration.orientation)

            // ---- back: the picker again, music stopped, still drawing ----
            shell("input keyevent KEYCODE_BACK")
            awaitTrue("the picker after back") { activity.screen() == AppFlow.Screen.PICKER }
            awaitTrue("music to stop") { activity.musicState() == MusicState.Idle }
            assertFalse("Back during a ride must not close the app", activity.isFinishing || activity.isDestroyed)
            val backFrames = Gdx.graphics.frameId
            SystemClock.sleep(2_000)
            assertTrue("The picker is not drawing after a ride", Gdx.graphics.frameId > backFrames)
            assertEquals(MusicState.Idle, activity.musicState())

            // ---- back on the picker exits ----
            shell("input keyevent KEYCODE_BACK")
            awaitTrue("the app to exit") { activity.isFinishing || activity.isDestroyed }
        } finally {
            instrumentation.runOnMainSync { if (!activity.isFinishing) activity.finish() }
        }
    }

    @Test
    fun theEnvironmentLaunchExtraSkipsThePicker() {
        prefs.edit().remove(RoadworkActivity.KEY_ENVIRONMENT).commit()
        val environment = Environments.all.last()
        val intent = Intent(context, RoadworkActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(RoadworkActivity.EXTRA_ENVIRONMENT, environment.id)
        val activity = instrumentation.startActivitySync(intent) as RoadworkActivity
        try {
            awaitTrue("the ride") { activity.screen() == AppFlow.Screen.RIDE }
            awaitTrue("music to start") { activity.musicState() != MusicState.Idle }
            awaitTrue("the environment to be remembered") { prefs.getString(RoadworkActivity.KEY_ENVIRONMENT, null) == environment.id }
            // Back still leads to the picker.
            shell("input keyevent KEYCODE_BACK")
            awaitTrue("the picker after back") { activity.screen() == AppFlow.Screen.PICKER }
            awaitTrue("music to stop") { activity.musicState() == MusicState.Idle }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 20_000, ok: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (ok()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun findGlSurface(activity: Activity): GLSurfaceView? {
        var found: GLSurfaceView? = null
        instrumentation.runOnMainSync {
            fun walk(v: View) {
                if (found != null) return
                if (v is GLSurfaceView) found = v
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(activity.window.decorView)
        }
        return found
    }

    private fun isShown(view: View): Boolean {
        var shown = false
        instrumentation.runOnMainSync { shown = view.isShown && view.width > 0 && view.height > 0 }
        return shown
    }

    /** Runs a shell command as the shell user and waits for it to finish. Returns its output. */
    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(pfd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
            .also { pfd.close() }
    }

    private companion object {
        const val TAG = "RoadworkSmoke"
        const val SCREENSHOT = "/sdcard/roadwork-smoke.png"
        const val PICKER_MS = 5_000L
        const val WARMUP_MS = 10_000L
        const val RUN_MS = 60_000L
    }
}
