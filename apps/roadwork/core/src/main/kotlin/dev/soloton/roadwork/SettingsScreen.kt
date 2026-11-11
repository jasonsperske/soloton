package dev.soloton.roadwork

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.utils.Align
import dev.soloton.api.MusicController
import dev.soloton.api.PlaylistSummary

/**
 * The settings of one environment, as a full screen: its music, the units to show, the ride's
 * goal, and whatever options the environment itself has ([Environment.options]). It is opened by
 * the gear on an environment's card in the picker and by the gear on the HUD during a ride, and
 * looks and works the same from both.
 *
 * Every change is reported at once through [Listener.onChanged], so there is nothing to confirm
 * or cancel; Done (or Escape, or Android's back button) just closes the screen. Positions come
 * from [SettingsLayout]; text is drawn the way the HUD draws it.
 *
 * Keys: Enter, Escape or back close; U changes the units; G changes the kind of goal; minus and
 * plus (or left and right) change the goal's amount; M opens the music settings.
 */
internal class SettingsScreen(
    private val music: MusicController,
    private val listener: Listener,
) : InputAdapter() {
    interface Listener {
        /** The rider changed something: [settings] is the environment's settings as they now stand. */
        fun onChanged(environment: Environment, settings: EnvironmentSettings)

        /** "Choose music": open the folders and songs of [environment]'s playlist. */
        fun onMusic(environment: Environment)

        fun onDone()
    }

    private val layout = SettingsLayout()
    private lateinit var camera: OrthographicCamera
    private lateinit var shapes: ShapeRenderer
    private lateinit var batch: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var textShader: ShaderProgram

    /** The environment whose settings are showing, or null while the screen is closed. */
    var environment: Environment? = null
        private set
    private var settings = EnvironmentSettings()

    private val headerText = GlyphLayout()
    private val doneText = GlyphLayout()
    private val musicButtonText = GlyphLayout()
    private val summaryText = GlyphLayout()
    private var shownSummary: PlaylistSummary? = null
    private val amountText = GlyphLayout()
    private val unitTexts = Array(Units.entries.size) { GlyphLayout() }
    private val goalTexts = Array(GoalKind.entries.size) { GlyphLayout() }
    private val labelTexts = Array(SettingsLayout.FIXED_ROWS + SettingsLayout.MAX_OPTIONS) { GlyphLayout() }
    private val optionTexts = Array(SettingsLayout.MAX_OPTIONS) { GlyphLayout() }
    private val optionNotes = Array(SettingsLayout.MAX_OPTIONS) { GlyphLayout() }

    fun create() {
        camera = OrthographicCamera()
        shapes = ShapeRenderer(4000)
        textShader = Shaders.compile(Shaders.TEXT_VERTEX, Shaders.TEXT_FRAGMENT)
        batch = SpriteBatch(600, textShader)
        font = BitmapFont()
        font.region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        font.setUseIntegerPositions(false)
    }

    /** Opens the screen on [environment], whose settings are now [current]. */
    fun show(environment: Environment, current: EnvironmentSettings) {
        this.environment = environment
        settings = current
        resize(Gdx.graphics.width, Gdx.graphics.height)
    }

    fun hide() {
        environment = null
    }

    fun resize(screenWidth: Int, screenHeight: Int) {
        val e = environment ?: return
        if (screenWidth <= 0 || screenHeight <= 0) return
        layout.layout(e.options.size, screenWidth, screenHeight)
        camera.setToOrtho(false, layout.width, SettingsLayout.HEIGHT)
        refreshText()
    }

    /** Lays out every piece of text again for the settings as they now stand. */
    private fun refreshText() {
        val e = environment ?: return
        text(headerText, "${e.title} settings", SCALE_HEADER, Color.WHITE, layout.done.x - layout.header.x - 24f)
        text(doneText, "Done", SCALE_BUTTON, Color.WHITE)
        text(musicButtonText, "Choose music", SCALE_BUTTON, Color.WHITE)
        shownSummary = null
        text(labelTexts[SettingsLayout.ROW_MUSIC], "Music", SCALE_LABEL, Color.WHITE)
        text(labelTexts[SettingsLayout.ROW_UNITS], "Units", SCALE_LABEL, Color.WHITE)
        text(labelTexts[SettingsLayout.ROW_GOAL], "Goal", SCALE_LABEL, Color.WHITE)
        val amountLabel = when (settings.goalKind) {
            GoalKind.DISTANCE -> "How far"
            GoalKind.TIME -> "How long"
            GoalKind.FREE -> ""
        }
        text(labelTexts[SettingsLayout.ROW_AMOUNT], amountLabel, SCALE_LABEL, TEXT_DIM)
        for (u in Units.entries) text(unitTexts[u.ordinal], u.title, SCALE_BUTTON, Color.WHITE)
        for (g in GoalKind.entries) text(goalTexts[g.ordinal], g.title, SCALE_BUTTON, Color.WHITE)
        text(amountText, settings.goalText(), SCALE_VALUE, Color.WHITE)
        for ((o, option) in e.options.withIndex()) {
            val box = layout.optionValue[o]
            text(labelTexts[SettingsLayout.FIXED_ROWS + o], option.title, SCALE_LABEL, Color.WHITE, layout.labels[SettingsLayout.FIXED_ROWS + o].width)
            text(optionTexts[o], option.choice(settings.options[option.key]).title, SCALE_BUTTON, Color.WHITE, box.width - 32f)
            text(optionNotes[o], option.note ?: "", SCALE_NOTE, TEXT_DIM, box.width - 32f)
        }
    }

    fun draw() {
        val e = environment ?: return
        refreshSummary(e)
        val gl = Gdx.gl
        gl.glDepthMask(true)
        gl.glDisable(GL20.GL_DEPTH_TEST)
        gl.glDisable(GL20.GL_CULL_FACE)
        gl.glDisable(GL20.GL_BLEND)
        gl.glClearColor(BACKGROUND.r, BACKGROUND.g, BACKGROUND.b, 1f)
        gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        camera.update()
        shapes.projectionMatrix = camera.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = ACCENT
        round(layout.done)
        val free = settings.goalKind == GoalKind.FREE
        for ((r, row) in layout.rows.withIndex()) {
            // A free ride has no amount to set: its row is left empty.
            if (r == SettingsLayout.ROW_AMOUNT && free) continue
            shapes.color = ROW
            UiIcons.roundRect(shapes, row.x, row.y, row.width, row.height, 24f)
        }
        shapes.color = BUTTON
        round(layout.musicButton)
        for (u in Units.entries) {
            shapes.color = if (u == settings.units) ACCENT else BUTTON
            round(layout.units[u.ordinal])
        }
        for (g in GoalKind.entries) {
            shapes.color = if (g == settings.goalKind) ACCENT else BUTTON
            round(layout.goals[g.ordinal])
        }
        if (!free) {
            shapes.color = BUTTON
            round(layout.amountLess)
            round(layout.amountMore)
            shapes.color = VALUE
            round(layout.amountValue)
            shapes.color = Color.WHITE
            minus(layout.amountLess)
            plus(layout.amountMore)
        }
        for (o in e.options.indices) {
            shapes.color = BUTTON
            round(layout.optionPrevious[o])
            round(layout.optionNext[o])
            shapes.color = VALUE
            round(layout.optionValue[o])
            shapes.color = Color.WHITE
            arrow(layout.optionPrevious[o], -1f)
            arrow(layout.optionNext[o], 1f)
        }
        shapes.end()

        gl.glEnable(GL20.GL_BLEND)
        gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        batch.projectionMatrix = camera.combined
        batch.begin()
        val h = layout.header
        draw(headerText, SCALE_HEADER, h.x, h.y + (h.height + headerText.height) / 2f)
        centred(doneText, SCALE_BUTTON, layout.done)
        for (r in layout.rows.indices) left(labelTexts[r], SCALE_LABEL, layout.labels[r])
        left(summaryText, SCALE_VALUE, layout.musicSummary)
        centred(musicButtonText, SCALE_BUTTON, layout.musicButton)
        for (u in Units.entries) centred(unitTexts[u.ordinal], SCALE_BUTTON, layout.units[u.ordinal])
        for (g in GoalKind.entries) centred(goalTexts[g.ordinal], SCALE_BUTTON, layout.goals[g.ordinal])
        if (!free) centred(amountText, SCALE_VALUE, layout.amountValue)
        for (o in e.options.indices) {
            val box = layout.optionValue[o]
            if (optionNotes[o].width > 0f) {
                // The choice above, the small print below, both centred in the box.
                draw(optionTexts[o], SCALE_BUTTON, box.x + (box.width - optionTexts[o].width) / 2f, box.y + box.height - 18f)
                draw(optionNotes[o], SCALE_NOTE, box.x + (box.width - optionNotes[o].width) / 2f, box.y + 14f + optionNotes[o].height)
            } else {
                centred(optionTexts[o], SCALE_BUTTON, box)
            }
        }
        batch.end()
        gl.glDisable(GL20.GL_BLEND)
    }

    /** The music line. The controller answers from a cache, so asking every frame is cheap. */
    private fun refreshSummary(e: Environment) {
        val summary = music.playlistSummary(e.playlist)
        if (summary == shownSummary) return
        shownSummary = summary
        text(summaryText, summary.text, SCALE_VALUE, if (summary.problem != null) WARN else TEXT_DIM, layout.musicSummary.width - 16f)
    }

    // ---- drawing helpers ----

    private fun round(r: Rectangle) = UiIcons.roundRect(shapes, r.x, r.y, r.width, r.height, 20f)

    private fun minus(r: Rectangle) = shapes.rect(r.x + r.width / 2f - 26f, r.y + r.height / 2f - 7f, 52f, 14f)

    private fun plus(r: Rectangle) {
        minus(r)
        shapes.rect(r.x + r.width / 2f - 7f, r.y + r.height / 2f - 26f, 14f, 52f)
    }

    /** A triangle pointing left ([direction] -1) or right (+1). */
    private fun arrow(r: Rectangle, direction: Float) {
        val cx = r.x + r.width / 2f
        val cy = r.y + r.height / 2f
        shapes.triangle(cx - 16f * direction, cy - 28f, cx - 16f * direction, cy + 28f, cx + 22f * direction, cy)
    }

    private fun draw(layout: GlyphLayout, scale: Float, x: Float, y: Float) {
        font.data.setScale(scale)
        font.draw(batch, layout, x, y)
    }

    private fun left(layout: GlyphLayout, scale: Float, box: Rectangle) =
        draw(layout, scale, box.x, box.y + (box.height + layout.height) / 2f)

    private fun centred(layout: GlyphLayout, scale: Float, box: Rectangle) =
        draw(layout, scale, box.x + (box.width - layout.width) / 2f, box.y + (box.height + layout.height) / 2f)

    private fun text(layout: GlyphLayout, text: CharSequence, scale: Float, color: Color, maxWidth: Float = 0f) {
        font.data.setScale(scale)
        if (maxWidth > 0f) {
            layout.setText(font, text, 0, text.length, color, maxWidth, Align.left, false, "...")
        } else {
            layout.setText(font, text, color, 0f, Align.left, false)
        }
    }

    // ---- input ----

    private fun change(next: EnvironmentSettings) {
        val e = environment ?: return
        if (next == settings) return
        settings = next
        refreshText()
        listener.onChanged(e, next)
    }

    /** Moves option [index] on by [step] choices, wrapping round at either end. */
    private fun cycle(index: Int, step: Int) {
        val option = environment?.options?.getOrNull(index) ?: return
        val now = option.choices.indexOf(option.choice(settings.options[option.key]))
        val next = option.choices[Math.floorMod(now + step, option.choices.size)]
        change(settings.withOption(option.key, next.id))
    }

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        val e = environment ?: return false
        val x = screenX * layout.width / Gdx.graphics.width
        val y = SettingsLayout.HEIGHT - screenY * SettingsLayout.HEIGHT / Gdx.graphics.height
        when {
            layout.done.contains(x, y) -> listener.onDone()
            layout.musicButton.contains(x, y) -> listener.onMusic(e)
            settings.goalKind != GoalKind.FREE && layout.amountLess.contains(x, y) -> change(settings.withGoalStepped(up = false))
            settings.goalKind != GoalKind.FREE && layout.amountMore.contains(x, y) -> change(settings.withGoalStepped(up = true))
            else -> {
                for (u in Units.entries) if (layout.units[u.ordinal].contains(x, y)) { change(settings.withUnits(u)); return true }
                for (g in GoalKind.entries) if (layout.goals[g.ordinal].contains(x, y)) { change(settings.withGoalKind(g)); return true }
                for (o in e.options.indices) {
                    if (layout.optionPrevious[o].contains(x, y)) { cycle(o, -1); return true }
                    // The value itself is as good a place to tap as the arrow.
                    if (layout.optionNext[o].contains(x, y) || layout.optionValue[o].contains(x, y)) { cycle(o, 1); return true }
                }
                return false
            }
        }
        return true
    }

    override fun keyDown(keycode: Int): Boolean {
        val e = environment ?: return false
        when (keycode) {
            Input.Keys.BACK, Input.Keys.ESCAPE, Input.Keys.ENTER -> listener.onDone()
            Input.Keys.M -> listener.onMusic(e)
            Input.Keys.U -> change(settings.withUnits(Units.entries[(settings.units.ordinal + 1) % Units.entries.size]))
            Input.Keys.G -> change(settings.withGoalKind(GoalKind.entries[(settings.goalKind.ordinal + 1) % GoalKind.entries.size]))
            Input.Keys.LEFT, Input.Keys.MINUS -> change(settings.withGoalStepped(up = false))
            Input.Keys.RIGHT, Input.Keys.PLUS, Input.Keys.EQUALS -> change(settings.withGoalStepped(up = true))
            Input.Keys.O -> cycle(0, 1)
            else -> return false
        }
        return true
    }

    fun dispose() {
        if (::batch.isInitialized) batch.dispose()
        if (::textShader.isInitialized) textShader.dispose()
        if (::shapes.isInitialized) shapes.dispose()
        if (::font.isInitialized) font.dispose()
    }

    companion object {
        /** The default font is 15 px tall, so a scale of 1.9 is the 28 px minimum for the tablet. */
        const val FONT_PIXELS = 15f
        const val SCALE_HEADER = 3.2f
        const val SCALE_LABEL = 2.6f
        const val SCALE_BUTTON = 2.4f
        const val SCALE_VALUE = 2.4f
        const val SCALE_NOTE = 1.9f
        private val BACKGROUND = Color(0.055f, 0.070f, 0.100f, 1f)
        private val ROW = Color(0.105f, 0.130f, 0.180f, 1f)
        private val VALUE = Color(0.07f, 0.09f, 0.13f, 1f)
        private val ACCENT = Color(0.16f, 0.52f, 0.86f, 1f)
        private val BUTTON = Color(0.25f, 0.29f, 0.36f, 1f)
        private val TEXT_DIM = Color(0.82f, 0.86f, 0.90f, 1f)
        private val WARN = Color(1f, 0.74f, 0.30f, 1f)
    }
}
