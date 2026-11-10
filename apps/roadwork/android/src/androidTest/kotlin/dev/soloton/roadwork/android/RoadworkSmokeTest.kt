package dev.soloton.roadwork.android

import android.Manifest
import android.app.Activity
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Launches Roadwork, keeps it running for 60 seconds, checks the GL surface is up and still drawing,
 * and saves a screenshot to [SCREENSHOT] (outside the app's own storage, so it survives the uninstall
 * that follows `connectedAndroidTest`). Pull it with `adb pull /sdcard/roadwork-smoke.png`.
 *
 * A crash of the app process fails the run, since the test runs in that process.
 */
@RunWith(AndroidJUnit4::class)
class RoadworkSmokeTest {
    // Granted up front, so no permission dialog covers the scene. Without music the HUD shows the problem line.
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_EXTERNAL_STORAGE)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun runsForSixtySecondsAndDraws() {
        // Wake and unlock the device, and skip the one-time "viewing full screen" hint.
        shell("input keyevent KEYCODE_WAKEUP")
        shell("input keyevent 82")
        shell("settings put secure immersive_mode_confirmations confirmed")

        val context = instrumentation.targetContext
        val intent = Intent(context, RoadworkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as RoadworkActivity
        try {
            SystemClock.sleep(WARMUP_MS)
            val surface = findGlSurface(activity)
            assertNotNull("No GLSurfaceView in the Activity", surface)
            assertTrue("GL surface is not shown", isShown(surface!!))
            val startFrame = Gdx.graphics.frameId
            val startTime = SystemClock.elapsedRealtime()

            SystemClock.sleep(RUN_MS - WARMUP_MS)

            assertFalse("Activity finished early", activity.isFinishing || activity.isDestroyed)
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
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
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
        const val WARMUP_MS = 10_000L
        const val RUN_MS = 60_000L
    }
}
