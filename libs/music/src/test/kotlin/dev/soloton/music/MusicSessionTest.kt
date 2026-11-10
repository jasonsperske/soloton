package dev.soloton.music

import dev.soloton.api.MusicState
import dev.soloton.api.Track
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicSessionTest {
    @TempDir
    lateinit var tmp: File

    private fun touch(path: String): File = File(tmp, path).apply {
        parentFile.mkdirs()
        writeText("x")
    }

    private fun scanned(session: MusicSession, folder: File = tmp, granted: Boolean = true): File? {
        session.beginScan(folder.path)
        return session.scanFinished(MusicScanner.scan(folder), granted)
    }

    @Test
    fun failureCounterStopsAtCapAndResetsOnSuccess() {
        val c = FailureCounter(5)
        repeat(4) { assertFalse(c.recordFailure()) }
        c.recordSuccess()
        repeat(4) { assertFalse(c.recordFailure()) }
        assertTrue(c.recordFailure())
        assertEquals(5, c.consecutive)
    }

    @Test
    fun playsFromAFolderWithFallbackTitleAndAlbum() {
        touch("Road Trip/01 Sunrise.mp3")
        val session = MusicSession(Random(1))
        val first = assertNotNull(scanned(session))
        assertEquals(1, session.trackCount)
        val playing = assertIs<MusicState.Playing>(session.state)
        assertEquals(Track("01 Sunrise", null, "Road Trip", first.absolutePath), playing.track)

        session.setPaused(true)
        assertIs<MusicState.Paused>(session.state)
    }

    @Test
    fun tagsOverrideFallbacksOnlyForTheCurrentTrack() {
        touch("a.mp3")
        touch("b.mp3")
        val session = MusicSession(Random(3))
        val first = assertNotNull(scanned(session))
        session.tagsRead(first, "  Real Title ", "Artist", "")
        val track = assertIs<MusicState.Playing>(session.state).track
        assertEquals("Real Title", track.title)
        assertEquals("Artist", track.artist)
        assertEquals(tmp.name, track.album) // blank tag falls back to the folder name

        val second = assertNotNull(session.advance())
        session.tagsRead(first, "Stale", null, null) // late result for the old track is ignored
        assertEquals(second.nameWithoutExtension, assertIs<MusicState.Playing>(session.state).track.title)
    }

    @Test
    fun fiveConsecutiveFailuresStopWithProblem() {
        repeat(3) { touch("t$it.mp3") }
        val session = MusicSession(Random(7))
        assertNotNull(scanned(session))
        repeat(4) { assertNotNull(session.trackFailed(), "failure ${it + 1} should skip to the next file") }
        assertNull(session.trackFailed())
        val problem = assertIs<MusicState.Problem>(session.state)
        assertEquals(tmp.path, problem.folder)
        assertTrue("5 files in a row" in problem.message, problem.message)
        assertNull(session.advance(), "no playback after giving up")
        assertNull(session.trackFailed())
    }

    @Test
    fun aSuccessResetsTheFailureRun() {
        repeat(2) { touch("t$it.mp3") }
        val session = MusicSession(Random(7))
        assertNotNull(scanned(session))
        repeat(20) {
            repeat(4) { assertNotNull(session.trackFailed()) }
            session.trackReady()
        }
        assertIs<MusicState.Playing>(session.state)
    }

    @Test
    fun missingFolderIsAProblemNamingTheFolder() {
        val missing = File(tmp, "not-there")
        val session = MusicSession()
        assertNull(scanned(session, missing))
        val problem = assertIs<MusicState.Problem>(session.state)
        assertEquals(missing.path, problem.folder)
        assertTrue(problem.message.startsWith("No music found in ${missing.path}"), problem.message)
        assertTrue("does not exist" in problem.message, problem.message)
    }

    @Test
    fun emptyFolderIsAProblemNamingTheFolder() {
        touch("cover.jpg")
        val session = MusicSession()
        assertNull(scanned(session))
        val problem = assertIs<MusicState.Problem>(session.state)
        assertTrue(problem.message.startsWith("No music found in ${tmp.path}"), problem.message)
        assertEquals(0, session.trackCount)
    }

    @Test
    fun unreadableFolderWithoutPermissionAsksForPermission() {
        val session = MusicSession()
        session.beginScan(tmp.path)
        assertNull(session.scanFinished(ScanResult.Unreadable, permissionGranted = false))
        val problem = assertIs<MusicState.Problem>(session.state)
        assertTrue("permission" in problem.message, problem.message)
        assertTrue(session.needsPermission)
    }

    @Test
    fun rescanClearsAProblem() {
        val session = MusicSession()
        assertNull(scanned(session))
        assertIs<MusicState.Problem>(session.state)
        touch("new.ogg")
        assertNotNull(scanned(session))
        assertIs<MusicState.Playing>(session.state)
    }

    @Test
    fun sessionShufflesWithoutRepeatsAcrossCycles() {
        val files = List(4) { touch("t$it.flac") }
        val session = MusicSession(Random(11))
        val played = mutableListOf(assertNotNull(scanned(session)))
        repeat(4 * 5 - 1) { played += assertNotNull(session.advance()) }
        played.chunked(4).forEach { assertEquals(files.toSet(), it.toSet()) }
        played.zipWithNext().forEach { (a, b) -> assertTrue(a != b, "repeat of $a") }
    }
}
