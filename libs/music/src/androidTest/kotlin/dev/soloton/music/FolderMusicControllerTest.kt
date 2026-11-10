package dev.soloton.music

import android.Manifest
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.soloton.api.MusicState
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
