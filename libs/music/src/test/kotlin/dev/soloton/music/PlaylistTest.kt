package dev.soloton.music

import dev.soloton.api.MusicState
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Folder names under the music root and titles of untagged files. Choosing folders and songs is in [SelectionTest]. */
class PlaylistTest {
    @TempDir
    lateinit var tmp: File

    private val game = "Mike Tyson's Punch-Out!!"

    private fun touch(root: File, path: String): File = File(root, path).apply {
        parentFile.mkdirs()
        writeText("x")
    }

    /** What the controller does on every (re)scan. */
    private fun scan(session: MusicSession, root: File): File? {
        session.beginScan(root.path)
        return session.scanFinished(MusicScanner.scan(root))
    }

    private fun library(root: File = tmp): File {
        touch(root, "$game/01 - Demo Intro.mp3")
        touch(root, "$game/02 - Demo Song Three.mp3")
        touch(root, "$game/03 - Demo Champ's Theme.mp3")
        touch(root, "$game/game.json")
        touch(root, "Night Circuit/01 - Demo Song One.mp3")
        touch(root, "Night Circuit/02 - Demo Song Two.mp3")
        touch(root, "Coast Drive/01 - Seaside.mp3")
        touch(root, "loose.mp3")
        return root
    }

    @Test
    fun folderIsRootPlusSubfolderWithNamesTakenLiterally() {
        assertEquals("/sdcard/Music/soloton/$game", Playlist.folder("/sdcard/Music/soloton", game))
        assertEquals("/sdcard/Music/soloton/$game", Playlist.folder("/sdcard/Music/soloton/", "/$game/"))
        assertEquals("/m/a b (c) [d] \$e *.?", Playlist.folder("/m", "a b (c) [d] \$e *.?"))
        assertEquals("/m/Series/Part 2", Playlist.folder("/m", "Series/Part 2"))
    }

    @Test
    fun nullOrBlankSubfolderMeansTheWholeRoot() {
        assertEquals("/m", Playlist.folder("/m", null))
        assertEquals("/m", Playlist.folder("/m", ""))
        assertEquals("/m", Playlist.folder("/m", "  "))
        assertEquals("/m", Playlist.folder("/m", "/"))
        assertNull(Playlist.normalize(" / "))
    }

    @Test
    fun nullPlaylistPlaysTheWholeRootRecursively() {
        library()
        val session = MusicSession(Random(2))
        assertNotNull(scan(session, tmp))
        assertEquals(tmp.path, session.folder)
        assertEquals(7, session.trackCount)
    }

    @Test
    fun untaggedTitlesDropALeadingTrackNumber() {
        val cases = mapOf(
            "01 - Song" to "Song",
            "1. Song" to "Song",
            "01_Song" to "Song",
            "1.Song" to "Song",
            "12-Song" to "Song",
            "003 - Demo Rival's Theme" to "Demo Rival's Theme",
            "11 - Wake Up Demo Song!!" to "Wake Up Demo Song!!",
            "03 - 1999" to "1999",
            "07 - 7 - Seven" to "7 - Seven",
            // Not track numbers: kept whole.
            "Song" to "Song",
            "01 Sunrise" to "01 Sunrise",
            "99 Balloons" to "99 Balloons",
            "1.5 Volts" to "1.5 Volts",
            "1999" to "1999",
            "2001 - A Space Odyssey" to "2001 - A Space Odyssey",
            "01 - " to "01 -",
            "- Song" to "- Song",
        )
        for ((name, title) in cases) assertEquals(title, Playlist.titleFromFileName(name), "for '$name'")
    }

    @Test
    fun untaggedTrackTakesItsTitleFromTheFileAndItsAlbumFromTheFolder() {
        val file = touch(tmp, "$game/19 - You've Finished the Demo Album!!.mp3")
        val track = MusicSession.fallbackTrack(file)
        assertEquals("You've Finished the Demo Album!!", track.title)
        assertEquals(game, track.album)
        assertNull(track.artist)
        assertEquals(file.absolutePath, track.path)

        // Tags still win when a file has them.
        val tagged = MusicSession.withTags(track, "Tagged Title", "Composer", "Tagged Album")
        assertEquals("Tagged Title", tagged.title)
        assertEquals("Tagged Album", tagged.album)
    }

    @Test
    fun playingStateCarriesTheStrippedTitleAndFolderAlbum() {
        touch(tmp, "$game/04 - Demo Sprint.mp3")
        val session = MusicSession()
        assertNotNull(scan(session, File(tmp, game)))
        val track = assertIs<MusicState.Playing>(session.state).track
        assertEquals("Demo Sprint", track.title)
        assertEquals(game, track.album)
    }
}
