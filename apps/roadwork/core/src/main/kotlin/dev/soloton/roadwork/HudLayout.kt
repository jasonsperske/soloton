package dev.soloton.roadwork

import com.badlogic.gdx.math.Rectangle

/**
 * Where the HUD's panels and buttons sit, in a virtual space [HEIGHT] pixels tall whose width
 * follows the screen's aspect ratio (1920 wide at 16:9). Origin at the bottom left. Pure maths, so
 * tests can check other overlays against it.
 */
class HudLayout {
    enum class Button {
        SLOWER, FASTER, SKIP, PAUSE, SETTINGS,

        /** Back to the environment picker. Part of the pause overlay: only there while paused. */
        ENVIRONMENTS,
    }

    var width = 1920f
        private set

    val buttons = Array(Button.entries.size) { Rectangle() }
    val statsPanel = Rectangle()
    val pacePanel = Rectangle()
    val musicPanel = Rectangle()

    fun rect(b: Button): Rectangle = buttons[b.ordinal]

    /** The panels and buttons that are always on screen (not the pause overlay's), for overlap checks. */
    fun all(): List<Rectangle> =
        listOf(statsPanel, pacePanel, musicPanel) + Button.entries.filter { it != Button.ENVIRONMENTS }.map { rect(it) }

    fun layout(screenWidth: Int, screenHeight: Int) {
        if (screenWidth <= 0 || screenHeight <= 0) return
        width = HEIGHT * screenWidth / screenHeight
        val m = MARGIN
        val b = BUTTON
        statsPanel.set(m, HEIGHT - m - 186f, 400f, 186f)
        rect(Button.SETTINGS).set(width - m - b, HEIGHT - m - b, b, b)
        rect(Button.PAUSE).set(width - m - b - GAP - b, HEIGHT - m - b, b, b)
        val pad = PAD
        val panelH = b + 2 * pad
        pacePanel.set(m, m, b * 2 + 300f + 4 * pad, panelH)
        rect(Button.SLOWER).set(pacePanel.x + pad, m + pad, b, b)
        rect(Button.FASTER).set(pacePanel.x + pacePanel.width - pad - b, m + pad, b, b)
        val musicW = (width - pacePanel.width - 3 * m - 40f).coerceAtMost(820f)
        musicPanel.set(width - m - musicW, m, musicW, panelH)
        rect(Button.SKIP).set(musicPanel.x + musicPanel.width - pad - b, m + pad, b, b)
        // Under the PAUSED banner and its two lines of small text.
        rect(Button.ENVIRONMENTS).set((width - OVERLAY_BUTTON_WIDTH) / 2f, HEIGHT / 2f - 250f, OVERLAY_BUTTON_WIDTH, b)
    }

    companion object {
        const val HEIGHT = 1080f
        const val MARGIN = 36f
        const val BUTTON = 120f
        const val GAP = 24f
        const val PAD = 16f
        const val OVERLAY_BUTTON_WIDTH = 440f
    }
}
