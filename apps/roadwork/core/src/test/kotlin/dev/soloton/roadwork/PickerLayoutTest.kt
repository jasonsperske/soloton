package dev.soloton.roadwork

import com.badlogic.gdx.math.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The picker's cards for one to eight environments: on screen, big enough to touch and read, nothing overlapping. */
class PickerLayoutTest {
    private fun layout(count: Int, w: Int = 1920, h: Int = 1080) = PickerLayout().apply { layout(count, w, h) }

    private fun inside(outer: Rectangle, inner: Rectangle): Boolean =
        inner.x >= outer.x - 0.01f && inner.y >= outer.y - 0.01f &&
            inner.x + inner.width <= outer.x + outer.width + 0.01f && inner.y + inner.height <= outer.y + outer.height + 0.01f

    /** Overlap by more than a rounding error (touching edges are fine). */
    private fun overlap(a: Rectangle, b: Rectangle): Boolean =
        a.x < b.x + b.width - 0.01f && b.x < a.x + a.width - 0.01f && a.y < b.y + b.height - 0.01f && b.y < a.y + a.height - 0.01f

    @Test
    fun cardsAndTheirPartsStayOnA1080pScreenForOneToEightEnvironments() {
        for (n in 1..PickerLayout.MAX_CARDS) {
            val l = layout(n)
            assertEquals(1920f, l.width)
            assertEquals(n, l.cards.size)
            val screen = Rectangle(0f, 0f, 1920f, 1080f)
            assertTrue(inside(screen, l.header), "$n: header $screen")
            for ((i, c) in l.cards.withIndex()) {
                assertTrue(inside(screen, c.bounds), "$n cards: card $i at ${c.bounds} leaves the screen")
                assertFalse(overlap(c.bounds, l.header), "$n cards: card $i under the heading")
                // A margin from the screen edge, like the HUD's.
                assertTrue(c.bounds.x >= PickerLayout.MARGIN - 0.01f && c.bounds.y >= PickerLayout.MARGIN - 0.01f, "$n: card $i margin")
                assertTrue(c.bounds.x + c.bounds.width <= 1920f - PickerLayout.MARGIN + 0.01f, "$n: card $i right margin")
                for (part in c.parts()) assertTrue(inside(c.bounds, part), "$n cards: card $i part $part outside ${c.bounds}")
            }
        }
    }

    @Test
    fun nothingOverlaps() {
        for (n in 1..PickerLayout.MAX_CARDS) {
            val l = layout(n)
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    assertFalse(overlap(l.cards[i].bounds, l.cards[j].bounds), "$n cards: $i and $j overlap")
                    // Room between cards, so a tap near an edge is not ambiguous.
                    val a = l.cards[i].bounds
                    val b = l.cards[j].bounds
                    val apartX = maxOf(b.x - (a.x + a.width), a.x - (b.x + b.width))
                    val apartY = maxOf(b.y - (a.y + a.height), a.y - (b.y + b.height))
                    assertTrue(maxOf(apartX, apartY) >= PickerLayout.GAP - 0.01f, "$n cards: $i and $j are closer than the gap")
                }
                val parts = l.cards[i].parts()
                for (p in parts.indices) for (q in p + 1 until parts.size) {
                    assertFalse(overlap(parts[p], parts[q]), "$n cards: parts $p and $q of card $i overlap")
                }
            }
        }
    }

    @Test
    fun buttonsAreAtLeast112PixelsAndTextAtLeast28() {
        for (n in 1..PickerLayout.MAX_CARDS) {
            val l = layout(n)
            for (c in l.cards) {
                for (b in listOf(c.ride, c.music)) {
                    assertTrue(b.width >= PickerLayout.MIN_TOUCH && b.height >= PickerLayout.MIN_TOUCH, "$n cards: button $b")
                }
                assertEquals(c.ride.width, c.music.width)
                assertEquals(c.ride.y, c.music.y)
                assertTrue(c.ride.x < c.music.x, "Ride comes first")
                // The picture is worth showing, and the text lines are tall enough for their text.
                assertTrue(c.preview.height >= 150f && c.preview.width >= 300f, "$n cards: preview ${c.preview}")
                assertTrue(c.preview.width / c.preview.height >= 16f / 9f - 0.01f, "$n cards: preview is never taller than 16:9")
                val titleScale = if (l.compact) Picker.SCALE_TITLE_COMPACT else Picker.SCALE_TITLE
                assertTrue(c.title.height >= titleScale * Picker.FONT_PIXELS, "$n cards: title box ${c.title}")
                assertTrue(c.summary.height >= Picker.SCALE_SUMMARY * Picker.FONT_PIXELS, "$n cards: summary box ${c.summary}")
            }
        }
        for (scale in listOf(Picker.SCALE_HEADER, Picker.SCALE_TITLE, Picker.SCALE_TITLE_COMPACT, Picker.SCALE_SUMMARY, Picker.SCALE_BUTTON)) {
            assertTrue(scale * Picker.FONT_PIXELS >= 28f, "text at scale $scale is ${scale * Picker.FONT_PIXELS} px")
        }
        assertTrue(PickerLayout.MIN_TOUCH >= 112f)
    }

    @Test
    fun upToFourSitInOneRowAndFiveToEightInTwo() {
        for (n in 1..PickerLayout.MAX_CARDS) {
            val l = layout(n)
            val rows = l.cards.map { it.bounds.y }.distinct().size
            assertEquals(if (n <= 4) 1 else 2, rows, "$n cards")
            assertEquals(n > 4, l.compact)
            // Every card is the same size, and each row is centred.
            assertEquals(1, l.cards.map { it.bounds.width to it.bounds.height }.distinct().size, "$n cards")
            for (row in l.cards.groupBy { it.bounds.y }.values) {
                val left = row.minOf { it.bounds.x }
                val right = 1920f - row.maxOf { it.bounds.x + it.bounds.width }
                assertEquals(left, right, 0.01f, "$n cards: row not centred")
            }
        }
        // A lone card does not stretch across the screen.
        assertTrue(layout(1).cards.single().bounds.width <= PickerLayout.MAX_CARD_WIDTH)
    }

    @Test
    fun layoutScalesWithScreenHeightAndFollowsTheAspectRatio() {
        for (n in 1..PickerLayout.MAX_CARDS) {
            val reference = layout(n)
            // 720p is the same layout: it is expressed in 1080ths of the height.
            val small = layout(n, 1280, 720)
            assertEquals(reference.width, small.width)
            assertEquals(reference.cards.map { it.bounds }, small.cards.map { it.bounds })
            // Other shapes: still on screen and not overlapping.
            for ((w, h) in listOf(1440 to 1080, 2560 to 1080, 1920 to 1200)) {
                val l = layout(n, w, h)
                val screen = Rectangle(0f, 0f, l.width, PickerLayout.HEIGHT)
                assertEquals(1080f * w / h, l.width, 0.01f)
                for (c in l.cards) {
                    assertTrue(inside(screen, c.bounds), "$n cards at ${w}x$h: ${c.bounds}")
                    assertTrue(c.ride.width >= PickerLayout.MIN_TOUCH && c.preview.height >= 100f, "$n cards at ${w}x$h")
                }
                for (i in 0 until n) for (j in i + 1 until n) assertFalse(overlap(l.cards[i].bounds, l.cards[j].bounds))
            }
        }
    }

    @Test
    fun theRegistryFitsThePicker() {
        assertTrue(Environments.all.size in 1..PickerLayout.MAX_CARDS, "the picker shows at most ${PickerLayout.MAX_CARDS} environments")
        assertFailsWith<IllegalArgumentException> { layout(0) }
        assertFailsWith<IllegalArgumentException> { layout(9) }
    }
}
