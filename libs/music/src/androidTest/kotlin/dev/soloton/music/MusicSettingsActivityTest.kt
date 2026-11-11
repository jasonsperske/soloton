package dev.soloton.music

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.soloton.api.PlaylistRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The settings screen: opens from a non-Activity context, shows a playlist's folders and songs, and
 * saves folder additions, removals and ticks as they are made.
 */
@RunWith(AndroidJUnit4::class)
class MusicSettingsActivityTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_EXTERNAL_STORAGE)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val settings = MusicSettings(context)
    private val playlist = PlaylistRef("test-env", "Test Environment", ALBUM)
    private lateinit var root: File
    private var activity: Activity? = null

    @Before
    fun setUp() {
        root = File(context.getExternalFilesDir(null) ?: context.cacheDir, "settings-test").apply {
            deleteRecursively()
            mkdirs()
        }
        context.getSharedPreferences(MusicSettings.SELECTIONS_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        for (i in 1..3) File(root, "$ALBUM/0$i - Song $i.mp3").apply { parentFile!!.mkdirs() }.writeText("x")
        File(root, "$ALBUM/notes.json").writeText("{}")
        for (i in 1..2) File(root, "Demo Album B/0$i - Other $i.mp3").apply { parentFile!!.mkdirs() }.writeText("x")
        File(root, "Collections/Volume 1/01 - Deep.mp3").apply { parentFile!!.mkdirs() }.writeText("x")
        settings.folder = root.path
    }

    @After
    fun tearDown() {
        activity?.let { a -> instrumentation.runOnMainSync { a.finish() } }
        root.deleteRecursively()
    }

    @Test
    fun withoutAPlaylistItShowsOnlyTheMusicRoot() {
        open(null)
        val texts = awaitTexts { "Use as the root" in it }
        assertTrue(texts.toString(), "Music root: ${root.path}" in texts)
        assertTrue(texts.toString(), "Music" in texts)
        assertTrue(texts.toString(), "Choose the music root" in texts)
        assertFalse(texts.toString(), "Add folder" in texts)
        // The root's sub-folders are listed with their song counts.
        assertTrue(texts.toString(), "$ALBUM/" in texts && "3 songs" in texts)
    }

    @Test
    fun showsTheDefaultFolderAndItsSongsWithNumbersStripped() {
        open(playlist)
        val texts = awaitTexts { "Song 1" in it }
        assertTrue(texts.toString(), "Music for Test Environment" in texts)
        assertTrue(texts.toString(), "Music root: ${root.path}" in texts)
        assertEquals(texts.toString(), 2, texts.count { it == ALBUM }) // the folder row and the songs heading
        assertEquals(texts.toString(), 2, texts.count { it == "3 of 3 songs" })
        assertTrue(texts.toString(), texts.containsAll(listOf("Song 1", "Song 2", "Song 3", "All", "None", "Remove", "Add folder", "Reset to default", "Change")))
        assertFalse("other files are not songs: $texts", texts.any { "notes" in it })
        assertEquals(listOf(true, true, true), checkBoxes())
        assertNull("looking saves nothing", settings.selections.saved(playlist.id))
    }

    @Test
    fun aMissingDefaultFolderIsShownAsMissingWithAWarning() {
        open(PlaylistRef("late-env", "Late Environment", "Not Copied Yet"))
        val texts = awaitTexts { "missing" in it }
        assertTrue(texts.toString(), "Not Copied Yet" in texts)
        assertTrue(texts.toString(), texts.any { it.startsWith("No music found in ${File(root, "Not Copied Yet").path}.") })
        assertTrue(texts.toString(), texts.any { it.startsWith("This folder is missing") })
        assertTrue(texts.toString(), texts.none { it.endsWith(" songs") })
    }

    @Test
    fun tickingSongsAddingAndRemovingFoldersSavesAtOnce() {
        open(playlist)
        awaitTexts { "Song 2" in it }

        // Untick one song.
        click("Song 2")
        awaitTexts { "2 of 3 songs" in it }
        assertEquals(listOf(true, false, true), checkBoxes())
        assertEquals(
            MusicSelection(listOf(SelectedFolder(ALBUM, setOf("02 - Song 2.mp3")))),
            settings.selections.saved(playlist.id),
        )

        // None, then All.
        click("None")
        var texts = awaitTexts { "0 of 3 songs" in it }
        assertTrue(texts.toString(), "No songs selected for Test Environment. Open music settings and tick some songs." in texts)
        assertEquals(3, settings.selections.saved(playlist.id)!!.folders.single().excluded.size)
        click("All")
        texts = awaitTexts { "3 of 3 songs" in it }
        assertTrue(texts.toString(), texts.none { it.startsWith("No songs selected") })
        assertTrue(settings.selections.saved(playlist.id)!!.folders.single().excluded.isEmpty())

        // Add a folder through the browser, which starts at the music root and counts songs.
        click("Add folder")
        texts = awaitTexts { "Demo Album B/" in it }
        assertTrue(texts.toString(), "Add a folder" in texts && root.path in texts)
        assertTrue(texts.toString(), "2 songs" in texts && "3 songs" in texts)
        click("Demo Album B/")
        awaitTexts { File(root, "Demo Album B").path in it }
        click("Add this folder")
        texts = awaitTexts { "Other 1" in it }
        assertTrue(texts.toString(), "2 of 2 songs" in texts)
        assertEquals(listOf(ALBUM, "Demo Album B"), settings.selections.saved(playlist.id)!!.folders.map { it.path })
        assertEquals("the new folder is selected, showing its songs", listOf(true, true), checkBoxes())

        // Adding it again does nothing.
        click("Add folder")
        awaitTexts { "Demo Album B/" in it }
        click("Demo Album B/")
        awaitTexts { File(root, "Demo Album B").path in it }
        click("Add this folder")
        awaitTexts { "Other 1" in it }
        assertEquals(2, settings.selections.saved(playlist.id)!!.folders.size)

        // Untick a song in the second folder; the first folder's ticks are untouched.
        click("Other 2")
        awaitTexts { "1 of 2 songs" in it }
        assertEquals(setOf("02 - Other 2.mp3"), settings.selections.saved(playlist.id)!!.folders[1].excluded)
        assertTrue(settings.selections.saved(playlist.id)!!.folders[0].excluded.isEmpty())

        // A folder holding only sub-folders offers them instead.
        click("Add folder")
        awaitTexts { "Collections/" in it }
        click("Collections/")
        awaitTexts { "Volume 1/" in it }
        click("Add this folder")
        texts = awaitTexts { it.any { t -> t.startsWith("No songs directly in this folder") } }
        assertTrue(texts.toString(), "Volume 1/" in texts && "1 song" in texts)
        click("Volume 1/")
        awaitTexts { "Deep" in it }
        assertEquals(
            listOf(ALBUM, "Demo Album B", "Collections", "Collections/Volume 1"),
            settings.selections.saved(playlist.id)!!.folders.map { it.path },
        )

        // Remove the three added folders, last first.
        repeat(3) { n ->
            clickLast("Remove")
            awaitTrue("folder removed") { settings.selections.saved(playlist.id)!!.folders.size == 3 - n }
        }
        texts = awaitTexts { "Song 1" in it }
        assertEquals(listOf(ALBUM), settings.selections.saved(playlist.id)!!.folders.map { it.path })
        assertFalse(texts.toString(), "Demo Album B" in texts)

        // Remove the last one too: a clear problem. Reset brings the default back.
        clickLast("Remove")
        texts = awaitTexts { it.any { t -> t.startsWith("No folders chosen for Test Environment") } }
        assertTrue(settings.selections.saved(playlist.id)!!.folders.isEmpty())
        click("Reset to default")
        awaitTexts { "3 of 3 songs" in it && "Song 1" in it }
        assertNull(settings.selections.saved(playlist.id))
    }

    @Test
    fun changingTheRootKeepsTheSelectionUnderTheNewRoot() {
        val other = File(root.parentFile, "settings-test-2").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            for (i in 1..4) File(other, "$ALBUM/0$i - Song $i.mp3").apply { parentFile!!.mkdirs() }.writeText("x")
            settings.selections.save(playlist.id, MusicSelection(listOf(SelectedFolder(ALBUM, setOf("02 - Song 2.mp3")))))
            open(playlist)
            awaitTexts { "2 of 3 songs" in it }
            click("Change")
            var texts = awaitTexts { "Use as the root" in it }
            assertTrue(texts.toString(), "Choose the music root" in texts && "Cancel" in texts)
            click("Up")
            awaitTexts { "settings-test-2/" in it }
            click("settings-test-2/")
            awaitTexts { other.path in it }
            click("Use as the root")
            texts = awaitTexts { "3 of 4 songs" in it }
            assertEquals(other.path, settings.folder)
            assertTrue(texts.toString(), "Music root: ${other.path}" in texts)
            assertEquals(listOf(true, false, true, true), checkBoxes())
        } finally {
            other.deleteRecursively()
        }
    }

    // ---- helpers ----

    private fun open(playlist: PlaylistRef?) {
        val intent = MusicSettingsActivity.intent(context, playlist).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
    }

    private fun visibleViews(): List<View> {
        val out = ArrayList<View>()
        instrumentation.runOnMainSync {
            fun walk(v: View) {
                if (v.visibility != View.VISIBLE) return
                out += v
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(activity!!.window.decorView)
        }
        return out
    }

    private fun texts(): List<String> = visibleViews().filterIsInstance<TextView>().map { it.text.toString() }.filter { it.isNotEmpty() }

    private fun checkBoxes(): List<Boolean> = visibleViews().filterIsInstance<Checkable>().map { it.isChecked }

    /** Lists arrive from a background thread, so wait for what is expected. */
    private fun awaitTexts(ok: (List<String>) -> Boolean): List<String> {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var last: List<String> = emptyList()
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            last = texts()
            if (ok(last)) return last
            SystemClock.sleep(100)
        }
        fail("Timed out; the screen shows $last")
        error("unreachable")
    }

    private fun awaitTrue(what: String, ok: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (ok()) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $what; the screen shows ${texts()}")
    }

    private fun click(text: String) = clickOn(visibleViews().filterIsInstance<TextView>().firstOrNull { it.text.toString() == text }, text)

    private fun clickLast(text: String) = clickOn(visibleViews().filterIsInstance<TextView>().lastOrNull { it.text.toString() == text }, text)

    /** Clicks the view, or the nearest ancestor that takes clicks (a list row around its label). */
    private fun clickOn(view: View?, text: String) {
        assertNotNull("nothing on screen says '$text': ${texts()}", view)
        instrumentation.runOnMainSync {
            var v: View? = view
            while (v != null && !v.hasOnClickListeners()) v = v.parent as? View
            assertNotNull("'$text' is not clickable", v)
            v!!.performClick()
        }
        instrumentation.waitForIdleSync()
    }

    private companion object {
        /** Apostrophe, exclamation marks and spaces, as real folder names have. */
        const val ALBUM = "Demo Album's Greatest Hits!!"
    }
}
