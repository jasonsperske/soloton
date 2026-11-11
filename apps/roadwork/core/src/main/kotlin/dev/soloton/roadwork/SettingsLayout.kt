package dev.soloton.roadwork

import com.badlogic.gdx.math.Rectangle

/**
 * Where everything on an environment's settings screen sits, in the same virtual space as the HUD
 * and the picker: [HEIGHT] pixels tall, as wide as the screen's aspect ratio makes it, origin at
 * the bottom left. Pure maths, so tests can check sizes and overlaps.
 *
 * ```
 *  Skylift Mesa settings                                        [  Done  ]
 *  +---------------------------------------------------------------------+
 *  | Music      14 of 20 songs from 1 folder          [  Choose music  ] |
 *  | Units      [ Kilometres ] [   Miles   ]                             |
 *  | Goal       [ Free ride ] [ Distance ] [   Time   ]                  |
 *  |            [ - ]   10 km   [ + ]                                    |
 *  | Aircraft   [ < ]   In turn: glider, paramotor, plane   [ > ]        |
 *  +---------------------------------------------------------------------+
 * ```
 * One row each for music, units, the goal and the goal's amount, then one per option of the
 * environment (up to [MAX_OPTIONS]). Every control is at least [MIN_TOUCH] pixels each way.
 */
class SettingsLayout {
    var width = 1920f
        private set

    /** The strip across the top: the heading, with [done] at its right end. */
    val header = Rectangle()
    val done = Rectangle()

    /** The panel behind each row, top to bottom: music, units, goal, goal amount, then the options. */
    var rows: List<Rectangle> = emptyList()
        private set

    /** Where each row's name is written, at its left end. */
    var labels: List<Rectangle> = emptyList()
        private set

    val musicSummary = Rectangle()
    val musicButton = Rectangle()
    val units = Array(Units.entries.size) { Rectangle() }
    val goals = Array(GoalKind.entries.size) { Rectangle() }
    val amountLess = Rectangle()
    val amountValue = Rectangle()
    val amountMore = Rectangle()

    /** For each option: the button to the previous choice, the box naming the current one, and the button to the next. */
    var optionPrevious: List<Rectangle> = emptyList()
        private set
    var optionValue: List<Rectangle> = emptyList()
        private set
    var optionNext: List<Rectangle> = emptyList()
        private set

    /** Every control that can be tapped, for overlap and size checks. */
    fun controls(): List<Rectangle> =
        listOf(done, musicButton, amountLess, amountMore) + units + goals + optionPrevious + optionNext

    fun layout(optionCount: Int, screenWidth: Int, screenHeight: Int) {
        require(optionCount in 0..MAX_OPTIONS) { "the settings screen holds up to $MAX_OPTIONS options, not $optionCount" }
        if (screenWidth <= 0 || screenHeight <= 0) return
        width = HEIGHT * screenWidth / screenHeight
        val rowCount = FIXED_ROWS + optionCount
        if (rows.size != rowCount) {
            rows = List(rowCount) { Rectangle() }
            labels = List(rowCount) { Rectangle() }
            optionPrevious = List(optionCount) { Rectangle() }
            optionValue = List(optionCount) { Rectangle() }
            optionNext = List(optionCount) { Rectangle() }
        }

        header.set(MARGIN, HEIGHT - MARGIN - HEADER_HEIGHT, width - 2 * MARGIN, HEADER_HEIGHT)
        done.set(header.x + header.width - DONE_WIDTH, header.y, DONE_WIDTH, HEADER_HEIGHT)

        val left = MARGIN
        val rowWidth = width - 2 * MARGIN
        val controlsLeft = left + LABEL_WIDTH
        val controlsRight = left + rowWidth - ROW_PAD
        var top = header.y - GAP
        for (r in 0 until rowCount) {
            val y = top - ROW_HEIGHT
            rows[r].set(left, y, rowWidth, ROW_HEIGHT)
            labels[r].set(left + ROW_PAD + 12f, y, LABEL_WIDTH - ROW_PAD - 24f, ROW_HEIGHT)
            top = y - ROW_GAP
        }
        fun controlY(row: Int) = rows[row].y + (ROW_HEIGHT - CONTROL) / 2f

        musicButton.set(controlsRight - MUSIC_BUTTON_WIDTH, controlY(ROW_MUSIC), MUSIC_BUTTON_WIDTH, CONTROL)
        musicSummary.set(controlsLeft, rows[ROW_MUSIC].y, musicButton.x - CONTROL_GAP - controlsLeft, ROW_HEIGHT)

        for (i in units.indices) units[i].set(controlsLeft + i * (UNIT_WIDTH + CONTROL_GAP), controlY(ROW_UNITS), UNIT_WIDTH, CONTROL)
        for (i in goals.indices) goals[i].set(controlsLeft + i * (GOAL_WIDTH + CONTROL_GAP), controlY(ROW_GOAL), GOAL_WIDTH, CONTROL)

        amountLess.set(controlsLeft, controlY(ROW_AMOUNT), CONTROL, CONTROL)
        amountValue.set(amountLess.x + CONTROL + CONTROL_GAP, controlY(ROW_AMOUNT), AMOUNT_WIDTH, CONTROL)
        amountMore.set(amountValue.x + AMOUNT_WIDTH + CONTROL_GAP, controlY(ROW_AMOUNT), CONTROL, CONTROL)

        for (o in 0 until optionCount) {
            val row = FIXED_ROWS + o
            optionPrevious[o].set(controlsLeft, controlY(row), CONTROL, CONTROL)
            val valueLeft = controlsLeft + CONTROL + CONTROL_GAP
            val valueWidth = (controlsRight - CONTROL - CONTROL_GAP - valueLeft).coerceAtMost(OPTION_VALUE_MAX_WIDTH)
            optionValue[o].set(valueLeft, controlY(row), valueWidth, CONTROL)
            optionNext[o].set(valueLeft + valueWidth + CONTROL_GAP, controlY(row), CONTROL, CONTROL)
        }
    }

    companion object {
        const val HEIGHT = HudLayout.HEIGHT
        const val MARGIN = 36f
        const val GAP = 24f
        const val HEADER_HEIGHT = 112f
        const val DONE_WIDTH = 260f

        /** The rows that every environment has: music, units, goal, goal amount. */
        const val FIXED_ROWS = 4
        const val ROW_MUSIC = 0
        const val ROW_UNITS = 1
        const val ROW_GOAL = 2
        const val ROW_AMOUNT = 3
        const val MAX_OPTIONS = 2

        const val ROW_HEIGHT = 128f
        const val ROW_GAP = 12f
        const val ROW_PAD = 16f
        const val LABEL_WIDTH = 300f

        /** Height of every control, and the side of the square ones. */
        const val CONTROL = 112f
        const val CONTROL_GAP = 16f
        const val MUSIC_BUTTON_WIDTH = 340f
        const val UNIT_WIDTH = 300f
        const val GOAL_WIDTH = 250f
        const val AMOUNT_WIDTH = 280f
        const val OPTION_VALUE_MAX_WIDTH = 900f

        /** Smallest touch target, in pixels on the 1080-line screen. */
        const val MIN_TOUCH = 112f
    }
}
