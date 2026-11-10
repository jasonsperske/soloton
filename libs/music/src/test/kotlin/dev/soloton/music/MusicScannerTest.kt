package dev.soloton.music

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MusicScannerTest {
    @TempDir
    lateinit var tmp: File

    private fun touch(path: String): File = File(tmp, path).apply {
        parentFile.mkdirs()
        writeText("x")
    }

    @Test
    fun findsNestedAudioFilesOfEverySupportedExtension() {
        val expected = listOf(
            "a.mp3", "b.OGG", "album/c.flac", "album/d.m4a", "album/disc 2/e.aac", "f.wav", "deep/er/still/g.opus",
        ).map { touch(it) }
        touch("cover.jpg")
        touch("notes.txt")
        touch("album/playlist.m3u")
        touch("noextension")

        val result = assertIs<ScanResult.Found>(MusicScanner.scan(tmp))
        assertEquals(expected.map { it.path }.sorted(), result.files.map { it.path })
    }

    @Test
    fun ignoresHiddenFilesAndFolders() {
        val visible = touch("song.mp3")
        touch(".hidden.mp3")
        touch(".thumbnails/x.mp3")
        touch("album/.trash/y.flac")

        val result = assertIs<ScanResult.Found>(MusicScanner.scan(tmp))
        assertEquals(listOf(visible.path), result.files.map { it.path })
    }

    @Test
    fun audioExtensionDirectoryIsNotATrack() {
        File(tmp, "weird.mp3").mkdirs()
        assertEquals(ScanResult.Empty, MusicScanner.scan(tmp))
    }

    @Test
    fun missingFolder() {
        assertEquals(ScanResult.Missing, MusicScanner.scan(File(tmp, "nope")))
    }

    @Test
    fun emptyFolderAndFolderWithOnlyOtherFiles() {
        assertEquals(ScanResult.Empty, MusicScanner.scan(tmp))
        touch("sub/readme.txt")
        assertEquals(ScanResult.Empty, MusicScanner.scan(tmp))
    }

    @Test
    fun fileInsteadOfFolder() {
        assertEquals(ScanResult.NotADirectory, MusicScanner.scan(touch("song.mp3")))
    }
}
