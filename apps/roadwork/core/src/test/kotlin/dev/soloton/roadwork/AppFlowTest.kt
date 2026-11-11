package dev.soloton.roadwork

import dev.soloton.api.PlaylistRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Picker -> ride -> picker, with fake scenes and music: what is built, started, stopped and disposed, and when. */
class AppFlowTest {
    private val log = ArrayList<String>()
    private val scenes = ArrayList<FakeScene>()
    private val music = FakeMusic(log)
    private val remembered = ArrayList<String>()

    private val stage = object : AppFlow.Stage {
        override fun pickerShown(highlight: Environment) { log += "picker.show(${highlight.id})" }
        override fun pickerHidden() { log += "picker.hide" }
        override fun rideStarted(session: RideSession) { log += "ride.started(${session.host.environment.id})" }
        override fun rideEnded() { log += "ride.ended" }
    }

    private fun env(id: String, folder: String?, views: List<String> = emptyList()) =
        Environment(id, "Title of $id", folder) { seed -> FakeScene(id, seed, log, views).also { scenes += it } }

    private val a = env("a", "Game A's Music!!")
    private val b = env("b", "Game B", views = listOf("side"))

    private fun flow(initial: Environment = a, startOnPicker: Boolean = true, speed: Float = 3f) = AppFlow(
        music = music,
        initialSpeed = { speed },
        seed = 9L,
        initial = initial,
        startOnPicker = startOnPicker,
        stage = stage,
        onRideStarted = { remembered += it.id },
    )

    @Test
    fun opensOnThePickerWithoutTouchingMusicOrBuildingAScene() {
        val flow = flow(initial = b)
        assertNull(flow.screen)
        flow.open()
        assertEquals(AppFlow.Screen.PICKER, flow.screen)
        assertEquals(listOf("picker.show(b)"), log, "the last environment is highlighted; nothing else happens")
        assertNull(flow.session)
        assertTrue(scenes.isEmpty())
        assertTrue(remembered.isEmpty())
        assertFailsWith<IllegalStateException> { flow.open() }
    }

    @Test
    fun rideHidesThePickerSelectsThePlaylistBuildsTheSceneThenStartsMusic() {
        val flow = flow()
        flow.resize(1920, 1080)
        flow.open()
        log.clear()

        flow.ride(b)

        assertEquals(
            listOf("picker.hide", "music.usePlaylist(Game B)", "b.create", "music.start", "ride.started(b)"),
            log,
        )
        assertEquals(AppFlow.Screen.RIDE, flow.screen)
        assertSame(b, flow.environment)
        assertEquals(listOf<PlaylistRef?>(b.playlist), music.refs)
        val session = assertNotNull(flow.session)
        val scene = scenes.single()
        assertSame(scene, session.host.scene)
        assertEquals(9L, scene.seed)
        assertEquals(1920 to 1080, scene.size)
        assertEquals(0.0, session.ride.elapsedSeconds)
        assertEquals(3f, session.ride.speed)
        assertEquals(listOf("b"), remembered, "the launcher is told, so it can save the last environment")

        // A second ride request during a ride is ignored.
        log.clear()
        flow.ride(a)
        assertTrue(log.isEmpty(), log.toString())
        assertSame(b, flow.environment)
    }

    @Test
    fun backToThePickerStopsMusicDisposesTheSceneAndHighlightsWhatWasRidden() {
        val flow = flow()
        flow.open()
        flow.ride(b)
        val session = assertNotNull(flow.session)
        repeat(90) { session.ride.step(1f / 60f, 3f); session.host.step(1f / 60f) }
        log.clear()

        flow.toPicker()

        assertEquals(listOf("music.stop", "b.dispose", "ride.ended", "picker.show(b)"), log)
        assertEquals(AppFlow.Screen.PICKER, flow.screen)
        assertNull(flow.session)
        assertTrue(scenes.single().disposed)
        assertNull(session.host.scene)
        assertEquals(0, log.count { it == "music.start" }, "music does not play on the picker")

        // Again on the picker: nothing to do.
        log.clear()
        flow.toPicker()
        assertTrue(log.isEmpty(), log.toString())
    }

    @Test
    fun aSecondRideStartsFromZeroWithANewScene() {
        val flow = flow()
        flow.open()
        flow.ride(a)
        val first = assertNotNull(flow.session)
        repeat(600) { first.ride.step(1f / 60f, 3f); first.host.step(1f / 60f) }
        assertEquals(10.0, first.ride.elapsedSeconds, 0.01)
        flow.toPicker()
        log.clear()

        flow.ride(a)

        val second = assertNotNull(flow.session)
        assertTrue(second !== first)
        assertEquals(0.0, second.ride.elapsedSeconds)
        assertEquals(0.0, second.ride.distanceMeters)
        assertEquals(0L, second.timestep.steps)
        assertEquals(2, scenes.size)
        assertTrue(scenes[0].disposed && !scenes[1].disposed)
        assertEquals(0.0, scenes[1].createdAtSeconds)
        assertEquals(listOf("picker.hide", "music.usePlaylist(Game A's Music!!)", "a.create", "music.start", "ride.started(a)"), log)
        assertEquals(listOf("a", "a"), remembered)
    }

    @Test
    fun pickerToRideToPickerToAnotherRideKeepsMusicInStep() {
        val flow = flow()
        flow.open()
        flow.ride(a)
        flow.toPicker()
        flow.ride(b)
        flow.toPicker()
        val musicCalls = log.filter { it.startsWith("music.") }
        assertEquals(
            listOf(
                "music.usePlaylist(Game A's Music!!)", "music.start", "music.stop",
                "music.usePlaylist(Game B)", "music.start", "music.stop",
            ),
            musicCalls,
        )
        // Every scene that was created was disposed, once, and never while another was live.
        assertEquals(listOf("a.create", "a.dispose", "b.create", "b.dispose"), log.filter { it.endsWith(".create") || it.endsWith(".dispose") })
        // Each start falls between its scene's create and the next stop.
        assertTrue(log.indexOf("a.create") < log.indexOf("music.start"))
        assertTrue(log.indexOf("music.stop") < log.indexOf("a.dispose"), "music stops before the scene goes")
    }

    @Test
    fun backLeavesARideForThePickerAndIsNotUsedOnThePicker() {
        val flow = flow()
        assertFalse(flow.back(), "before open")
        flow.open()
        assertFalse(flow.back(), "on the picker the app exits instead")
        assertEquals(AppFlow.Screen.PICKER, flow.screen)
        flow.ride(a)
        assertTrue(flow.back())
        assertEquals(AppFlow.Screen.PICKER, flow.screen)
        assertEquals("music.stop", log.last { it.startsWith("music.") })
        assertFalse(flow.back())
    }

    @Test
    fun startingWithAnEnvironmentSkipsThePickerAndBackStillReachesIt() {
        val flow = flow(initial = b, startOnPicker = false)
        flow.open()
        assertEquals(listOf("music.usePlaylist(Game B)", "b.create", "music.start", "ride.started(b)"), log)
        assertEquals(AppFlow.Screen.RIDE, flow.screen)
        assertEquals(listOf("b"), remembered)
        log.clear()
        assertTrue(flow.back())
        assertEquals(listOf("music.stop", "b.dispose", "ride.ended", "picker.show(b)"), log)
    }

    @Test
    fun switchingEnvironmentDuringARideKeepsTheRideAndIsRefusedOnThePicker() {
        val flow = flow()
        assertTrue(flow.switchEnvironment(b), "before open it chooses the initial environment")
        assertFalse(flow.switchEnvironment(b))
        assertSame(b, flow.environment)
        assertTrue(log.isEmpty(), log.toString())
        flow.open()
        assertEquals(listOf("picker.show(b)"), log)
        assertFalse(flow.switchEnvironment(a), "the picker has no ride to switch")

        flow.ride(b)
        val session = assertNotNull(flow.session)
        repeat(120) { session.ride.step(1f / 60f, 3f); session.host.step(1f / 60f) }
        log.clear()
        assertTrue(flow.switchEnvironment(a))
        assertEquals(listOf("music.usePlaylist(Game A's Music!!)", "b.dispose", "a.create"), log)
        assertSame(session, flow.session)
        assertEquals(2.0, session.ride.elapsedSeconds, 0.01)
        assertSame(a, flow.environment)
        assertEquals(listOf("b", "a"), remembered)
    }

    @Test
    fun inspectionViewIsRememberedForRidesThatHaveIt() {
        val flow = flow()
        assertTrue(flow.setInspectionView("side"))
        flow.open()
        flow.ride(a)
        assertTrue(scenes.last().riderView, "a has no such view, so the rider's view is used")
        flow.toPicker()
        flow.ride(b)
        assertEquals("side", scenes.last().view)
        assertFalse(flow.setInspectionView("nope"))
        assertTrue(flow.setInspectionView(null))
        assertTrue(scenes.last().riderView)
    }

    @Test
    fun resizeReachesTheLiveSceneAndTheNextOne() {
        val flow = flow()
        flow.open()
        flow.resize(1280, 720)
        flow.ride(a)
        assertEquals(1280 to 720, scenes.last().size)
        flow.resize(1920, 1080)
        assertEquals(1920 to 1080, scenes.last().size)
    }

    @Test
    fun disposeEndsARideOrThePickerOnce() {
        val riding = flow()
        riding.open()
        riding.ride(a)
        log.clear()
        riding.dispose()
        riding.dispose()
        assertEquals(listOf("a.dispose"), log, "music belongs to the launcher; the scene is disposed once")
        assertNull(riding.screen)

        log.clear()
        val picking = flow()
        picking.open()
        picking.dispose()
        assertEquals(listOf("picker.show(a)", "picker.hide"), log)
    }

    @Test
    fun aSceneThatCannotBeBuiltLeavesTheAppOnThePickerWithMusicStopped() {
        val bad = Environment("bad", "Bad", "Game C") { _ ->
            object : EnvironmentScene by FakeScene("bad", 0, log) {
                override fun create(ride: RideState) = throw IllegalStateException("shader would not compile")
                override fun dispose() { log += "bad.dispose" }
            }
        }
        val flow = flow()
        flow.open()
        log.clear()
        assertFailsWith<IllegalStateException> { flow.ride(bad) }
        assertEquals(AppFlow.Screen.PICKER, flow.screen)
        assertNull(flow.session)
        assertTrue("bad.dispose" in log)
        assertFalse("music.start" in log, "music never starts for a ride that did not begin")
        assertEquals("picker.show(a)", log.last())
        // The picker still works.
        flow.ride(b)
        assertEquals(AppFlow.Screen.RIDE, flow.screen)
    }
}
