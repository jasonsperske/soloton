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
import dev.soloton.roadwork.HudLayout.Button
import com.badlogic.gdx.utils.Align
import dev.soloton.api.MusicState
import kotlin.math.roundToInt

/**
 * Heads-up display drawn in a virtual 1080-pixel-tall space (width follows the aspect ratio).
 *
 * ```
 *  +-------------------+                                  +-----+ +-----+
 *  | 12:34   elapsed   |                                  | ||  | | (*) |   pause, settings
 *  | 2.04 km           |                                  +-----+ +-----+
 *  +-------------------+
 *
 *  +-----+-------------+-----+          +--------------------------+-----+
 *  |  -  |  10.8 km/h  |  +  |          | NOW PLAYING              | >>| |
 *  |     |  5:33 /km   |     |          | Title - Artist           |     |
 *  +-----+-------------+-----+          +--------------------------+-----+
 * ```
 * Speed leads (the cycling convention) with pace below it, in the units of the environment's
 * settings: km/h and time per km, or mph and time per mile. With a distance or time goal set, the
 * stats panel also names the goal and shows a bar filling toward it, and a banner says when it is
 * reached; the ride carries on. The rider's own handlebars are drawn in the gap between the two
 * bottom panels. Positions come from [HudLayout].
 * While paused, a dimmed overlay adds the PAUSED banner, the environment's name and an
 * "Environments" button that leaves the ride for the picker.
 * Buttons are 120 px squares (at 1080 p). Text is the libGDX default font scaled up and
 * sharpened by an alpha-threshold shader. Text layouts are rebuilt only when their value changes.
 */
internal class Hud(private val listener: Listener) : InputAdapter() {
    interface Listener {
        fun onSlower()
        fun onFaster()
        fun onSkip()
        fun onTogglePause()
        fun onSettings()

        /** The pause overlay's "Environments" button, or back: leave the ride for the picker. */
        fun onEnvironments()

        /** The R and F keys: raise or lower a hand-set resistance (there is no bike to read it from). */
        fun onResistanceUp() {}
        fun onResistanceDown() {}
    }

    private val layout = HudLayout()

    private lateinit var camera: OrthographicCamera
    private lateinit var shapes: ShapeRenderer
    private lateinit var batch: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var textShader: ShaderProgram

    val width: Float get() = layout.width
    val height = HEIGHT

    private val statsPanel = layout.statsPanel
    private val pacePanel = layout.pacePanel
    private val musicPanel = layout.musicPanel
    private var showPace = true

    private var pressed: Button? = null
    private var pressedTime = 0f

    // Cached text: the layout is rebuilt only when the value behind it changes.
    private val sb = StringBuilder(64)
    private val timeLayout = GlyphLayout()
    private val distLayout = GlyphLayout()
    private val paceLayout = GlyphLayout()
    private val speedLayout = GlyphLayout()
    private val musicLabelLayout = GlyphLayout()
    private val musicLineLayout = GlyphLayout()
    private val musicDetailLayout = GlyphLayout()
    private val pausedLayout = GlyphLayout()
    private val pausedHintLayout = GlyphLayout()
    private val environmentLayout = GlyphLayout()
    private val environmentsButtonLayout = GlyphLayout()
    private var pausedShown = false
    private val elapsedLabelLayout = GlyphLayout()
    private val goalLayout = GlyphLayout()
    private val goalReachedLayout = GlyphLayout()
    private val goalBannerLayout = GlyphLayout()
    private var settings = EnvironmentSettings()
    private var goalReached = false
    private var bannerSeconds = 0f
    private var shownSeconds = -1L
    private var shownHundredths = -1L
    private var shownSpeedTenths = -1
    private var shownMusic: MusicState? = null
    private var musicProblem = false

    fun create() {
        camera = OrthographicCamera()
        shapes = ShapeRenderer(2000)
        textShader = Shaders.compile(Shaders.TEXT_VERTEX, Shaders.TEXT_FRAGMENT)
        batch = SpriteBatch(500, textShader)
        font = BitmapFont()
        font.region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        font.setUseIntegerPositions(false)
        layoutText(pausedLayout, "PAUSED", SCALE_HUGE, Color.WHITE)
        layoutText(pausedHintLayout, "Tap the play button to carry on", SCALE_SMALL, TEXT_DIM)
        layoutText(elapsedLabelLayout, "ELAPSED", SCALE_SMALL, TEXT_DIM)
        layoutText(environmentsButtonLayout, "Environments", SCALE_MEDIUM, Color.WHITE)
        layoutText(goalReachedLayout, "GOAL REACHED", SCALE_SMALL, GOOD)
        layoutText(goalBannerLayout, "Goal reached", SCALE_HUGE, Color.WHITE)
        setSettings(settings)
        resize(Gdx.graphics.width, Gdx.graphics.height)
    }

    fun resize(screenWidth: Int, screenHeight: Int) {
        if (screenWidth <= 0 || screenHeight <= 0) return
        layout.layout(screenWidth, screenHeight)
        camera.setToOrtho(false, width, HEIGHT)
        shownMusic = null // re-truncate the music line for the new width
    }

    private fun rect(b: Button) = layout.rect(b)

    fun setPaceAdjustable(adjustable: Boolean) { showPace = adjustable }

    /** The environment's name, shown under the PAUSED banner only, so the ride view stays clear. Call after [create]. */
    fun setEnvironmentTitle(title: String) {
        layoutText(environmentLayout, title, SCALE_SMALL, TEXT_DIM)
    }

    /**
     * The units to show and the goal to count toward. Call after [create], when a ride starts and
     * whenever the rider changes the settings.
     */
    fun setSettings(value: EnvironmentSettings) {
        settings = value
        // Every number is laid out again in the new units.
        shownSeconds = -1L
        shownHundredths = -1L
        shownSpeedTenths = -1
        goalReached = false
        bannerSeconds = 0f
        if (value.goalKind != GoalKind.FREE) layoutText(goalLayout, "GOAL " + value.goalText(), SCALE_SMALL, TEXT_DIM)
    }

    fun draw(
        elapsedSeconds: Double,
        distanceMeters: Double,
        speed: Float,
        music: MusicState,
        paused: Boolean,
    ) {
        refreshText(elapsedSeconds, distanceMeters, speed, music)
        pausedShown = paused
        val hasGoal = settings.goalKind != GoalKind.FREE
        val progress = settings.progress(elapsedSeconds, distanceMeters)
        if (hasGoal && progress >= 1f && !goalReached) {
            goalReached = true
            bannerSeconds = BANNER_SECONDS
        }
        if (bannerSeconds > 0f && !paused) bannerSeconds -= Gdx.graphics.deltaTime
        if (pressed != null) {
            pressedTime -= Gdx.graphics.deltaTime
            if (pressedTime <= 0f) pressed = null
        }

        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDisable(GL20.GL_CULL_FACE)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        camera.update()
        shapes.projectionMatrix = camera.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        if (paused) {
            shapes.color = DIM
            shapes.rect(0f, 0f, width, HEIGHT)
        }
        panel(statsPanel, PANEL)
        panel(pacePanel, PANEL)
        panel(musicPanel, PANEL)
        button(Button.PAUSE)
        button(Button.SETTINGS)
        button(Button.SKIP)
        if (showPace) {
            button(Button.SLOWER)
            button(Button.FASTER)
        }
        if (paused) button(Button.ENVIRONMENTS)
        icons(paused)
        if (hasGoal) {
            // The goal bar along the bottom of the stats panel: a track, filled as far as the ride has got.
            val barX = statsPanel.x + 28f
            val barW = statsPanel.width - 56f
            shapes.color = BUTTON_UP
            shapes.rect(barX, statsPanel.y + 9f, barW, 8f)
            shapes.color = if (goalReached) GOOD else ACCENT
            shapes.rect(barX, statsPanel.y + 9f, barW * progress, 8f)
        }
        if (bannerSeconds > 0f) {
            val w = goalBannerLayout.width + 120f
            shapes.color = PANEL
            UiIcons.roundRect(shapes, (width - w) / 2f, HEIGHT * 0.66f, w, 130f, 26f)
        }
        shapes.end()

        batch.projectionMatrix = camera.combined
        batch.begin()
        val s = statsPanel
        drawText(elapsedLabelLayout, SCALE_SMALL, s.x + 28f, s.y + s.height - 22f)
        drawText(timeLayout, SCALE_HUGE, s.x + 26f, s.y + s.height - 58f)
        drawText(distLayout, SCALE_LARGE, s.x + 28f, s.y + 66f)
        if (hasGoal) {
            val label = if (goalReached) goalReachedLayout else goalLayout
            drawText(label, SCALE_SMALL, s.x + s.width - 26f - label.width, s.y + s.height - 22f)
        }
        if (bannerSeconds > 0f) {
            drawText(goalBannerLayout, SCALE_HUGE, (width - goalBannerLayout.width) / 2f, HEIGHT * 0.66f + 65f + goalBannerLayout.height / 2f)
        }

        val textLeft = rect(Button.SLOWER).x + BUTTON + 16f
        val textW = rect(Button.FASTER).x - 16f - textLeft
        drawCentered(speedLayout, SCALE_LARGE, textLeft, textW, pacePanel.y + pacePanel.height - 30f)
        drawCentered(paceLayout, SCALE_MEDIUM, textLeft, textW, pacePanel.y + 58f)

        val mx = musicPanel.x + 104f
        drawText(musicLabelLayout, SCALE_SMALL, mx, musicPanel.y + musicPanel.height - 30f)
        if (musicProblem) {
            // Two small lines instead of one: the whole folder must be readable, then what is wrong with it.
            drawText(musicLineLayout, SCALE_SMALL, mx, musicPanel.y + 88f)
            drawText(musicDetailLayout, SCALE_SMALL, mx, musicPanel.y + 50f)
        } else {
            drawText(musicLineLayout, SCALE_MEDIUM, mx, musicPanel.y + 70f)
        }

        if (paused) {
            drawText(pausedLayout, SCALE_HUGE, (width - pausedLayout.width) / 2f, HEIGHT / 2f + 60f)
            drawText(pausedHintLayout, SCALE_SMALL, (width - pausedHintLayout.width) / 2f, HEIGHT / 2f - 20f)
            drawText(environmentLayout, SCALE_SMALL, (width - environmentLayout.width) / 2f, HEIGHT / 2f - 70f)
            val e = rect(Button.ENVIRONMENTS)
            drawText(
                environmentsButtonLayout, SCALE_MEDIUM,
                e.x + (e.width - environmentsButtonLayout.width) / 2f, e.y + (e.height + environmentsButtonLayout.height) / 2f,
            )
        }
        batch.end()
        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    private fun drawText(layout: GlyphLayout, scale: Float, x: Float, y: Float) {
        font.data.setScale(scale)
        font.draw(batch, layout, x, y)
    }

    private fun drawCentered(layout: GlyphLayout, scale: Float, left: Float, w: Float, y: Float) =
        drawText(layout, scale, left + (w - layout.width) / 2f, y)

    private fun layoutText(layout: GlyphLayout, text: CharSequence, scale: Float, color: Color, maxWidth: Float = 0f) {
        font.data.setScale(scale)
        if (maxWidth > 0f) {
            layout.setText(font, text, 0, text.length, color, maxWidth, Align.left, false, "...")
        } else {
            layout.setText(font, text, color, 0f, Align.left, false)
        }
    }

    private fun refreshText(elapsed: Double, distance: Double, speed: Float, music: MusicState) {
        val units = settings.units
        val secs = elapsed.toLong()
        if (secs != shownSeconds) {
            shownSeconds = secs
            RideText.time(sb, secs)
            layoutText(timeLayout, sb, SCALE_HUGE, Color.WHITE)
        }
        val hundredths = RideText.hundredths(distance, units)
        if (hundredths != shownHundredths) {
            shownHundredths = hundredths
            RideText.distance(sb, distance, units)
            layoutText(distLayout, sb, SCALE_LARGE, Color.WHITE)
        }
        // Speed is shown to a tenth of a metre per second's worth of change, which is finer than either unit's tenth.
        val tenths = (speed * 10f).roundToInt()
        if (tenths != shownSpeedTenths) {
            shownSpeedTenths = tenths
            RideText.pace(sb, tenths / 10f, units)
            layoutText(paceLayout, sb, SCALE_MEDIUM, TEXT_DIM)
            RideText.speed(sb, tenths / 10f, units)
            layoutText(speedLayout, sb, SCALE_LARGE, Color.WHITE)
        }
        if (music !== shownMusic) {
            shownMusic = music
            // Music state changes rarely, so building strings here is fine.
            val lineWidth = rect(Button.SKIP).x - 16f - (musicPanel.x + 104f)
            musicProblem = music is MusicState.Problem
            when (music) {
                is MusicState.Idle -> {
                    layoutText(musicLabelLayout, "MUSIC", SCALE_SMALL, TEXT_DIM)
                    layoutText(musicLineLayout, "Not playing", SCALE_MEDIUM, Color.WHITE, lineWidth)
                }
                is MusicState.Playing -> {
                    layoutText(musicLabelLayout, "NOW PLAYING", SCALE_SMALL, TEXT_DIM)
                    layoutText(musicLineLayout, trackLine(music.track.title, music.track.artist), SCALE_MEDIUM, Color.WHITE, lineWidth)
                }
                is MusicState.Paused -> {
                    layoutText(musicLabelLayout, "MUSIC PAUSED", SCALE_SMALL, TEXT_DIM)
                    layoutText(musicLineLayout, trackLine(music.track.title, music.track.artist), SCALE_MEDIUM, Color.WHITE, lineWidth)
                }
                is MusicState.Problem -> {
                    layoutText(musicLabelLayout, "NO MUSIC IN  (gear: settings, skip: retry)", SCALE_SMALL, WARN, lineWidth)
                    layoutFolder(musicLineLayout, shortFolder(music.folder), lineWidth)
                    layoutText(musicDetailLayout, problemDetail(music), SCALE_SMALL, TEXT_DIM, lineWidth)
                }
            }
        }
    }

    /** Drops the external-storage prefix so the folder fits: "/storage/emulated/0/Music/soloton" -> "Music/soloton". */
    private fun shortFolder(folder: String): String {
        for (prefix in STORAGE_PREFIXES) {
            if (folder.startsWith(prefix) && folder.length > prefix.length) return folder.substring(prefix.length)
        }
        return folder
    }

    /**
     * Lays out a folder path in small text. One that is too wide loses leading folders, not its
     * end: the last folder is the one the rider has to copy music into.
     */
    private fun layoutFolder(layout: GlyphLayout, folder: String, maxWidth: Float) {
        var text = folder
        layoutText(layout, text, SCALE_SMALL, Color.WHITE)
        while (layout.width > maxWidth) {
            val cut = text.indexOf('/', if (text.startsWith(".../")) 4 else 1)
            if (cut < 0 || cut == text.length - 1) break
            text = ".../" + text.substring(cut + 1)
            layoutText(layout, text, SCALE_SMALL, Color.WHITE)
        }
        if (layout.width > maxWidth) layoutText(layout, text, SCALE_SMALL, Color.WHITE, maxWidth)
    }

    /** What is wrong, without repeating the folder that is shown on the line above. */
    private fun problemDetail(problem: MusicState.Problem): String {
        val lead = "No music found in ${problem.folder}. "
        return if (problem.message.startsWith(lead)) problem.message.substring(lead.length)
        else problem.message.replace(problem.folder, shortFolder(problem.folder))
    }

    private fun trackLine(title: String, artist: String?): String =
        if (artist.isNullOrBlank()) title else "$title  -  $artist"

    // ---- shapes ----

    private fun panel(r: Rectangle, c: Color) {
        shapes.color = c
        roundRect(r.x, r.y, r.width, r.height, 22f)
    }

    private fun button(b: Button) {
        val r = rect(b)
        shapes.color = if (pressed == b) BUTTON_DOWN else BUTTON_UP
        roundRect(r.x, r.y, r.width, r.height, 20f)
    }

    private fun roundRect(x: Float, y: Float, w: Float, h: Float, radius: Float) = UiIcons.roundRect(shapes, x, y, w, h, radius)

    private fun icons(paused: Boolean) {
        shapes.color = ICON
        // Pause / play.
        var r = rect(Button.PAUSE)
        var cx = r.x + r.width / 2f
        var cy = r.y + r.height / 2f
        if (paused) {
            shapes.triangle(cx - 20f, cy - 30f, cx - 20f, cy + 30f, cx + 30f, cy)
        } else {
            shapes.rect(cx - 24f, cy - 28f, 16f, 56f)
            shapes.rect(cx + 8f, cy - 28f, 16f, 56f)
        }
        // Skip (next track).
        r = rect(Button.SKIP)
        cx = r.x + r.width / 2f
        cy = r.y + r.height / 2f
        shapes.triangle(cx - 30f, cy - 26f, cx - 30f, cy + 26f, cx + 2f, cy)
        shapes.triangle(cx - 4f, cy - 26f, cx - 4f, cy + 26f, cx + 26f, cy)
        shapes.rect(cx + 24f, cy - 26f, 8f, 52f)
        // Settings gear.
        r = rect(Button.SETTINGS)
        UiIcons.gear(shapes, r.x + r.width / 2f, r.y + r.height / 2f)
        if (showPace) {
            r = rect(Button.SLOWER)
            cx = r.x + r.width / 2f
            cy = r.y + r.height / 2f
            shapes.rect(cx - 28f, cy - 7f, 56f, 14f)
            r = rect(Button.FASTER)
            cx = r.x + r.width / 2f
            cy = r.y + r.height / 2f
            shapes.rect(cx - 28f, cy - 7f, 56f, 14f)
            shapes.rect(cx - 7f, cy - 28f, 14f, 56f)
        }
        // Music note (or a warning mark when there is a problem).
        val nx = musicPanel.x + 52f
        val ny = musicPanel.y + musicPanel.height / 2f
        if (musicProblem) {
            shapes.color = WARN
            shapes.triangle(nx - 32f, ny - 28f, nx + 32f, ny - 28f, nx, ny + 30f)
            shapes.color = PANEL_SOLID
            shapes.rect(nx - 4f, ny - 8f, 8f, 26f)
            shapes.rect(nx - 4f, ny - 20f, 8f, 7f)
        } else {
            shapes.circle(nx - 12f, ny - 18f, 13f, 14)
            shapes.rect(nx - 1f, ny - 18f, 7f, 50f)
            shapes.triangle(nx + 6f, ny + 32f, nx + 6f, ny + 16f, nx + 26f, ny + 10f)
        }
    }

    // ---- input ----

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        val x = screenX * width / Gdx.graphics.width
        val y = HEIGHT - screenY * HEIGHT / Gdx.graphics.height
        val hit = hitTest(x, y) ?: return false
        press(hit)
        return true
    }

    /** Which button is under virtual HUD coordinates (x, y), or null. Buttons get an extra 12 px of slop. */
    fun hitTest(x: Float, y: Float): Button? {
        for (b in Button.entries) {
            if (!showPace && (b == Button.SLOWER || b == Button.FASTER)) continue
            if (!pausedShown && b == Button.ENVIRONMENTS) continue
            val r = layout.rect(b)
            if (x >= r.x - SLOP && x <= r.x + r.width + SLOP && y >= r.y - SLOP && y <= r.y + r.height + SLOP) return b
        }
        return null
    }

    override fun keyDown(keycode: Int): Boolean {
        val b = when (keycode) {
            Input.Keys.SPACE, Input.Keys.P -> Button.PAUSE
            Input.Keys.LEFT, Input.Keys.MINUS, Input.Keys.DOWN -> Button.SLOWER
            Input.Keys.RIGHT, Input.Keys.PLUS, Input.Keys.EQUALS, Input.Keys.UP -> Button.FASTER
            Input.Keys.N -> Button.SKIP
            Input.Keys.S -> Button.SETTINGS
            // Android's back button (caught by the game) and Escape leave the ride from any state.
            Input.Keys.BACK, Input.Keys.ESCAPE, Input.Keys.E -> {
                listener.onEnvironments()
                return true
            }
            Input.Keys.R, Input.Keys.PAGE_UP -> {
                listener.onResistanceUp()
                return true
            }
            Input.Keys.F, Input.Keys.PAGE_DOWN -> {
                listener.onResistanceDown()
                return true
            }
            else -> return false
        }
        if (!showPace && (b == Button.SLOWER || b == Button.FASTER)) return false
        press(b)
        return true
    }

    private fun press(b: Button) {
        pressed = b
        pressedTime = 0.18f
        when (b) {
            Button.SLOWER -> listener.onSlower()
            Button.FASTER -> listener.onFaster()
            Button.SKIP -> listener.onSkip()
            Button.PAUSE -> listener.onTogglePause()
            Button.SETTINGS -> listener.onSettings()
            Button.ENVIRONMENTS -> listener.onEnvironments()
        }
    }

    fun dispose() {
        batch.dispose()
        textShader.dispose()
        shapes.dispose()
        font.dispose()
    }

    companion object {
        private val STORAGE_PREFIXES = arrayOf("/storage/emulated/0/", "/sdcard/", "/mnt/sdcard/", "/storage/sdcard0/")
        const val HEIGHT = HudLayout.HEIGHT
        const val BUTTON = HudLayout.BUTTON
        const val SLOP = 12f
        const val SCALE_HUGE = 4.2f
        const val SCALE_LARGE = 3.0f
        const val SCALE_MEDIUM = 2.5f
        const val SCALE_SMALL = 1.7f
        private val PANEL = Color(0.04f, 0.06f, 0.11f, 0.56f)
        private val PANEL_SOLID = Color(0.10f, 0.12f, 0.17f, 1f)
        private val BUTTON_UP = Color(1f, 1f, 1f, 0.16f)
        private val BUTTON_DOWN = Color(1f, 1f, 1f, 0.42f)
        private val ICON = Color(1f, 1f, 1f, 1f)
        private val DIM = Color(0.02f, 0.03f, 0.06f, 0.35f)
        private val TEXT_DIM = Color(0.82f, 0.86f, 0.90f, 1f)
        private val WARN = Color(1f, 0.74f, 0.30f, 1f)
        private val ACCENT = Color(0.16f, 0.52f, 0.86f, 1f)
        private val GOOD = Color(0.36f, 0.86f, 0.52f, 1f)

        /** How long the "Goal reached" banner stays up. */
        const val BANNER_SECONDS = 6f
    }
}
