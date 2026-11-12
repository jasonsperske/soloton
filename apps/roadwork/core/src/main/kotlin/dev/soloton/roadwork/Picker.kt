package dev.soloton.roadwork

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.utils.Align
import dev.soloton.api.MusicController
import dev.soloton.api.PlaylistSummary

/**
 * The first screen: one card per environment, each with a preview of its scene, its title, a line
 * about its music, a Ride button and a gear that opens its settings. Positions come from
 * [PickerLayout]; text is drawn the way the HUD draws it. The environment ridden last is outlined.
 *
 * A preview is one frame of the scene, [PREVIEW_SECONDS] into a ride at the default pace, drawn
 * once into a framebuffer when the picker is shown (no HUD, no handlebars) and kept until it is
 * hidden. [renderPreviews] must be called outside any other framebuffer.
 *
 * An Exit button at the top right closes the app.
 *
 * Keys: left/right move the outline, Enter or space rides, S opens the settings, Escape or
 * Android's back button exits.
 */
internal class Picker(
    private val environments: List<Environment>,
    private val music: MusicController,
    private val seed: Long,
    private val listener: Listener,
) : InputAdapter() {
    interface Listener {
        fun onRide(environment: Environment)
        fun onSettings(environment: Environment)
        fun onExit()
    }

    private val layout = PickerLayout()
    private lateinit var camera: OrthographicCamera
    private lateinit var shapes: ShapeRenderer
    private lateinit var batch: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var textShader: ShaderProgram

    private val previews = arrayOfNulls<FrameBuffer>(environments.size)
    private var previewsStale = false

    /** True between [show] and [hide]. */
    var shown = false
        private set
    private var highlight = 0

    private val headerLayout = GlyphLayout()
    private val rideLayout = GlyphLayout()
    private val exitLayout = GlyphLayout()
    private val titleLayouts = Array(environments.size) { GlyphLayout() }
    private val summaryLayouts = Array(environments.size) { GlyphLayout() }
    private val shownSummaries = arrayOfNulls<PlaylistSummary>(environments.size)

    fun create() {
        camera = OrthographicCamera()
        shapes = ShapeRenderer(2000)
        textShader = Shaders.compile(Shaders.TEXT_VERTEX, Shaders.TEXT_FRAGMENT)
        batch = SpriteBatch(400)
        font = BitmapFont()
        font.region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        font.setUseIntegerPositions(false)
        resize(Gdx.graphics.width, Gdx.graphics.height)
    }

    fun resize(screenWidth: Int, screenHeight: Int) {
        if (screenWidth <= 0 || screenHeight <= 0) return
        layout.layout(environments.size, screenWidth, screenHeight)
        camera.setToOrtho(false, layout.width, PickerLayout.HEIGHT)
        layoutText(headerLayout, "Choose an environment", SCALE_HEADER, Color.WHITE)
        layoutText(rideLayout, "Ride", SCALE_BUTTON, Color.WHITE)
        layoutText(exitLayout, "Exit", SCALE_BUTTON, Color.WHITE)
        val titleScale = if (layout.compact) SCALE_TITLE_COMPACT else SCALE_TITLE
        for (i in environments.indices) {
            layoutText(titleLayouts[i], environments[i].title, titleScale, Color.WHITE, layout.cards[i].title.width)
            shownSummaries[i] = null // re-truncate for the new width
        }
    }

    /** Makes the picker the screen, with [last] outlined, and draws the previews. */
    fun show(last: Environment) {
        shown = true
        highlight = environments.indexOfFirst { it.id == last.id }.coerceAtLeast(0)
        renderPreviews()
    }

    /** Frees the previews. */
    fun hide() {
        shown = false
        disposePreviews()
    }

    /** The GL context was lost and restored: framebuffer contents are gone, so draw the previews again. */
    fun invalidatePreviews() {
        previewsStale = true
    }

    /** Draws any preview that is missing or stale. Not while another framebuffer is bound. */
    fun renderPreviews() {
        if (!shown) return
        if (previewsStale) disposePreviews()
        previewsStale = false
        for (i in environments.indices) {
            if (previews[i] == null) previews[i] = renderPreview(environments[i])
        }
    }

    /** One frame of [environment]'s scene; null if the scene could not be built (the card then shows a blank panel). */
    private fun renderPreview(environment: Environment): FrameBuffer? {
        val scene = environment.createScene(seed)
        var fbo: FrameBuffer? = null
        try {
            val ride = RideState(AdjustablePace.DEFAULT_METERS_PER_SECOND)
            ride.worldScale = environment.speedScale
            scene.create(ride)
            scene.resize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
            val dt = 1f / 60f
            repeat((PREVIEW_SECONDS * 60).toInt()) {
                ride.step(dt, AdjustablePace.DEFAULT_METERS_PER_SECOND)
                scene.step(dt, ride)
            }
            fbo = FrameBuffer(Pixmap.Format.RGBA8888, PREVIEW_WIDTH, PREVIEW_HEIGHT, true)
            fbo.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            fbo.begin()
            scene.draw(ride)
            fbo.end()
            return fbo
        } catch (e: RuntimeException) {
            Gdx.app?.error("Roadwork", "No preview for ${environment.id}", e)
            fbo?.dispose()
            return null
        } finally {
            scene.dispose()
        }
    }

    private fun disposePreviews() {
        for (i in previews.indices) {
            previews[i]?.dispose()
            previews[i] = null
        }
    }

    fun draw() {
        refreshSummaries()
        val gl = Gdx.gl
        gl.glDepthMask(true)
        gl.glDisable(GL20.GL_DEPTH_TEST)
        gl.glDisable(GL20.GL_CULL_FACE)
        gl.glClearColor(BACKGROUND.r, BACKGROUND.g, BACKGROUND.b, 1f)
        gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        camera.update()
        shapes.projectionMatrix = camera.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = BUTTON
        roundRect(layout.exit.x, layout.exit.y, layout.exit.width, layout.exit.height, 20f)
        for (i in environments.indices) {
            val c = layout.cards[i]
            if (i == highlight) {
                shapes.color = ACCENT
                roundRect(c.bounds.x - OUTLINE, c.bounds.y - OUTLINE, c.bounds.width + 2 * OUTLINE, c.bounds.height + 2 * OUTLINE, 26f + OUTLINE)
            }
            shapes.color = if (i == highlight) CARD_HIGHLIGHT else CARD
            roundRect(c.bounds.x, c.bounds.y, c.bounds.width, c.bounds.height, 26f)
            shapes.color = PREVIEW_EMPTY
            shapes.rect(c.preview.x, c.preview.y, c.preview.width, c.preview.height)
            shapes.color = ACCENT
            roundRect(c.ride.x, c.ride.y, c.ride.width, c.ride.height, 20f)
            shapes.color = BUTTON
            roundRect(c.settings.x, c.settings.y, c.settings.width, c.settings.height, 20f)
            shapes.color = Color.WHITE
            UiIcons.gear(shapes, c.settings.x + c.settings.width / 2f, c.settings.y + c.settings.height / 2f, c.settings.height / 120f)
        }
        shapes.end()

        batch.projectionMatrix = camera.combined
        batch.shader = null // the stock shader, for the preview images
        batch.disableBlending()
        batch.begin()
        batch.setColor(Color.WHITE)
        for (i in environments.indices) {
            val texture = previews[i]?.colorBufferTexture ?: continue
            val p = layout.cards[i].preview
            // Fill the box, cropping the 16:9 image evenly where the box is wider. Framebuffer
            // textures have their origin at the bottom left, as this camera does.
            val shownFraction = ((PREVIEW_WIDTH.toFloat() / PREVIEW_HEIGHT) / (p.width / p.height)).coerceAtMost(1f)
            val v0 = (1f - shownFraction) / 2f
            batch.draw(texture, p.x, p.y, p.width, p.height, 0f, v0, 1f, v0 + shownFraction)
        }
        batch.end()

        gl.glEnable(GL20.GL_BLEND)
        gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        batch.enableBlending()
        batch.shader = textShader
        batch.begin()
        val h = layout.header
        drawText(headerLayout, SCALE_HEADER, h.x, h.y + (h.height + headerLayout.height) / 2f)
        drawCentred(exitLayout, SCALE_BUTTON, layout.exit)
        val titleScale = if (layout.compact) SCALE_TITLE_COMPACT else SCALE_TITLE
        for (i in environments.indices) {
            val c = layout.cards[i]
            drawLeft(titleLayouts[i], titleScale, c.title)
            drawLeft(summaryLayouts[i], SCALE_SUMMARY, c.summary)
            drawCentred(rideLayout, SCALE_BUTTON, c.ride)
        }
        batch.end()
        gl.glDisable(GL20.GL_BLEND)
    }

    /** The music line of each card. The controller answers from a cache, so asking every frame is cheap. */
    private fun refreshSummaries() {
        for (i in environments.indices) {
            val summary = music.playlistSummary(environments[i].playlist)
            if (summary == shownSummaries[i]) continue
            shownSummaries[i] = summary
            val color = if (summary.problem != null) WARN else TEXT_DIM
            layoutText(summaryLayouts[i], summary.text, SCALE_SUMMARY, color, layout.cards[i].summary.width)
        }
    }

    private fun drawText(layout: GlyphLayout, scale: Float, x: Float, y: Float) {
        font.data.setScale(scale)
        font.draw(batch, layout, x, y)
    }

    private fun drawLeft(layout: GlyphLayout, scale: Float, box: Rectangle) =
        drawText(layout, scale, box.x, box.y + (box.height + layout.height) / 2f)

    private fun drawCentred(layout: GlyphLayout, scale: Float, box: Rectangle) =
        drawText(layout, scale, box.x + (box.width - layout.width) / 2f, box.y + (box.height + layout.height) / 2f)

    private fun layoutText(layout: GlyphLayout, text: CharSequence, scale: Float, color: Color, maxWidth: Float = 0f) {
        font.data.setScale(scale)
        if (maxWidth > 0f) {
            layout.setText(font, text, 0, text.length, color, maxWidth, Align.left, false, "...")
        } else {
            layout.setText(font, text, color, 0f, Align.left, false)
        }
    }

    private fun roundRect(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        shapes.rect(x + radius, y, w - 2 * radius, h)
        shapes.rect(x, y + radius, radius, h - 2 * radius)
        shapes.rect(x + w - radius, y + radius, radius, h - 2 * radius)
        shapes.arc(x + radius, y + radius, radius, 180f, 90f, 8)
        shapes.arc(x + w - radius, y + radius, radius, 270f, 90f, 8)
        shapes.arc(x + w - radius, y + h - radius, radius, 0f, 90f, 8)
        shapes.arc(x + radius, y + h - radius, radius, 90f, 90f, 8)
    }

    // ---- input ----

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        val x = screenX * layout.width / Gdx.graphics.width
        val y = PickerLayout.HEIGHT - screenY * PickerLayout.HEIGHT / Gdx.graphics.height
        if (layout.exit.contains(x, y)) {
            listener.onExit()
            return true
        }
        for (i in environments.indices) {
            val c = layout.cards[i]
            when {
                c.settings.contains(x, y) -> listener.onSettings(environments[i])
                // The picture is as good a place to tap as the button.
                c.ride.contains(x, y) || c.preview.contains(x, y) -> listener.onRide(environments[i])
                else -> continue
            }
            return true
        }
        return false
    }

    override fun keyDown(keycode: Int): Boolean {
        when (keycode) {
            Input.Keys.LEFT -> highlight = (highlight + environments.size - 1) % environments.size
            Input.Keys.RIGHT -> highlight = (highlight + 1) % environments.size
            Input.Keys.ENTER, Input.Keys.SPACE -> listener.onRide(environments[highlight])
            Input.Keys.M, Input.Keys.S -> listener.onSettings(environments[highlight])
            Input.Keys.BACK, Input.Keys.ESCAPE -> listener.onExit()
            else -> return false
        }
        return true
    }

    fun dispose() {
        disposePreviews()
        if (::batch.isInitialized) batch.dispose()
        if (::textShader.isInitialized) textShader.dispose()
        if (::shapes.isInitialized) shapes.dispose()
        if (::font.isInitialized) font.dispose()
    }

    companion object {
        /** How far into a ride the preview frame is taken: the scene has settled in by then. */
        const val PREVIEW_SECONDS = 30f
        const val PREVIEW_WIDTH = 960
        const val PREVIEW_HEIGHT = 540

        /** The default font is 15 px tall, so a scale of 1.9 is the 28 px minimum for the tablet. */
        const val FONT_PIXELS = 15f
        const val SCALE_HEADER = 3.2f
        const val SCALE_TITLE = 2.8f
        const val SCALE_TITLE_COMPACT = 2.3f
        const val SCALE_SUMMARY = 1.9f
        const val SCALE_BUTTON = 2.6f
        private const val OUTLINE = 6f
        private val BACKGROUND = Color(0.055f, 0.070f, 0.100f, 1f)
        private val CARD = Color(0.105f, 0.130f, 0.180f, 1f)
        private val CARD_HIGHLIGHT = Color(0.135f, 0.165f, 0.225f, 1f)
        private val PREVIEW_EMPTY = Color(0.07f, 0.09f, 0.13f, 1f)
        private val ACCENT = Color(0.16f, 0.52f, 0.86f, 1f)
        private val BUTTON = Color(0.25f, 0.29f, 0.36f, 1f)
        private val TEXT_DIM = Color(0.82f, 0.86f, 0.90f, 1f)
        private val WARN = Color(1f, 0.74f, 0.30f, 1f)
    }
}
