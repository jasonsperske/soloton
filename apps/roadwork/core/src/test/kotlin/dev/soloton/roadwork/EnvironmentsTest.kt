package dev.soloton.roadwork

import dev.soloton.api.MusicController
import dev.soloton.api.MusicState
import dev.soloton.api.PlaylistRef
import dev.soloton.api.PlaylistSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Records every call, in order. */
internal class FakeMusic(private val log: MutableList<String>) : MusicController {
    override val state: MusicState = MusicState.Idle
    /** The default folder of each playlist asked for. */
    val playlists = ArrayList<String?>()
    val refs = ArrayList<PlaylistRef?>()
    val summariesAsked = ArrayList<String>()
    override fun start() { log += "music.start" }
    override fun stop() { log += "music.stop" }
    override fun usePlaylist(playlist: PlaylistRef?) {
        refs += playlist
        playlists += playlist?.defaultFolder
        log += "music.usePlaylist(${playlist?.defaultFolder})"
    }
    override fun playlistSummary(playlist: PlaylistRef): PlaylistSummary {
        summariesAsked += playlist.id
        return PlaylistSummary(1, 2, 3)
    }
    override fun skip() {}
    override fun pause() { log += "music.pause" }
    override fun resume() { log += "music.resume" }
}

internal class FakeLight : SceneLight {
    override val sunDirection = com.badlogic.gdx.math.Vector3(0f, 1f, 0f)
    override val sunColor = com.badlogic.gdx.graphics.Color()
    override val ambientSky = com.badlogic.gdx.graphics.Color()
    override val ambientGround = com.badlogic.gdx.graphics.Color()
}

internal class FakeScene(
    val name: String,
    val seed: Long,
    private val log: MutableList<String>,
    private val views: List<String> = emptyList(),
) : EnvironmentScene {
    var created = false
    var disposed = false
    var steps = 0
    var size: Pair<Int, Int>? = null
    var view: String? = null
    var createdAtSeconds = -1.0

    /** The rider's choices as last passed in, or null if they never were. */
    var options: Map<String, String>? = null
    override fun applyOptions(options: Map<String, String>) {
        this.options = options
        log += "$name.options"
    }

    override val light: SceneLight = FakeLight()
    override val inspectionViews: List<String> get() = views
    override val riderView: Boolean get() = view == null

    override fun create(ride: RideState) {
        check(!created && !disposed)
        created = true
        createdAtSeconds = ride.elapsedSeconds
        log += "$name.create"
    }
    override fun resize(width: Int, height: Int) { size = width to height }
    override fun step(dt: Float, ride: RideState) {
        check(created && !disposed) { "$name stepped while not live" }
        steps++
    }
    override fun draw(ride: RideState) { check(created && !disposed) }
    override fun setInspectionView(name: String?): Boolean {
        if (name != null && name !in views) return false
        view = name
        return true
    }
    override fun dispose() {
        check(!disposed) { "$name disposed twice" }
        disposed = true
        log += "$name.dispose"
    }
}

class EnvironmentsTest {
    private val log = ArrayList<String>()
    private val scenes = ArrayList<FakeScene>()

    private fun env(id: String, folder: String?, views: List<String> = emptyList()) =
        Environment(id, "Title of $id", folder) { seed -> FakeScene(id, seed, log, views).also { scenes += it } }

    // ---- registry ----

    @Test
    fun idsAreUniqueKebabCaseAndTitlesAreSet() {
        val all = Environments.all
        assertTrue(all.isNotEmpty())
        assertEquals(all.size, all.map { it.id }.toSet().size, "duplicate environment id")
        assertEquals(all.size, all.map { it.title }.toSet().size, "duplicate environment title")
        for (e in all) {
            assertTrue(Regex("[a-z0-9]+(-[a-z0-9]+)*").matches(e.id), "'${e.id}' is not kebab-case")
            assertTrue(e.title.isNotBlank())
            // A music folder is one folder name under the music root, not a path.
            e.musicFolder?.let { assertTrue(it.isNotBlank() && '/' !in it && it == it.trim(), "music folder '$it'") }
        }
    }

    @Test
    fun idsMustBeKebabCase() {
        for (bad in listOf("", "Riverfront", "river_front", "river front", "-river", "river-", "river--front", "río")) {
            assertFalse(Environment.isValidId(bad), "'$bad'")
            assertFailsWith<IllegalArgumentException> { env(bad, null) }
        }
        for (good in listOf("riverfront", "night-ride", "circuit-2", "64")) assertTrue(Environment.isValidId(good), good)
    }

    @Test
    fun defaultResolvesAndIsTheRiverfront() {
        val d = Environments.default
        assertSame(d, Environments.byId(d.id))
        assertSame(d, Environments.all.first())
        assertEquals("riverfront", d.id)
        assertEquals("Riverfront Roadwork", d.title)
        assertEquals("Mike Tyson's Punch-Out!!", d.musicFolder)
    }

    @Test
    fun anEnvironmentsPlaylistIsKeyedByItsIdAndStartsFromItsMusicFolder() {
        for (e in Environments.all) assertEquals(PlaylistRef(e.id, e.title, e.musicFolder), e.playlist)
        assertEquals(PlaylistRef("c", "Title of c", null), env("c", null).playlist)
    }

    @Test
    fun everyRegisteredIdResolvesToItself() {
        for (e in Environments.all) {
            assertSame(e, Environments.byId(e.id))
            assertSame(e, Environments.find(e.id))
        }
    }

    @Test
    fun unknownIdFallsBackToTheDefault() {
        for (id in listOf("no-such-place", "", "RIVERFRONT", " riverfront", null)) {
            assertSame(Environments.default, Environments.byId(id), "byId($id)")
            assertNull(Environments.find(id), "find($id)")
        }
    }

    @Test
    fun registeredScenesCanBeMadeWithoutOpenGl() {
        // Launchers ask a scene for its camera names before any GL context exists.
        for (e in Environments.all) {
            val scene = e.createScene(RoadworkGame.DEFAULT_SEED)
            for (v in scene.inspectionViews) assertTrue(Environment.isValidId(v), "view '$v' of ${e.id}")
            assertFalse(scene.setInspectionView("no-such-view"))
            assertTrue(scene.setInspectionView(null))
        }
        // The riverfront is seen from the saddle, handlebars and all; the mesa follows its runner instead.
        assertTrue(Environments.byId("riverfront").createScene(RoadworkGame.DEFAULT_SEED).riderView)
        assertFalse(Environments.byId("skylift-mesa").createScene(RoadworkGame.DEFAULT_SEED).riderView)
    }

    // ---- switching ----

    @Test
    fun openSelectsThePlaylistThenBuildsTheScene() {
        val a = env("a", "Game A's Music!!")
        val host = EnvironmentHost(a, FakeMusic(log), RideState(3f), seed = 42L)
        assertNull(host.scene)
        host.resize(1920, 1080)
        host.open()
        assertEquals(listOf("music.usePlaylist(Game A's Music!!)", "a.options", "a.create"), log)
        val scene = scenes.single()
        assertSame(scene, host.scene)
        assertEquals(42L, scene.seed)
        assertEquals(1920 to 1080, scene.size, "a size known before open reaches the scene")
        assertSame(a, host.environment)
    }

    @Test
    fun switchUsesTheNewPlaylistDisposesTheOldSceneAndBuildsTheNewOne() {
        val a = env("a", "Game A's Music!!")
        val b = env("b", "Game B")
        val music = FakeMusic(log)
        val ride = RideState(3f)
        val host = EnvironmentHost(a, music, ride, seed = 7L)
        host.open()
        host.resize(1280, 720)
        repeat(120) { ride.step(1f / 60f, 3f); host.step(1f / 60f) }
        val old = scenes.single()
        assertEquals(120, old.steps)
        log.clear()

        assertTrue(host.switchTo(b))

        assertEquals(listOf("music.usePlaylist(Game B)", "a.dispose", "b.options", "b.create"), log)
        assertTrue(old.disposed)
        val new = scenes.last()
        assertSame(new, host.scene)
        assertSame(b, host.environment)
        assertTrue(new.created && !new.disposed)
        assertEquals(7L, new.seed)
        assertEquals(1280 to 720, new.size, "the new scene gets the current size")
        // The ride carries on: the new scene starts two seconds in and takes the following steps.
        assertEquals(2.0, new.createdAtSeconds, 0.01)
        assertEquals(2.0, ride.elapsedSeconds, 0.01)
        host.step(1f / 60f)
        assertEquals(1, new.steps)
        assertEquals(120, old.steps, "a disposed scene is never stepped again")
        assertEquals(listOf<String?>("Game A's Music!!", "Game B"), music.playlists)
        assertEquals(listOf<PlaylistRef?>(a.playlist, b.playlist), music.refs)
    }

    @Test
    fun switchingToAnEnvironmentWithoutAMusicFolderPlaysTheWholeRoot() {
        val a = env("a", "Game A")
        val c = env("c", null)
        val music = FakeMusic(log)
        val host = EnvironmentHost(a, music, RideState(3f), 1L)
        host.open()
        assertTrue(host.switchTo(c))
        assertEquals(listOf<String?>("Game A", null), music.playlists)
    }

    @Test
    fun switchingToTheCurrentEnvironmentDoesNothing() {
        val a = env("a", "Game A")
        val host = EnvironmentHost(a, FakeMusic(log), RideState(3f), 1L)
        host.open()
        log.clear()
        assertFalse(host.switchTo(a))
        assertFalse(host.switchTo(env("a", "Same id, other object")))
        assertTrue(log.isEmpty(), log.toString())
        assertFalse(scenes.first().disposed)
        assertEquals(1, scenes.count { it.created })
    }

    @Test
    fun switchBeforeOpenOnlyChoosesWhatOpenBuilds() {
        val a = env("a", "Game A")
        val b = env("b", "Game B")
        val music = FakeMusic(log)
        val host = EnvironmentHost(a, music, RideState(3f), 1L)
        assertTrue(host.switchTo(b))
        assertTrue(scenes.isEmpty(), "no scene is built before open")
        host.open()
        assertEquals("b", scenes.single().name)
        assertEquals("Game B", music.playlists.last())
        assertFalse("Game A" in music.playlists)
    }

    @Test
    fun disposeDisposesTheLiveSceneOnce() {
        val a = env("a", "Game A")
        val b = env("b", "Game B")
        val host = EnvironmentHost(a, FakeMusic(log), RideState(3f), 1L)
        host.open()
        host.switchTo(b)
        host.dispose()
        host.dispose()
        assertNull(host.scene)
        assertEquals(1, log.count { it == "a.dispose" })
        assertEquals(1, log.count { it == "b.dispose" })
        host.step(1f / 60f) // harmless with no scene
    }

    @Test
    fun inspectionViewIsKeptAcrossASwitchOnlyIfTheNewSceneHasIt() {
        val a = env("a", null, views = listOf("close-up", "side"))
        val b = env("b", null, views = listOf("side"))
        val c = env("c", null)
        val host = EnvironmentHost(a, FakeMusic(log), RideState(3f), 1L)
        assertTrue(host.setInspectionView("side"), "remembered until the scene exists")
        host.open()
        assertEquals("side", scenes.last().view)
        assertFalse(host.setInspectionView("nope"))
        assertEquals("side", host.inspectionView)

        host.switchTo(b)
        assertEquals("side", scenes.last().view)
        host.switchTo(c)
        assertNull(host.inspectionView)
        assertTrue(scenes.last().riderView)
        assertNotNull(host.scene)
    }

    @Test
    fun aSceneThatFailsToBuildIsDisposedAndNotKept() {
        val bad = Environment("bad", "Bad", null) { _ ->
            object : EnvironmentScene by FakeScene("bad", 0, log) {
                override fun create(ride: RideState) = throw IllegalStateException("shader would not compile")
                override fun dispose() { log += "bad.dispose" }
            }
        }
        val a = env("a", "Game A")
        val host = EnvironmentHost(a, FakeMusic(log), RideState(3f), 1L)
        host.open()
        assertFailsWith<IllegalStateException> { host.switchTo(bad) }
        assertNull(host.scene)
        assertTrue(scenes.first().disposed)
        assertTrue("bad.dispose" in log)
        host.step(1f / 60f)
        host.dispose()
    }

    // ---- the game, as far as it goes without OpenGL ----

    @Test
    fun gameStartsInTheGivenEnvironmentAndRejectsUnknownOrCurrentIds() {
        val changes = ArrayList<String>()
        val game = RoadworkGame(music = FakeMusic(log), onEnvironmentChanged = { changes += it.id })
        assertSame(Environments.default, game.environment)
        assertFalse(game.switchEnvironment("no-such-place"))
        assertFalse(game.switchEnvironment(Environments.default.id))
        assertSame(Environments.default, game.environment)
        assertTrue(changes.isEmpty())
        assertTrue(log.isEmpty(), "nothing touches music before create(): $log")
        assertNull(game.screen, "no screen before create()")
        assertFalse(game.paused)
        game.setPaused(true)
        assertFalse(game.paused, "there is no ride to pause yet")
        assertEquals(0.0, game.elapsedSeconds)
    }
}
