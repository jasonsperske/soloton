package dev.soloton.music

import android.Manifest
import android.content.Intent
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The settings screen opens from a non-Activity context, shows the folder, and closes cleanly. */
@RunWith(AndroidJUnit4::class)
class MusicSettingsActivityTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_EXTERNAL_STORAGE)

    @Test
    fun opensAndShowsCurrentFolder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.getExternalFilesDir(null) ?: context.cacheDir, "settings-test").apply { mkdirs() }
        MusicSettings(context).folder = folder.path

        val intent = MusicSettingsActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        val texts = ArrayList<String>()
        instrumentation.runOnMainSync {
            fun walk(v: android.view.View) {
                if (v is TextView) texts += v.text.toString()
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(activity.window.decorView)
        }
        assertTrue(texts.toString(), texts.any { folder.path in it })
        assertTrue(texts.toString(), texts.any { it == "Use this folder" })
        activity.finish()
        folder.deleteRecursively()
    }
}
