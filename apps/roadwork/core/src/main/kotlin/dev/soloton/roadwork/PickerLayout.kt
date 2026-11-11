package dev.soloton.roadwork

import com.badlogic.gdx.math.Rectangle
import kotlin.math.ceil
import kotlin.math.min

/**
 * Where the environment picker's cards sit, in the same virtual space as the HUD: [HEIGHT] pixels
 * tall, as wide as the screen's aspect ratio makes it (1920 at 16:9), origin at the bottom left.
 * Pure maths, so tests can check sizes and overlaps for any number of environments.
 *
 * One to four environments sit in a single row; five to eight in two rows. A card stacks a
 * preview image, the title, the music summary and two buttons:
 *
 * ```
 *  +-----------------------------+
 *  |          preview            |
 *  |                             |
 *  | Title                       |
 *  | 14 of 20 songs from 1 folder|
 *  | [    Ride    ] [   Music   ]|
 *  +-----------------------------+
 * ```
 */
class PickerLayout {
    class Card {
        val bounds = Rectangle()
        val preview = Rectangle()
        /** Boxes the title and summary lines are drawn in (left aligned, vertically centred, truncated to fit). */
        val title = Rectangle()
        val summary = Rectangle()
        val ride = Rectangle()
        val music = Rectangle()

        fun parts(): List<Rectangle> = listOf(preview, title, summary, ride, music)
    }

    var width = 1920f
        private set

    /** The strip across the top holding the screen's heading. */
    val header = Rectangle()

    var cards: List<Card> = emptyList()
        private set

    /** Two-row layouts use smaller text and padding. */
    var compact = false
        private set

    fun layout(count: Int, screenWidth: Int, screenHeight: Int) {
        require(count in 1..MAX_CARDS) { "the picker holds 1 to $MAX_CARDS environments, not $count" }
        if (screenWidth <= 0 || screenHeight <= 0) return
        width = HEIGHT * screenWidth / screenHeight
        if (cards.size != count) cards = List(count) { Card() }

        val rows = if (count <= 4) 1 else 2
        val columns = ceil(count / rows.toFloat()).toInt()
        compact = rows > 1
        val pad = if (compact) 16f else 20f
        val titleH = if (compact) TITLE_HEIGHT_COMPACT else TITLE_HEIGHT
        val summaryH = SUMMARY_HEIGHT
        val buttonH = if (compact) BUTTON_HEIGHT_COMPACT else BUTTON_HEIGHT

        header.set(MARGIN, HEIGHT - MARGIN - HEADER_HEIGHT, width - 2 * MARGIN, HEADER_HEIGHT)
        val areaW = width - 2 * MARGIN
        val areaH = header.y - GAP - MARGIN
        val cardW = min((areaW - (columns - 1) * GAP) / columns, MAX_CARD_WIDTH)
        val rowH = (areaH - (rows - 1) * GAP) / rows
        // Everything but the preview has a fixed height; the preview takes 16:9 if there is room, else what is left.
        val fixed = pad + TEXT_GAP + titleH + summaryH + TEXT_GAP + buttonH + pad
        val previewW = cardW - 2 * pad
        val previewH = min(previewW * 9f / 16f, rowH - fixed)
        val cardH = fixed + previewH
        val blockH = rows * cardH + (rows - 1) * GAP
        val top = MARGIN + areaH - (areaH - blockH) / 2f

        for (i in 0 until count) {
            val row = i / columns
            val inRow = if (row == rows - 1) count - row * columns else columns
            val rowW = inRow * cardW + (inRow - 1) * GAP
            val x = (width - rowW) / 2f + (i - row * columns) * (cardW + GAP)
            val y = top - row * (cardH + GAP) - cardH
            val c = cards[i]
            c.bounds.set(x, y, cardW, cardH)
            val buttonW = (previewW - BUTTON_GAP) / 2f
            c.ride.set(x + pad, y + pad, buttonW, buttonH)
            c.music.set(x + pad + buttonW + BUTTON_GAP, y + pad, buttonW, buttonH)
            c.summary.set(x + pad, c.ride.y + buttonH + TEXT_GAP, previewW, summaryH)
            c.title.set(x + pad, c.summary.y + summaryH, previewW, titleH)
            c.preview.set(x + pad, c.title.y + titleH + TEXT_GAP, previewW, previewH)
        }
    }

    companion object {
        const val HEIGHT = HudLayout.HEIGHT
        const val MAX_CARDS = 8
        const val MARGIN = 36f
        const val GAP = 24f
        const val HEADER_HEIGHT = 84f
        const val MAX_CARD_WIDTH = 880f
        const val BUTTON_HEIGHT = 120f
        const val BUTTON_HEIGHT_COMPACT = 112f
        const val BUTTON_GAP = 16f
        const val TITLE_HEIGHT = 60f
        const val TITLE_HEIGHT_COMPACT = 48f
        const val SUMMARY_HEIGHT = 44f
        const val TEXT_GAP = 12f

        /** Smallest touch target, in pixels on the 1080-line screen. */
        const val MIN_TOUCH = 112f
    }
}
