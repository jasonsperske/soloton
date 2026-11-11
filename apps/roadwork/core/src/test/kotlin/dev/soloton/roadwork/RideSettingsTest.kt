package dev.soloton.roadwork

import com.badlogic.gdx.math.Rectangle
import dev.soloton.api.MemorySettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RideSettingsTest {
    private val log = ArrayList<String>()
    private val craft = EnvironmentOption(
        "craft", "Aircraft",
        listOf(EnvironmentOption.Choice("tour", "In turn"), EnvironmentOption.Choice("random", "Random"), EnvironmentOption.Choice("plane", "Plane")),
    )
    private val plain = Environment("plain", "Plain", null) { seed -> FakeScene("plain", seed, log) }
    private val island = Environment("island", "Island", null, listOf(craft)) { seed -> FakeScene("island", seed, log) }

    // ---- the settings themselves ----

    @Test
    fun aNewEnvironmentIsAFreeRideInKilometresWithEachOptionAtItsFirstChoice() {
        val store = EnvironmentSettingsStore(MemorySettingsStore())
        val s = store.load(island)
        assertEquals(Units.KILOMETRES, s.units)
        assertEquals(GoalKind.FREE, s.goalKind)
        assertEquals(mapOf("craft" to "tour"), s.options)
        assertEquals("", s.goalText())
        assertEquals(0f, s.progress(600.0, 5000.0))
        assertEquals(emptyMap(), store.load(plain).options)
    }

    @Test
    fun settingsAreSavedPerEnvironmentAndComeBackAsTheyWere() {
        val backing = MemorySettingsStore()
        val store = EnvironmentSettingsStore(backing)
        val chosen = EnvironmentSettings().withUnits(Units.MILES).withGoalKind(GoalKind.TIME).withGoalStepped(up = true).withOption("craft", "random")
        store.save(island, chosen)
        assertEquals(chosen, store.load(island))
        assertEquals(chosen, EnvironmentSettingsStore(backing).load(island), "another store over the same backing reads the same")
        // The other environment is untouched.
        assertEquals(EnvironmentSettings(), store.load(plain))
        store.save(plain, EnvironmentSettings().withGoalKind(GoalKind.DISTANCE))
        assertEquals(chosen, store.load(island))
        assertEquals(GoalKind.DISTANCE, store.load(plain).goalKind)
    }

    @Test
    fun savedValuesThatNoLongerMakeSenseFallBackToTheDefaults() {
        val backing = MemorySettingsStore()
        backing.put("env.island.units", "furlongs")
        backing.put("env.island.goal", "sprint")
        backing.put("env.island.goalDistance", "-4")
        backing.put("env.island.goalMinutes", "soon")
        backing.put("env.island.option.craft", "zeppelin")
        backing.put("env.island.option.colour", "red")
        val s = EnvironmentSettingsStore(backing).load(island)
        assertEquals(EnvironmentSettings(options = mapOf("craft" to "tour")), s)
    }

    @Test
    fun optionsNeedAKeyDistinctChoicesAndDistinctKeys() {
        assertFailsWith<IllegalArgumentException> { EnvironmentOption("", "Nameless", listOf(EnvironmentOption.Choice("a", "A"))) }
        assertFailsWith<IllegalArgumentException> { EnvironmentOption("empty", "Empty", emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            EnvironmentOption("twice", "Twice", listOf(EnvironmentOption.Choice("a", "A"), EnvironmentOption.Choice("a", "Again")))
        }
        assertFailsWith<IllegalArgumentException> { Environment("two", "Two", null, listOf(craft, craft)) { seed -> FakeScene("two", seed, log) } }
        assertEquals("tour", craft.default.id)
        assertEquals("random", craft.choice("random").id)
        assertEquals("tour", craft.choice("zeppelin").id)
        assertEquals("tour", craft.choice(null).id)
        // Every registered environment's options fit the settings screen.
        for (e in Environments.all) assertTrue(e.options.size <= SettingsLayout.MAX_OPTIONS, "${e.id} has ${e.options.size} options")
    }

    // ---- goals ----

    @Test
    fun aGoalStepsThroughRoundDistancesAndTimesAndStopsAtTheEnds() {
        var s = EnvironmentSettings().withGoalKind(GoalKind.DISTANCE)
        assertEquals("10 km", s.goalText())
        s = s.withGoalStepped(up = true)
        assertEquals(15, s.goalDistance)
        s = s.withGoalStepped(up = false).withGoalStepped(up = false)
        assertEquals(8, s.goalDistance)
        repeat(30) { s = s.withGoalStepped(up = false) }
        assertEquals(1, s.goalDistance)
        repeat(30) { s = s.withGoalStepped(up = true) }
        assertEquals(100, s.goalDistance)
        assertEquals("100 mi", s.withUnits(Units.MILES).goalText(), "the number is kept when the units change")

        var t = EnvironmentSettings().withGoalKind(GoalKind.TIME)
        assertEquals("30 min", t.goalText())
        t = t.withGoalStepped(up = true)
        assertEquals(45, t.goalMinutes)
        repeat(30) { t = t.withGoalStepped(up = false) }
        assertEquals(5, t.goalMinutes)
        // A free ride has nothing to step, and stepping one goal leaves the other alone.
        val free = EnvironmentSettings()
        assertEquals(free, free.withGoalStepped(up = true))
        assertEquals(EnvironmentSettings.DEFAULT_DISTANCE, t.goalDistance)
        // A value that is not on the list (saved by an older version) steps to its neighbours.
        assertEquals(15, EnvironmentSettings.step(EnvironmentSettings.DISTANCES, 12, up = true))
        assertEquals(10, EnvironmentSettings.step(EnvironmentSettings.DISTANCES, 12, up = false))
    }

    @Test
    fun progressCountsTowardTheGoalInItsOwnUnitsAndStopsAtFull() {
        val tenKm = EnvironmentSettings().withGoalKind(GoalKind.DISTANCE)
        assertEquals(10_000.0, tenKm.goalMeters)
        assertEquals(0.25f, tenKm.progress(99.0, 2500.0), 1e-6f)
        assertEquals(1f, tenKm.progress(0.0, 12_000.0))
        val tenMiles = tenKm.withUnits(Units.MILES)
        assertEquals(16_093.44, tenMiles.goalMeters, 1e-6)
        assertTrue(tenMiles.progress(0.0, 10_000.0) < 1f, "ten kilometres is not ten miles")
        val halfHour = EnvironmentSettings().withGoalKind(GoalKind.TIME)
        assertEquals(1800.0, halfHour.goalSeconds)
        assertEquals(0.5f, halfHour.progress(900.0, 1.0), 1e-6f)
        assertEquals(1f, halfHour.progress(4000.0, 0.0))
        assertEquals(0.0, halfHour.goalMeters)
        assertEquals(0.0, tenKm.goalSeconds)
    }

    @Test
    fun settingsCompareByValue() {
        assertEquals(EnvironmentSettings(), EnvironmentSettings())
        assertEquals(EnvironmentSettings().hashCode(), EnvironmentSettings().hashCode())
        assertNotEquals(EnvironmentSettings(), EnvironmentSettings().withUnits(Units.MILES))
        assertNotEquals(EnvironmentSettings(), EnvironmentSettings().withOption("craft", "plane"))
        assertEquals(Units.KILOMETRES, Units.byId("km"))
        assertEquals(Units.MILES, Units.byId("miles"))
        assertEquals(Units.KILOMETRES, Units.byId(null))
        assertEquals(GoalKind.TIME, GoalKind.byId("time"))
        assertEquals(GoalKind.FREE, GoalKind.byId("marathon"))
    }

    // ---- the words on the HUD ----

    private fun text(write: (StringBuilder) -> Unit) = StringBuilder().also(write).toString()

    @Test
    fun theHudSpeaksKilometresOrMiles() {
        assertEquals("2.04 km", text { RideText.distance(it, 2049.9, Units.KILOMETRES) })
        assertEquals("0.00 km", text { RideText.distance(it, 9.9, Units.KILOMETRES) })
        assertEquals("1.27 mi", text { RideText.distance(it, 2049.9, Units.MILES) })
        assertEquals("1.00 mi", text { RideText.distance(it, 1609.344, Units.MILES) })
        assertEquals("123.45 km", text { RideText.distance(it, 123_456.0, Units.KILOMETRES) })

        assertEquals("10.8 km/h", text { RideText.speed(it, 3f, Units.KILOMETRES) })
        assertEquals("6.7 mph", text { RideText.speed(it, 3f, Units.MILES) })
        assertEquals("0.0 km/h", text { RideText.speed(it, 0f, Units.KILOMETRES) })

        assertEquals("5:33 /km", text { RideText.pace(it, 3f, Units.KILOMETRES) })
        assertEquals("8:56 /mi", text { RideText.pace(it, 3f, Units.MILES) })
        assertEquals("--:-- /km", text { RideText.pace(it, 0f, Units.KILOMETRES) })
        assertEquals("--:-- /mi", text { RideText.pace(it, 0f, Units.MILES) })

        assertEquals("0:00", text { RideText.time(it, 0) })
        assertEquals("12:34", text { RideText.time(it, 754) })
        assertEquals("1:02:03", text { RideText.time(it, 3723) })
        // Each call starts afresh.
        val sb = StringBuilder("leftover")
        RideText.time(sb, 5)
        assertEquals("0:05", sb.toString())
    }

    // ---- the settings screen's layout ----

    private fun overlap(a: Rectangle, b: Rectangle): Boolean =
        a.x < b.x + b.width - 0.01f && b.x < a.x + a.width - 0.01f && a.y < b.y + b.height - 0.01f && b.y < a.y + a.height - 0.01f

    private fun inside(outer: Rectangle, inner: Rectangle): Boolean =
        inner.x >= outer.x - 0.01f && inner.y >= outer.y - 0.01f &&
            inner.x + inner.width <= outer.x + outer.width + 0.01f && inner.y + inner.height <= outer.y + outer.height + 0.01f

    @Test
    fun theSettingsScreenFitsEveryScreenShapeWithControlsBigEnoughToTouchAndNothingOverlapping() {
        for (options in 0..SettingsLayout.MAX_OPTIONS) {
            for ((w, h) in listOf(1920 to 1080, 1280 to 720, 1440 to 1080, 2560 to 1080, 1920 to 1200)) {
                val l = SettingsLayout().apply { layout(options, w, h) }
                val where = "$options options at ${w}x$h"
                val screen = Rectangle(0f, 0f, l.width, SettingsLayout.HEIGHT)
                assertEquals(1080f * w / h, l.width, 0.01f)
                assertEquals(SettingsLayout.FIXED_ROWS + options, l.rows.size, where)
                assertEquals(options, l.optionNext.size, where)
                assertTrue(inside(screen, l.header) && inside(l.header, l.done), where)
                for ((i, row) in l.rows.withIndex()) {
                    assertTrue(inside(screen, row), "$where: row $i $row")
                    assertTrue(row.y >= SettingsLayout.MARGIN - 0.01f, "$where: row $i reaches the bottom edge")
                    assertFalse(overlap(row, l.header), "$where: row $i under the heading")
                    for (j in i + 1 until l.rows.size) assertFalse(overlap(row, l.rows[j]), "$where: rows $i and $j")
                    assertTrue(inside(row, l.labels[i]), "$where: label $i")
                }
                val controls = l.controls()
                for ((i, c) in controls.withIndex()) {
                    assertTrue(c.width >= SettingsLayout.MIN_TOUCH && c.height >= SettingsLayout.MIN_TOUCH, "$where: control $c")
                    assertTrue(inside(screen, c), "$where: control $c off the screen")
                    for (j in i + 1 until controls.size) assertFalse(overlap(c, controls[j]), "$where: controls $c and ${controls[j]}")
                    for (label in l.labels) assertFalse(overlap(c, label), "$where: control $c over a label")
                }
                // Each row's controls are in that row, with the value boxes clear of their buttons.
                assertTrue(inside(l.rows[SettingsLayout.ROW_MUSIC], l.musicButton) && inside(l.rows[SettingsLayout.ROW_MUSIC], l.musicSummary), where)
                assertFalse(overlap(l.musicSummary, l.musicButton), where)
                assertTrue(l.musicSummary.width >= 400f, "$where: room for the music line: ${l.musicSummary.width}")
                for (u in l.units) assertTrue(inside(l.rows[SettingsLayout.ROW_UNITS], u), where)
                for (g in l.goals) assertTrue(inside(l.rows[SettingsLayout.ROW_GOAL], g), where)
                for (r in listOf(l.amountLess, l.amountValue, l.amountMore)) assertTrue(inside(l.rows[SettingsLayout.ROW_AMOUNT], r), where)
                assertFalse(overlap(l.amountValue, l.amountLess) || overlap(l.amountValue, l.amountMore), where)
                assertTrue(l.amountLess.x < l.amountValue.x && l.amountValue.x < l.amountMore.x, "$where: less, value, more")
                for (o in 0 until options) {
                    val row = l.rows[SettingsLayout.FIXED_ROWS + o]
                    for (r in listOf(l.optionPrevious[o], l.optionValue[o], l.optionNext[o])) assertTrue(inside(row, r), "$where: option $o")
                    assertFalse(overlap(l.optionValue[o], l.optionPrevious[o]) || overlap(l.optionValue[o], l.optionNext[o]), where)
                    assertTrue(l.optionValue[o].width >= 500f, "$where: room for the choice's name: ${l.optionValue[o].width}")
                }
            }
        }
        assertFailsWith<IllegalArgumentException> { SettingsLayout().layout(SettingsLayout.MAX_OPTIONS + 1, 1920, 1080) }
        for (scale in listOf(SettingsScreen.SCALE_HEADER, SettingsScreen.SCALE_LABEL, SettingsScreen.SCALE_BUTTON, SettingsScreen.SCALE_VALUE, SettingsScreen.SCALE_NOTE)) {
            assertTrue(scale * SettingsScreen.FONT_PIXELS >= 28f, "text at scale $scale is ${scale * SettingsScreen.FONT_PIXELS} px")
        }
    }

    // ---- options reaching the scene ----

    @Test
    fun aSceneIsGivenTheRidersChoicesBeforeItIsCreatedAndAgainWhenTheyChange() {
        val scenes = ArrayList<FakeScene>()
        val e = Environment("opt", "Options", null, listOf(craft)) { seed -> FakeScene("opt", seed, log).also { scenes += it } }
        val store = EnvironmentSettingsStore(MemorySettingsStore())
        store.save(e, store.load(e).withOption("craft", "random"))
        val host = EnvironmentHost(e, FakeMusic(log), RideState(3f), 1L) { store.load(it).options }
        host.open()
        val scene = scenes.single()
        assertEquals(mapOf("craft" to "random"), scene.options)
        assertTrue(log.indexOf("opt.options") < log.indexOf("opt.create"), "options come before create: $log")

        store.save(e, store.load(e).withOption("craft", "plane"))
        assertEquals(mapOf("craft" to "random"), scene.options, "nothing changes until the host is told")
        host.refreshOptions()
        assertEquals(mapOf("craft" to "plane"), scene.options)

        // A host with no settings behind it gives every scene an empty map.
        val bare = EnvironmentHost(e, FakeMusic(log), RideState(3f), 1L)
        bare.open()
        assertEquals(emptyMap(), scenes.last().options)
        bare.dispose()
        bare.refreshOptions() // harmless with no scene
    }
}
