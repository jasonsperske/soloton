package dev.soloton.roadwork.desktop

import dev.soloton.api.MusicState
import dev.soloton.api.PlaylistRef
import dev.soloton.roadwork.Environments
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopLauncherTest {
    @Test
    fun parsesEnvironmentFlags() {
        assertNull(parseArgs(emptyArray()).env)
        assertEquals("riverfront", parseArgs(arrayOf("--env", "riverfront")).env)
        assertTrue(parseArgs(arrayOf("--list-envs")).listEnvs)
        assertFailsWith<IllegalArgumentException> { parseArgs(arrayOf("--env")) }
    }

    @Test
    fun riderViewIsTheDefaultAndOtherViewsPassThroughByName() {
        assertNull(parseArgs(emptyArray()).view)
        assertNull(parseArgs(arrayOf("--view", "rider")).view)
        assertEquals("jogger-side", parseArgs(arrayOf("--view", "jogger-side")).view)
    }

    @Test
    fun listsEveryEnvironmentWithItsMusicFolder() {
        val lines = describeEnvironments(Environments.all, Environments.default).trimEnd().lines()
        assertEquals(Environments.all.size, lines.size)
        // Columns are padded to the longest id and title.
        assertEquals(
            listOf("riverfront", "Riverfront Roadwork", "music: Mike Tyson's Punch-Out!!", "[default]"),
            lines.first().split(Regex(" {2,}")),
        )
        for ((line, e) in lines.zip(Environments.all)) {
            assertTrue(line.startsWith(e.id) && e.title in line && "music: ${e.musicFolder}" in line, line)
        }
    }

    @Test
    fun resistanceIsAPercentageFromZeroToAHundred() {
        assertEquals(0f, parseArgs(emptyArray()).resistancePercent)
        assertEquals(70f, parseArgs(arrayOf("--resistance", "70")).resistancePercent)
        assertEquals(12.5f, parseArgs(arrayOf("--env", "skylift-mesa", "--resistance", "12.5")).resistancePercent)
        for (bad in listOf("-1", "101", "high")) {
            assertFailsWith<IllegalArgumentException> { parseArgs(arrayOf("--resistance", bad)) }
        }
        assertFailsWith<IllegalArgumentException> { parseArgs(arrayOf("--resistance")) }
    }

    @Test
    fun theWindowOpensOnThePickerAndEnvScreenshotAndStatsGoStraightToARide() {
        assertTrue(parseArgs(emptyArray()).startsOnPicker)
        assertTrue(parseArgs(arrayOf("--silent-music")).startsOnPicker)
        assertFalse(parseArgs(arrayOf("--env", "riverfront")).startsOnPicker)
        assertFalse(parseArgs(arrayOf("--screenshot", "/tmp/x.png", "--at", "30")).startsOnPicker)
        assertFalse(parseArgs(arrayOf("--stats")).startsOnPicker)
        // --picker wins, so the picker itself can be screenshotted.
        assertTrue(parseArgs(arrayOf("--picker", "--screenshot", "/tmp/x.png")).startsOnPicker)
        assertTrue(parseArgs(arrayOf("--env", "riverfront", "--picker")).startsOnPicker)
    }

    @Test
    fun demoMusicIsMadeUpWithoutAMusicFolderAndTheSameForEveryPlaylist() {
        val music = DemoMusicController()
        val playlist = Environments.default.playlist
        assertEquals("3 of 3 songs from 1 folder", music.playlistSummary(playlist).text)
        music.usePlaylist(playlist)
        assertEquals(playlist, music.playlist)
        music.start()
        assertEquals("Harbour Lights", assertIs<MusicState.Playing>(music.state).track.title)
        music.skip()
        assertEquals("Second Wind", assertIs<MusicState.Playing>(music.state).track.title)
        music.pause()
        assertIs<MusicState.Paused>(music.state)
        music.stop()
        assertEquals(MusicState.Idle, music.state)
    }

    @Test
    fun demoMusicListsARealFolderWhenGivenOne(@TempDir dir: File) {
        val game = "Demo Album's Greatest Hits!!"
        for (name in listOf("02 - Second Song.mp3", "01 - First Song.ogg", "cover.png", "game.json", ".hidden.mp3")) {
            File(dir, "$game/$name").apply { parentFile.mkdirs() }.writeText("x")
        }
        File(dir, "Other Album/01 - Elsewhere.mp3").apply { parentFile.mkdirs() }.writeText("x")
        val music = DemoMusicController(dir)
        val playlist = PlaylistRef("demo", "Demo", game)

        assertEquals("2 of 2 songs from 1 folder", music.playlistSummary(playlist).text)
        music.usePlaylist(playlist)
        music.start()
        val first = assertIs<MusicState.Playing>(music.state).track
        assertEquals("First Song", first.title)
        assertEquals(game, first.album)
        music.skip()
        assertEquals("Second Song", assertIs<MusicState.Playing>(music.state).track.title)
        music.skip()
        assertEquals("First Song", assertIs<MusicState.Playing>(music.state).track.title, "wraps round within the folder")

        // A playlist whose folder is not there is a problem on the picker and in the HUD.
        val missing = PlaylistRef("gone", "Gone", "Not Copied")
        assertEquals("Music folder not found", music.playlistSummary(missing).text)
        music.usePlaylist(missing)
        music.start()
        assertEquals(File(dir, "Not Copied").path, assertIs<MusicState.Problem>(music.state).folder)

        // No default folder: everything under the music folder.
        music.usePlaylist(null)
        music.start()
        assertIs<MusicState.Playing>(music.state)
        assertEquals("3 of 3 songs from 1 folder", music.playlistSummary(PlaylistRef("all", "All", null)).text)
    }

    @Test
    fun musicFolderComesFromTheEnvironmentVariableOnlyWhenItIsAFolder(@TempDir dir: File) {
        assertNull(DemoMusicController.musicDirFromEnvironment(null))
        assertNull(DemoMusicController.musicDirFromEnvironment(""))
        assertNull(DemoMusicController.musicDirFromEnvironment("  "))
        assertNull(DemoMusicController.musicDirFromEnvironment(File(dir, "missing").path))
        assertEquals(dir, DemoMusicController.musicDirFromEnvironment(" ${dir.path} "))
    }
}
