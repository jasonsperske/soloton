package dev.soloton.music

import android.Manifest
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.soloton.api.MusicState
import dev.soloton.api.PlaylistRef
import dev.soloton.api.PlaylistSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a generated tone from a folder on the device, then survives the folder being switched to an empty
 * one (through [FolderMusicController.setFolder]) and back (through [MusicSettings], as the settings screen does).
 * Then playlists: a default folder, and a saved selection of folders and ticked songs.
 */
@RunWith(AndroidJUnit4::class)
class FolderMusicControllerTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_EXTERNAL_STORAGE)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var musicDir: File
    private lateinit var emptyDir: File
    private lateinit var controller: FolderMusicController

    @Before
    fun setUp() {
        root = File(context.getExternalFilesDir(null) ?: context.cacheDir, "wp4-test").apply {
            deleteRecursively()
            mkdirs()
        }
        musicDir = File(root, "music/Test Album").apply { mkdirs() }
        emptyDir = File(root, "empty").apply { mkdirs() }
        File(musicDir, "tone.wav").writeBytes(toneWav(seconds = 3.0, hz = 440.0))
        // An undecodable file next to the good one: must be skipped, not fatal.
        File(musicDir, "broken.mp3").writeBytes(ByteArray(4096) { (it * 31).toByte() })
        // Selections are saved per playlist id: start each test with none.
        context.getSharedPreferences(MusicSettings.SELECTIONS_PREFS_NAME, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        controller = FolderMusicController(context)
    }

    @After
    fun tearDown() {
        controller.release()
        root.deleteRecursively()
    }

    @Test
    fun playsToneThenHandlesEmptyFolderAndBack() {
        controller.setFolder(musicDir.path)
        val playing = awaitState<MusicState.Playing>("tone playing") { it.track.title == "tone" && controller.readyCount > 0 }
        assertEquals("Test Album", playing.track.album)
        assertEquals(File(musicDir, "tone.wav").absolutePath, playing.track.path)
        assertEquals(2, controller.trackCount)

        controller.setFolder(emptyDir.path)
        val problem = awaitState<MusicState.Problem>("empty folder problem") { true }
        assertEquals(emptyDir.path, problem.folder)
        assertTrue(problem.message, problem.message.startsWith("No music found in ${emptyDir.path}"))
        assertEquals(emptyDir.path, MusicSettings(context).folder)

        // Back again, the way MusicSettingsActivity does it: write the setting, the controller follows.
        val readyBefore = controller.readyCount
        MusicSettings(context).folder = musicDir.path
        awaitState<MusicState.Playing>("tone playing again") { controller.readyCount > readyBefore }

        controller.pause()
        awaitState<MusicState.Paused>("paused") { true }
        controller.resume()
        awaitState<MusicState.Playing>("resumed") { true }
    }

    @Test
    fun missingFolderIsAProblemAndNeverCrashes() {
        val missing = File(root, "does-not-exist").path
        controller.setFolder(missing)
        val problem = awaitState<MusicState.Problem>("missing folder problem") { true }
        assertEquals(missing, problem.folder)
        // Calls in the problem state are harmless no-ops.
        controller.skip()
        controller.resume()
        controller.pause()
        controller.start()
        awaitState<MusicState.Problem>("still a problem") { true }
    }

    /** Two sibling album folders under one root: only the playlist's default folder plays until the selection says otherwise. */
    @Test
    fun playsOnlyThePlaylistsDefaultFolder() {
        val library = File(root, "library")
        val first = File(library, PLAYLIST).apply { mkdirs() }
        val other = File(library, "Demo Album B").apply { mkdirs() }
        for (i in 1..3) File(first, "0$i - First $i.wav").writeBytes(toneWav(seconds = 1.0, hz = 330.0 * i))
        for (i in 1..3) File(other, "0$i - Other $i.wav").writeBytes(toneWav(seconds = 1.0, hz = 220.0 * i))
        File(first, "notes.json").writeText("{}")
        val playlist = PlaylistRef("test-env", "Test Environment", PLAYLIST)

        // Before start: the playlist is remembered and used by the first scan.
        controller.usePlaylist(playlist)
        assertEquals(playlist, controller.playlist)
        controller.setFolder(library.path)
        val played = HashSet<String>()
        var last = awaitState<MusicState.Playing>("first playlist track") { it.track.path.startsWith(first.path) }
        assertEquals(3, controller.trackCount)
        assertEquals(first.path, controller.folder)
        assertEquals(library.path, controller.root)
        repeat(30) {
            assertEquals(first.path, File(last.track.path).parent)
            assertEquals(PLAYLIST, last.track.album)
            assertTrue(last.track.title, last.track.title.startsWith("First "))
            played += last.track.path
            val before = last.track.path
            controller.skip()
            last = awaitState("track after skip") { it.track.path != before }
        }
        assertEquals("every playlist track played", 3, played.size)

        // After start: another playlist plays only its own default folder.
        controller.usePlaylist(PlaylistRef("other-env", "Other Environment", "Demo Album B"))
        last = awaitState("other playlist") { it.track.path.startsWith(other.path) }
        assertEquals(3, controller.trackCount)
        repeat(10) {
            assertEquals(other.path, File(last.track.path).parent)
            val before = last.track.path
            controller.skip()
            last = awaitState("track after skip") { it.track.path != before }
        }

        // A default folder that is not there: the problem names the full expected folder.
        controller.usePlaylist(PlaylistRef("late-env", "Late Environment", "Not Copied Yet"))
        val missing = File(library, "Not Copied Yet")
        val problem = awaitState<MusicState.Problem>("missing playlist problem") { true }
        assertEquals(missing.path, problem.folder)
        assertTrue(problem.message, problem.message.startsWith("No music found in ${missing.path}"))

        // It appears; a retry (what returning to the app does) recovers without a new controller.
        missing.mkdirs()
        File(missing, "01 - Arrived.wav").writeBytes(toneWav(seconds = 1.0, hz = 500.0))
        controller.start()
        val recovered = awaitState<MusicState.Playing>("recovered") { true }
        assertEquals("Arrived", recovered.track.title)

        // Changing the root in settings finds the same folder under the new root.
        val library2 = File(root, "library2")
        File(library2, "Not Copied Yet").mkdirs()
        File(library2, "Not Copied Yet/02 - Second Root.wav").writeBytes(toneWav(seconds = 1.0, hz = 600.0))
        File(library2, "Demo Album B").mkdirs()
        File(library2, "Demo Album B/01 - Wrong.wav").writeBytes(toneWav(seconds = 1.0, hz = 700.0))
        MusicSettings(context).folder = library2.path
        val moved = awaitState<MusicState.Playing>("playlist under the new root") { it.track.path.startsWith(library2.path) }
        assertEquals("Second Root", moved.track.title)
        assertEquals(1, controller.trackCount)

        // Back to the whole root: both folders.
        controller.usePlaylist(null)
        awaitState<MusicState.Playing>("whole root") { controller.trackCount == 2 }
    }

    /**
     * A saved selection of two folders with unticked songs: only ticked songs from those folders play,
     * a change made while playing (as the settings screen makes it) is followed without restarting,
     * and stop and start work.
     */
    @Test
    fun twoFolderSelectionWithExclusionsPlaysOnlyTickedSongs() {
        val library = File(root, "library")
        val a = File(library, PLAYLIST).apply { mkdirs() }
        val b = File(library, "Demo Album B").apply { mkdirs() }
        val c = File(library, "Demo Album C").apply { mkdirs() }
        // The same file names in every folder: they are different songs.
        for (dir in listOf(a, b, c)) {
            for (i in 1..4) File(dir, "0$i - Song $i.wav").writeBytes(toneWav(seconds = 1.0, hz = 200.0 + 60 * i))
        }
        val playlist = PlaylistRef("test-env", "Test Environment", PLAYLIST)
        val settings = MusicSettings(context)
        settings.folder = library.path
        val selection = settings.selections.load(playlist)
            .setIncluded(library.path, a, "02 - Song 2.wav", false)
            .setIncluded(library.path, a, "04 - Song 4.wav", false)
            .add(library.path, b)
            .setIncluded(library.path, b, "01 - Song 1.wav", false)
        settings.selections.save(playlist.id, selection)
        val allowed = setOf(
            File(a, "01 - Song 1.wav").path, File(a, "03 - Song 3.wav").path,
            File(b, "02 - Song 2.wav").path, File(b, "03 - Song 3.wav").path, File(b, "04 - Song 4.wav").path,
        )

        // The menu line, worked out in the background.
        val summary = awaitSummary(playlist) { !it.pending }
        assertEquals(PlaylistSummary(2, 5, 8), summary)
        assertEquals("5 of 8 songs from 2 folders", summary.text)
        assertEquals(MusicState.Idle, controller.state)

        controller.usePlaylist(playlist)
        controller.start()
        var last = awaitState<MusicState.Playing>("first selected track") { true }
        assertEquals(5, controller.trackCount)
        val played = HashSet<String>()
        repeat(40) {
            assertTrue("played ${last.track.path}", last.track.path in allowed)
            played += last.track.path
            val before = last.track.path
            controller.skip()
            last = awaitState("track after skip") { it.track.path != before }
        }
        assertEquals("every ticked song played", allowed, played)

        // Untick a song that is not playing: the current track carries on (no new prepare).
        val current = last.track.path
        val notPlaying = allowed.first { it != current }
        val readyBefore = controller.readyCount
        var edited = selection.setIncluded(library.path, File(notPlaying).parentFile!!, File(notPlaying).name, false)
        settings.selections.save(playlist.id, edited)
        awaitTrue("selection followed") { controller.trackCount == 4 }
        assertEquals(current, (controller.state as MusicState.Playing).track.path)
        assertEquals("the playing track was not restarted", readyBefore, controller.readyCount)
        assertEquals(PlaylistSummary(2, 4, 8), awaitSummary(playlist) { it.selectedCount == 4 })

        // Untick the playing song: playback moves to a ticked one.
        edited = edited.setIncluded(library.path, File(current).parentFile!!, File(current).name, false)
        settings.selections.save(playlist.id, edited)
        last = awaitState("moved off the unticked song") { it.track.path != current }
        val stillAllowed = allowed - current - notPlaying
        repeat(20) {
            assertTrue("played ${last.track.path}", last.track.path in stillAllowed)
            val before = last.track.path
            controller.skip()
            last = awaitState("track after skip") { it.track.path != before }
        }

        // Replace both folders with a third: only it plays.
        edited = edited.remove(library.path, a).remove(library.path, b).add(library.path, c)
        settings.selections.save(playlist.id, edited)
        last = awaitState("third folder") { it.track.path.startsWith(c.path) }
        assertEquals(4, controller.trackCount)

        // Nothing ticked: a problem naming the playlist; summary says so too.
        settings.selections.save(playlist.id, edited.excludeAll(library.path, c, c.list()!!.toList()))
        val problem = awaitState<MusicState.Problem>("nothing selected") { true }
        assertEquals("No songs selected for Test Environment. Open music settings and tick some songs.", problem.message)
        assertEquals("No songs selected", awaitSummary(playlist) { it.problem != null }.text)

        // Reset to default: the default folder, every song.
        settings.selections.reset(playlist.id)
        last = awaitState("default folder again") { it.track.path.startsWith(a.path) }
        assertEquals(4, controller.trackCount)

        // Stop (back to the picker) and start (another ride).
        controller.pause()
        awaitState<MusicState.Paused>("paused") { true }
        controller.stop()
        awaitTrue("idle after stop") { controller.state == MusicState.Idle }
        SystemClock.sleep(1500)
        assertEquals("stays stopped", MusicState.Idle, controller.state)
        controller.start()
        awaitState<MusicState.Playing>("plays again, unpaused") { it.track.path.startsWith(a.path) }
    }

    private fun awaitSummary(playlist: PlaylistRef, ok: (PlaylistSummary) -> Boolean): PlaylistSummary {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val s = controller.playlistSummary(playlist)
            if (ok(s)) return s
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for the summary; last=${controller.playlistSummary(playlist)}")
        error("unreachable")
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 15_000, ok: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (ok()) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $what; state=${controller.state} trackCount=${controller.trackCount}")
    }

    private inline fun <reified T : MusicState> awaitState(what: String, timeoutMs: Long = 15_000, ok: (T) -> Boolean): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val s = controller.state
            if (s is T && ok(s)) return s
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $what; state=${controller.state} readyCount=${controller.readyCount}")
        error("unreachable")
    }

    private companion object {
        /** Apostrophe, exclamation marks and spaces, as real folder names have. */
        const val PLAYLIST = "Demo Album's Greatest Hits!!"
    }

    /** 16-bit mono PCM WAV. */
    private fun toneWav(seconds: Double, hz: Double, sampleRate: Int = 44_100): ByteArray {
        val samples = (seconds * sampleRate).toInt()
        val dataBytes = samples * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataBytes)
        for (i in 0 until samples) {
            buf.putShort((sin(2 * PI * hz * i / sampleRate) * 0.3 * Short.MAX_VALUE).toInt().toShort())
        }
        return buf.array()
    }
}
