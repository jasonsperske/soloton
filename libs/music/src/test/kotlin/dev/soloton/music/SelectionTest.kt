package dev.soloton.music

import dev.soloton.api.MusicState
import dev.soloton.api.PlaylistRef
import dev.soloton.api.PlaylistSummary
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A playlist's chosen folders and songs: saving, finding them on disk, what plays, and what a menu says. */
class SelectionTest {
    @TempDir
    lateinit var tmp: File

    private val game = "Mike Tyson's Punch-Out!!"
    private val ref = PlaylistRef("riverfront", "Riverfront Roadwork", game)
    private val backing = MemorySelectionBacking()
    private val store = SelectionStore(backing)

    private fun touch(root: File, path: String): File = File(root, path).apply {
        parentFile.mkdirs()
        writeText("x")
    }

    /** A root with the default folder (3 songs and a json file), two other game folders and a loose file. */
    private fun library(root: File = File(tmp, "root")): File {
        touch(root, "$game/01 - Demo Intro.mp3")
        touch(root, "$game/02 - Demo Song Three.mp3")
        touch(root, "$game/03 - Demo Champ's Theme.mp3")
        touch(root, "$game/game.json")
        touch(root, "Night Circuit/01 - Demo Song One.mp3")
        touch(root, "Night Circuit/02 - Demo Song Two.mp3")
        touch(root, "Coast Drive/01 - Demo Intro.mp3")
        touch(root, "loose.mp3")
        return root
    }

    private fun resolve(root: File, playlist: PlaylistRef = ref): ResolvedSelection =
        SelectionResolver.resolve(root.path, SelectionResolver.effective(store, root.path, playlist))

    private fun names(files: List<File>): List<String> = files.map { "${it.parentFile.name}/${it.name}" }

    // ---- defaults ----

    @Test
    fun withNothingSavedThePlaylistIsItsDefaultFolderWithEverySong() {
        val root = library()
        assertNull(store.saved(ref.id))
        assertEquals(MusicSelection(listOf(SelectedFolder(game))), store.load(ref))
        val resolved = resolve(root)
        assertEquals(1, resolved.folders.size)
        assertEquals(File(root, game), resolved.folders.single().dir)
        assertEquals(3, resolved.selectedCount)
        assertEquals(3, resolved.totalCount)
        assertNull(resolved.problem(ref.title))
        assertTrue(backing.values.isEmpty(), "looking does not save anything")
    }

    @Test
    fun aPlaylistWithoutADefaultFolderStartsAsTheRootAndEveryFolderOfSongsInIt() {
        val root = library()
        File(root, "Empty Folder").mkdirs()
        val everything = PlaylistRef("anywhere", "Anywhere", null)
        val resolved = resolve(root, everything)
        assertEquals(listOf(".", "Coast Drive", game, "Night Circuit"), resolved.folders.map { it.folder.path })
        assertEquals(7, resolved.selectedCount)
        assertEquals(MusicSelection(listOf(SelectedFolder("."))), MusicSelection.default(null))
    }

    @Test
    fun songsAreListedByNameWithoutDescendingAndOtherFilesAreIgnored() {
        val root = library()
        touch(root, "$game/Bonus/04 - Hidden Away.mp3")
        touch(root, "$game/.hidden.mp3")
        touch(root, "$game/10 - Ten.OGG")
        touch(root, "$game/cover.png")
        val folder = resolve(root).folders.single()
        assertEquals(listOf("01 - Demo Intro.mp3", "02 - Demo Song Three.mp3", "03 - Demo Champ's Theme.mp3", "10 - Ten.OGG"), folder.songs.map { it.name })
        assertEquals(listOf("Bonus"), folder.subfolders.map { it.name })
    }

    // ---- editing and saving ----

    @Test
    fun addAndRemoveFoldersKeepingTheirOrderAndNeverAddingOneTwice() {
        val root = library()
        var sel = store.load(ref)
        sel = sel.add(root.path, File(root, "Coast Drive")).add(root.path, File(root, "Night Circuit"))
        assertEquals(listOf(game, "Coast Drive", "Night Circuit"), sel.folders.map { it.path })

        // Already present, however the path is spelled: nothing changes.
        assertSame(sel, sel.add(root.path, File(root, "Night Circuit")))
        assertSame(sel, sel.add(root.path, File(root, "Coast Drive/../Night Circuit/")))
        assertSame(sel, sel.add(root.path, File(root.path + "/./$game")))

        store.save(ref.id, sel)
        assertEquals(sel, store.saved(ref.id))
        assertEquals(6, resolve(root).selectedCount)

        sel = sel.remove(root.path, File(root, "Coast Drive"))
        assertEquals(listOf(game, "Night Circuit"), sel.folders.map { it.path })
        assertSame(sel, sel.remove(root.path, File(root, "Never Added")))
        store.save(ref.id, sel)
        assertEquals(5, resolve(root).selectedCount)
    }

    @Test
    fun untickedSongsAreStoredAsExclusionsSoSongsCopiedInLaterAreIncluded() {
        val root = library()
        val dir = File(root, game)
        var sel = store.load(ref).setIncluded(root.path, dir, "02 - Demo Song Three.mp3", false)
        assertEquals(setOf("02 - Demo Song Three.mp3"), sel.folders.single().excluded)
        store.save(ref.id, sel)
        assertEquals(listOf("$game/01 - Demo Intro.mp3", "$game/03 - Demo Champ's Theme.mp3"), names(resolve(root).files))

        // A song copied over afterwards plays without a visit to settings.
        touch(root, "$game/04 - New Arrival.mp3")
        val resolved = resolve(root)
        assertEquals(3, resolved.selectedCount)
        assertEquals(4, resolved.totalCount)
        assertTrue("$game/04 - New Arrival.mp3" in names(resolved.files))

        // Tick it again; "None" and "All".
        sel = sel.setIncluded(root.path, dir, "02 - Demo Song Three.mp3", true)
        assertTrue(sel.folders.single().excluded.isEmpty())
        sel = sel.excludeAll(root.path, dir, resolved.folders.single().songs.map { it.name })
        assertEquals(4, sel.folders.single().excluded.size)
        store.save(ref.id, sel)
        assertEquals(0, resolve(root).selectedCount)
        sel = sel.includeAll(root.path, dir)
        store.save(ref.id, sel)
        assertEquals(4, resolve(root).selectedCount)
    }

    @Test
    fun editsToAFolderThatIsNotInTheSelectionChangeNothing() {
        val root = library()
        val sel = store.load(ref)
        assertSame(sel, sel.setIncluded(root.path, File(root, "Night Circuit"), "01 - Demo Song One.mp3", false))
        assertSame(sel, sel.includeAll(root.path, File(root, "Night Circuit")))
    }

    @Test
    fun resetForgetsTheSavedSelection() {
        val root = library()
        store.save(ref.id, store.load(ref).add(root.path, File(root, "Night Circuit")).setIncluded(root.path, File(root, game), "01 - Demo Intro.mp3", false))
        assertEquals(4, resolve(root).selectedCount)
        store.reset(ref.id)
        assertNull(store.saved(ref.id))
        assertEquals(3, resolve(root).selectedCount)
        assertEquals(listOf(game), resolve(root).folders.map { it.folder.path })
    }

    @Test
    fun selectionsAreKeptPerPlaylist() {
        val root = library()
        val other = PlaylistRef("night-circuit", "Night Circuit", "Night Circuit")
        store.save(ref.id, store.load(ref).setIncluded(root.path, File(root, game), "01 - Demo Intro.mp3", false))
        assertEquals(2, resolve(root).selectedCount)
        assertEquals(listOf("Night Circuit/01 - Demo Song One.mp3", "Night Circuit/02 - Demo Song Two.mp3"), names(resolve(root, other).files))
    }

    // ---- relative and absolute folders, and the root moving ----

    @Test
    fun foldersInsideTheRootAreStoredRelativeAndOthersAbsolute() {
        val root = library()
        val outside = File(tmp, "elsewhere/Demo's Album").apply { mkdirs() }
        assertEquals(game, FolderPaths.toStored(root.path, File(root, game)))
        assertEquals("Series/Part 2", FolderPaths.toStored(root.path, File(root, "Series/Part 2")))
        assertEquals(".", FolderPaths.toStored(root.path, root))
        assertEquals(".", FolderPaths.toStored(root.path + "/", File(root.path + "/.")))
        assertEquals(outside.absolutePath, FolderPaths.toStored(root.path, outside))
        // A sibling whose name merely starts with the root's name is not inside it.
        assertEquals(root.path + "-old/Game", FolderPaths.toStored(root.path, File(root.path + "-old/Game")))

        assertEquals(File(root, game), FolderPaths.resolve(root.path, game))
        assertEquals(root, FolderPaths.resolve(root.path, "."))
        assertEquals(outside, FolderPaths.resolve(root.path, outside.absolutePath))
    }

    @Test
    fun changingTheRootKeepsRelativeFoldersAndTheirExclusionsAndLeavesAbsoluteOnesAlone() {
        val rootA = library(File(tmp, "a"))
        val rootB = File(tmp, "b")
        touch(rootB, "$game/01 - Demo Intro.mp3")
        touch(rootB, "$game/02 - Demo Song Three.mp3")
        touch(rootB, "$game/07 - Only In B.mp3")
        val outside = File(tmp, "usb/Mixtape")
        touch(outside, "01 - Side A.mp3")

        var sel = store.load(ref)
            .setIncluded(rootA.path, File(rootA, game), "02 - Demo Song Three.mp3", false)
            .add(rootA.path, File(rootA, "Night Circuit"))
            .add(rootA.path, outside)
        store.save(ref.id, sel)
        assertEquals(listOf(game, "Night Circuit", outside.absolutePath), sel.folders.map { it.path })
        assertEquals(5, resolve(rootA).selectedCount)

        // Same saved selection, new root: the default folder is found under it with the same song unticked,
        // the folder that is not under the new root is missing, and the absolute folder still plays.
        val moved = resolve(rootB)
        assertEquals(
            listOf("$game/01 - Demo Intro.mp3", "$game/07 - Only In B.mp3", "Mixtape/01 - Side A.mp3"),
            names(moved.files),
        )
        assertEquals(listOf(false, true, false), moved.folders.map { it.missing })
        assertEquals(File(rootB, "Night Circuit"), moved.folders[1].dir)

        // And back again, nothing was lost.
        assertEquals(5, resolve(rootA).selectedCount)
        sel = store.saved(ref.id)!!
        assertEquals(3, sel.folders.size)
    }

    // ---- things that went away ----

    @Test
    fun aSavedFolderThatNoLongerExistsIsReportedMissingSkippedForPlaybackAndKept() {
        val root = library()
        store.save(ref.id, store.load(ref).add(root.path, File(root, "Night Circuit")).add(root.path, File(root, "Coast Drive")))
        File(root, "Night Circuit").deleteRecursively()

        val resolved = resolve(root)
        assertEquals(listOf(game, "Night Circuit", "Coast Drive"), resolved.folders.map { it.name })
        val gone = resolved.folders[1]
        assertTrue(gone.missing)
        assertEquals(ResolvedFolder.Status.MISSING, gone.status)
        assertTrue(gone.songs.isEmpty())
        assertEquals(4, resolved.selectedCount)
        assertEquals(4, resolved.totalCount)
        assertNull(resolved.problem(ref.title))
        assertEquals("4 of 4 songs from 3 folders", resolved.summary().text)
        assertEquals(3, store.saved(ref.id)!!.folders.size, "resolving never deletes a saved folder")

        // It comes back: it plays again, with nothing to redo.
        touch(root, "Night Circuit/01 - Demo Song One.mp3")
        assertEquals(5, resolve(root).selectedCount)
    }

    @Test
    fun aFileWhereAFolderShouldBeIsMissingToo() {
        val root = library()
        store.save(ref.id, MusicSelection(listOf(SelectedFolder("loose.mp3"))))
        val resolved = resolve(root)
        assertTrue(resolved.folders.single().missing)
        assertIs<MusicState.Problem>(resolved.problem(ref.title))
    }

    @Test
    fun exclusionsForFilesThatVanishedAreIgnoredAndKept() {
        val root = library()
        val dir = File(root, game)
        store.save(
            ref.id,
            store.load(ref)
                .setIncluded(root.path, dir, "02 - Demo Song Three.mp3", false)
                .setIncluded(root.path, dir, "99 - Deleted Long Ago.mp3", false),
        )
        File(dir, "02 - Demo Song Three.mp3").delete()
        val resolved = resolve(root)
        assertEquals(2, resolved.selectedCount)
        assertEquals(2, resolved.totalCount)
        assertEquals("2 of 2 songs from 1 folder", resolved.summary().text)
        // The file returns: it is still unticked.
        touch(root, "$game/02 - Demo Song Three.mp3")
        assertEquals(2, resolve(root).selectedCount)
        assertEquals(3, resolve(root).totalCount)
    }

    // ---- same name in two folders, awkward names ----

    @Test
    fun theSameFileNameInTwoFoldersIsTwoSongsTickedSeparately() {
        val root = library()
        val sel = store.load(ref)
            .add(root.path, File(root, "Coast Drive"))
            .setIncluded(root.path, File(root, "Coast Drive"), "01 - Demo Intro.mp3", false)
        store.save(ref.id, sel)
        val resolved = resolve(root)
        assertEquals(4, resolved.totalCount)
        assertTrue("$game/01 - Demo Intro.mp3" in names(resolved.files))
        assertFalse("Coast Drive/01 - Demo Intro.mp3" in names(resolved.files))

        store.save(ref.id, sel.includeAll(root.path, File(root, "Coast Drive")).setIncluded(root.path, File(root, game), "01 - Demo Intro.mp3", false))
        val swapped = names(resolve(root).files)
        assertFalse("$game/01 - Demo Intro.mp3" in swapped)
        assertTrue("Coast Drive/01 - Demo Intro.mp3" in swapped)
    }

    @Test
    fun aFolderListedTwiceUnderDifferentSpellingsPlaysEachSongOnce() {
        val root = library()
        store.save(ref.id, MusicSelection(listOf(SelectedFolder(game), SelectedFolder(File(root, game).absolutePath))))
        val resolved = resolve(root)
        assertEquals(3, resolved.selectedCount)
        assertEquals(3, resolved.totalCount)
    }

    @Test
    fun namesWithApostrophesExclamationMarksSpacesQuotesAndNonAsciiSurviveSavingAndResolving() {
        val root = File(tmp, "My Music (2024)")
        val names = listOf(
            game,
            "Démo Álbum – Ça & Là",
            "デモ アルバム 三",
            "It's \"Quoted\" \\ back\\slash",
            "  spaces  around  ",
            "tab\there",
            "100% [Final] {Mix} \$HOME *?",
        )
        var sel = MusicSelection(emptyList())
        for (n in names) {
            touch(root, "$n/01 - Ünïcödé's Song!!.mp3")
            touch(root, "$n/02 - \"Second\" Song.mp3")
            sel = sel.add(root.path, File(root, n)).setIncluded(root.path, File(root, n), "02 - \"Second\" Song.mp3", false)
        }
        assertEquals(names, sel.folders.map { it.path })
        store.save(ref.id, sel)

        val text = backing.read(ref.id)!!
        assertEquals(sel, SelectionCodec.decode(text))
        assertEquals(sel, store.saved(ref.id))
        val resolved = resolve(root)
        assertEquals(names.size, resolved.selectedCount)
        assertEquals(names.size * 2, resolved.totalCount)
        assertTrue(resolved.files.all { it.name == "01 - Ünïcödé's Song!!.mp3" })
        assertTrue(resolved.folders.none { it.missing })
    }

    @Test
    fun codecWritesReadableJsonAndRejectsAnythingElse() {
        val sel = MusicSelection(listOf(SelectedFolder(game, setOf("b.mp3", "a.mp3")), SelectedFolder("/abs/Other")))
        assertEquals(
            """{"folders":[{"path":"Mike Tyson's Punch-Out!!","excluded":["a.mp3","b.mp3"]},{"path":"/abs/Other","excluded":[]}]}""",
            SelectionCodec.encode(sel),
        )
        assertEquals(sel, SelectionCodec.decode(""" { "folders" : [ {"excluded":["b.mp3","a.mp3"], "path":"$game"}, {"path":"\/abs\/Other"} ] } """))
        assertEquals(MusicSelection(emptyList()), SelectionCodec.decode("""{"folders":[]}"""))
        assertEquals(MusicSelection(listOf(SelectedFolder("é\n"))), SelectionCodec.decode("""{"folders":[{"path":"é\n"}]}"""))
        for (bad in listOf("", "null", "[]", "{}", """{"folders":"x"}""", """{"folders":[{"excluded":[]}]}""", """{"folders":[{"path":"a"}]} trailing""", """{"folders":[{"path":"a"""")) {
            assertNull(SelectionCodec.decode(bad), "decode($bad)")
        }
        // An entry that cannot be read is treated as "nothing saved": the default folder plays.
        backing.write(ref.id, "not json")
        assertNull(store.saved(ref.id))
        assertEquals(MusicSelection.default(game), store.load(ref))
    }

    // ---- what plays ----

    @Test
    fun onlyTickedSongsFromTheChosenFoldersAreEverPlayedAcrossManyShuffles() {
        val root = library()
        val sel = store.load(ref)
            .setIncluded(root.path, File(root, game), "02 - Demo Song Three.mp3", false)
            .add(root.path, File(root, "Night Circuit"))
            .setIncluded(root.path, File(root, "Night Circuit"), "01 - Demo Song One.mp3", false)
        store.save(ref.id, sel)
        val resolved = resolve(root)
        val allowed = setOf("$game/01 - Demo Intro.mp3", "$game/03 - Demo Champ's Theme.mp3", "Night Circuit/02 - Demo Song Two.mp3")
        assertEquals(allowed, names(resolved.files).toSet())

        for (seed in 0 until 25) {
            val session = MusicSession(Random(seed))
            session.beginScan(root.path)
            val played = mutableListOf(assertNotNull(session.filesLoaded(resolved.files, resolved.folderLabel, resolved.problem(ref.title))))
            repeat(60) { played += assertNotNull(session.advance()) }
            assertEquals(allowed, names(played).toSet(), "seed $seed: exactly the ticked songs, all of them")
            played.zipWithNext().forEach { (a, b) -> assertTrue(a != b, "seed $seed: repeat of $a") }
            // No repeats within a pass through the three songs.
            played.chunked(3).filter { it.size == 3 }.forEach { assertEquals(3, it.toSet().size, "seed $seed: $it") }
            assertEquals(3, session.trackCount)
            // A failed file skips to another ticked song.
            assertTrue("${session.trackFailed()!!.parentFile.name}/${session.currentFile!!.name}" in allowed)
        }
    }

    @Test
    fun changingTheSelectionWhilePlayingLetsATickedTrackCarryOnAndMovesOffAnUntickedOne() {
        val root = library()
        val dir = File(root, game)
        val session = MusicSession(Random(7))
        session.beginScan(root.path)
        val first = resolve(root)
        val current = assertNotNull(session.filesLoaded(first.files, first.folderLabel, first.problem(ref.title)))

        // Untick a different song and add a folder: the current track is untouched.
        val other = first.files.first { it != current }
        store.save(ref.id, store.load(ref).setIncluded(root.path, dir, other.name, false).add(root.path, File(root, "Night Circuit")))
        var now = resolve(root)
        assertTrue(session.filesChanged(now.files, now.folderLabel, now.problem(ref.title)))
        assertEquals(current, session.currentFile)
        assertEquals(4, session.trackCount)
        val next = assertNotNull(session.advance())
        assertTrue(next != current, "the new shuffle does not start with the track that is playing")
        repeat(40) { assertTrue(assertNotNull(session.advance()) != other, "an unticked song played") }

        // Untick the song that is playing: playback moves to another ticked one.
        val playing = session.currentFile!!
        store.save(ref.id, store.saved(ref.id)!!.setIncluded(root.path, playing.parentFile, playing.name, false))
        now = resolve(root)
        assertFalse(session.filesChanged(now.files, now.folderLabel, now.problem(ref.title)))
        val moved = assertNotNull(session.currentFile)
        assertTrue(moved != playing && moved in now.files)
        assertIs<MusicState.Playing>(session.state)

        // Untick everything: a clear problem; tick one again: it plays.
        var sel = store.saved(ref.id)!!
        for (f in now.folders) sel = sel.excludeAll(root.path, f.dir, f.songs.map { it.name })
        store.save(ref.id, sel)
        now = resolve(root)
        assertFalse(session.filesChanged(now.files, now.folderLabel, now.problem(ref.title)))
        assertNull(session.currentFile)
        val problem = assertIs<MusicState.Problem>(session.state)
        assertEquals("No songs selected for Riverfront Roadwork. Open music settings and tick some songs.", problem.message)
        assertEquals(dir.path, problem.folder)

        store.save(ref.id, sel.setIncluded(root.path, dir, "01 - Demo Intro.mp3", true))
        now = resolve(root)
        assertFalse(session.filesChanged(now.files, now.folderLabel, now.problem(ref.title)))
        assertEquals(File(dir, "01 - Demo Intro.mp3"), session.currentFile)
        assertIs<MusicState.Playing>(session.state)
    }

    @Test
    fun resetReturnsTheSessionToIdleAndUnpaused() {
        val root = library()
        val session = MusicSession(Random(1))
        session.beginScan(root.path)
        val resolved = resolve(root)
        session.filesLoaded(resolved.files, resolved.folderLabel, null)
        session.setPaused(true)
        assertIs<MusicState.Paused>(session.state)
        session.reset()
        assertSame(MusicState.Idle, session.state)
        assertFalse(session.paused)
        assertNull(session.advance())
        assertEquals(0, session.trackCount)
    }

    // ---- problems and the menu line ----

    @Test
    fun problemsSayWhatIsWrong() {
        val root = library()
        val title = ref.title

        // The default folder is not there: the existing "no music found in <folder>" message, naming it.
        val missingRef = PlaylistRef("x", "Somewhere", "Not Copied Yet")
        val missing = resolve(root, missingRef)
        val expected = File(root, "Not Copied Yet").path
        val p1 = assertNotNull(missing.problem("Somewhere"))
        assertEquals(expected, p1.folder)
        assertTrue(p1.message.startsWith("No music found in $expected. The folder does not exist."), p1.message)
        assertEquals("Music folder not found", missing.summary().text)

        // A folder with no audio in it.
        touch(root, "Just Notes/readme.txt")
        store.save(ref.id, MusicSelection(listOf(SelectedFolder("Just Notes"))))
        val empty = resolve(root)
        assertTrue(empty.problem(title)!!.message.startsWith("No music found in ${File(root, "Just Notes").path}. Copy "))
        assertEquals("No songs in the chosen folder", empty.summary().text)

        // No folders at all.
        store.save(ref.id, MusicSelection(emptyList()))
        val none = resolve(root)
        assertEquals("No folders chosen for $title. Open music settings and add a folder.", none.problem(title)!!.message)
        assertEquals(root.path, none.problem(title)!!.folder)
        assertEquals("No folders chosen", none.summary().text)
        assertEquals(PlaylistSummary(0, 0, 0, "No folders chosen"), none.summary())

        // Every song unticked: the problem names the environment.
        val dir = File(root, game)
        store.save(ref.id, MusicSelection.default(game).excludeAll(root.path, dir, dir.list()!!.toList()))
        val unticked = resolve(root)
        assertEquals("No songs selected for $title. Open music settings and tick some songs.", unticked.problem(title)!!.message)
        assertEquals("No songs selected", unticked.summary().text)
        assertEquals(PlaylistSummary(1, 0, 3, "No songs selected"), unticked.summary())

        // Several folders, all gone.
        store.save(ref.id, MusicSelection(listOf(SelectedFolder("Gone 1"), SelectedFolder("Gone 2"))))
        val gone = resolve(root)
        assertTrue(gone.problem(title)!!.message.startsWith("None of the 2 folders chosen for $title can be found."))
        assertEquals("Music folders not found", gone.summary().text)

        // Without storage permission nothing can be listed: say that instead.
        assertTrue(missing.problem("Somewhere", permissionGranted = false)!!.message.startsWith("Music needs permission"))
        assertEquals("Allow storage access to play music", missing.summary(permissionGranted = false).text)
    }

    @Test
    fun summaryLineCountsSongsAndFolders() {
        assertEquals("14 of 20 songs from 1 folder", PlaylistSummary(1, 14, 20).text)
        assertEquals("1 of 1 song from 1 folder", PlaylistSummary(1, 1, 1).text)
        assertEquals("0 of 20 songs from 3 folders", PlaylistSummary(3, 0, 20).text)
        assertEquals("No songs selected", PlaylistSummary(1, 0, 20, problem = "No songs selected").text)
        assertEquals("Looking for music...", PlaylistSummary.PENDING.text)

        val root = library()
        assertEquals("3 of 3 songs from 1 folder", resolve(root).summary().text)
        store.save(ref.id, store.load(ref).add(root.path, File(root, "Night Circuit")).setIncluded(root.path, File(root, game), "01 - Demo Intro.mp3", false))
        assertEquals("4 of 5 songs from 2 folders", resolve(root).summary().text)
        assertNull(resolve(root).summary().problem)
    }

    @Test
    fun shuffleBagDoesNotStartWithTheTrackThatWasJustPlaying() {
        repeat(50) { seed ->
            val bag = ShuffleBag(listOf("a", "b", "c"), Random(seed), justPlayed = "b")
            assertTrue(bag.next() != "b", "seed $seed")
        }
        assertEquals("only", ShuffleBag(listOf("only"), Random(1), justPlayed = "only").next())
    }
}
