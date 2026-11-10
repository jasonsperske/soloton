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
import dev.soloton.api.MusicState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
 *  |  -  |  6:10 /km   |  +  |          | NOW PLAYING              | >>| |
 *  |     |  9.7 km/h   |     |          | Title - Artist           |     |
 *  +-----+-------------+-----+          +--------------------------+-----+
 * ```
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
    }

    enum class Button { SLOWER, FASTER, SKIP, PAUSE, SETTINGS }

    private lateinit var camera: OrthographicCamera
    private lateinit var shapes: ShapeRenderer
    private lateinit var batch: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var textShader: ShaderProgram

    var width = 1920f
        private set
    val height = HEIGHT

    private val rects = Array(Button.entries.size) { Rectangle() }
    private val statsPanel = Rectangle()
    private val pacePanel = Rectangle()
    private val musicPanel = Rectangle()
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
    private val pausedLayout = GlyphLayout()
    private val pausedHintLayout = GlyphLayout()
    private val elapsedLabelLayout = GlyphLayout()
    private var shownSeconds = -1L
    private var shownDecameters = -1L
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
        resize(Gdx.graphics.width, Gdx.graphics.height)
    }

    fun resize(screenWidth: Int, screenHeight: Int) {
        if (screenWidth <= 0 || screenHeight <= 0) return
        width = HEIGHT * screenWidth / screenHeight
        camera.setToOrtho(false, width, HEIGHT)
        val m = MARGIN
        val b = BUTTON
        statsPanel.set(m, HEIGHT - m - 186f, 400f, 186f)
        rect(Button.SETTINGS).set(width - m - b, HEIGHT - m - b, b, b)
        rect(Button.PAUSE).set(width - m - b - GAP - b, HEIGHT - m - b, b, b)
        val pad = 16f
        val panelH = b + 2 * pad
        pacePanel.set(m, m, b * 2 + 300f + 4 * pad, panelH)
        rect(Button.SLOWER).set(pacePanel.x + pad, m + pad, b, b)
        rect(Button.FASTER).set(pacePanel.x + pacePanel.width - pad - b, m + pad, b, b)
        val musicW = (width - pacePanel.width - 3 * m - 40f).coerceAtMost(820f)
        musicPanel.set(width - m - musicW, m, musicW, panelH)
        rect(Button.SKIP).set(musicPanel.x + musicPanel.width - pad - b, m + pad, b, b)
        shownMusic = null // re-truncate the music line for the new width
    }

    private fun rect(b: Button) = rects[b.ordinal]

    fun setPaceAdjustable(adjustable: Boolean) { showPace = adjustable }

    fun draw(
        elapsedSeconds: Double,
        distanceMeters: Double,
        speed: Float,
        music: MusicState,
        paused: Boolean,
    ) {
        refreshText(elapsedSeconds, distanceMeters, speed, music)
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
        icons(paused)
        shapes.end()

        batch.projectionMatrix = camera.combined
        batch.begin()
        val s = statsPanel
        drawText(elapsedLabelLayout, SCALE_SMALL, s.x + 28f, s.y + s.height - 22f)
        drawText(timeLayout, SCALE_HUGE, s.x + 26f, s.y + s.height - 58f)
        drawText(distLayout, SCALE_LARGE, s.x + 28f, s.y + 66f)

        val textLeft = rect(Button.SLOWER).x + BUTTON + 16f
        val textW = rect(Button.FASTER).x - 16f - textLeft
        drawCentered(paceLayout, SCALE_LARGE, textLeft, textW, pacePanel.y + pacePanel.height - 30f)
        drawCentered(speedLayout, SCALE_MEDIUM, textLeft, textW, pacePanel.y + 58f)

        val mx = musicPanel.x + 104f
        drawText(musicLabelLayout, SCALE_SMALL, mx, musicPanel.y + musicPanel.height - 30f)
        drawText(musicLineLayout, SCALE_MEDIUM, mx, musicPanel.y + 70f)

        if (paused) {
            drawText(pausedLayout, SCALE_HUGE, (width - pausedLayout.width) / 2f, HEIGHT / 2f + 60f)
            drawText(pausedHintLayout, SCALE_SMALL, (width - pausedHintLayout.width) / 2f, HEIGHT / 2f - 20f)
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
        val secs = elapsed.toLong()
        if (secs != shownSeconds) {
            shownSeconds = secs
            sb.setLength(0)
            val h = secs / 3600
            val m = (secs / 60) % 60
            val s = secs % 60
            if (h > 0) { sb.append(h).append(':'); pad2(m) } else sb.append(m)
            sb.append(':'); pad2(s)
            layoutText(timeLayout, sb, SCALE_HUGE, Color.WHITE)
        }
        val dam = (distance / 10.0).toLong()
        if (dam != shownDecameters) {
            shownDecameters = dam
            sb.setLength(0)
            sb.append(dam / 100).append('.'); pad2(dam % 100); sb.append(" km")
            layoutText(distLayout, sb, SCALE_LARGE, Color.WHITE)
        }
        val tenths = (speed * 10f).roundToInt()
        if (tenths != shownSpeedTenths) {
            shownSpeedTenths = tenths
            sb.setLength(0)
            if (tenths <= 0) {
                sb.append("--:-- /km")
            } else {
                val secPerKm = (10000f / tenths).roundToInt()
                sb.append(secPerKm / 60).append(':'); pad2((secPerKm % 60).toLong()); sb.append(" /km")
            }
            layoutText(paceLayout, sb, SCALE_LARGE, Color.WHITE)
            sb.setLength(0)
            val kmh10 = (tenths * 3.6f).roundToInt() // speed in tenths of km/h
            sb.append(kmh10 / 10).append('.').append(kmh10 % 10).append(" km/h")
            layoutText(speedLayout, sb, SCALE_MEDIUM, TEXT_DIM)
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
                    layoutText(musicLabelLayout, "NO MUSIC  (tap the gear to choose a folder)", SCALE_SMALL, WARN, lineWidth)
                    layoutText(musicLineLayout, music.message.replace(music.folder, shortFolder(music.folder)), SCALE_MEDIUM, Color.WHITE, lineWidth)
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

    private fun trackLine(title: String, artist: String?): String =
        if (artist.isNullOrBlank()) title else "$title  -  $artist"

    private fun pad2(v: Long) {
        if (v < 10) sb.append('0')
        sb.append(v)
    }

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

    private fun roundRect(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        // Without overlap, so translucent panels have an even tone.
        shapes.rect(x + radius, y, w - 2 * radius, h)
        shapes.rect(x, y + radius, radius, h - 2 * radius)
        shapes.rect(x + w - radius, y + radius, radius, h - 2 * radius)
        shapes.arc(x + radius, y + radius, radius, 180f, 90f, 8)
        shapes.arc(x + w - radius, y + radius, radius, 270f, 90f, 8)
        shapes.arc(x + w - radius, y + h - radius, radius, 0f, 90f, 8)
        shapes.arc(x + radius, y + h - radius, radius, 90f, 90f, 8)
    }

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
        // Settings gear: teeth and a ring.
        r = rect(Button.SETTINGS)
        cx = r.x + r.width / 2f
        cy = r.y + r.height / 2f
        for (i in 0 until 8) {
            val a = (i * PI / 4).toFloat()
            val c = cos(a); val s = sin(a)
            shapes.rectLine(cx + c * 27f, cy + s * 27f, cx + c * 39f, cy + s * 39f, 14f)
        }
        // Ring (an annulus from short segments, so the centre stays see-through).
        for (i in 0 until 16) {
            val a0 = (i * PI / 8).toFloat()
            val a1 = ((i + 1) * PI / 8).toFloat()
            shapes.rectLine(cx + cos(a0) * 21f, cy + sin(a0) * 21f, cx + cos(a1) * 21f, cy + sin(a1) * 21f, 17f)
        }
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
            val r = rects[b.ordinal]
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
        const val HEIGHT = 1080f
        const val MARGIN = 36f
        const val BUTTON = 120f
        const val GAP = 24f
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
    }
}
