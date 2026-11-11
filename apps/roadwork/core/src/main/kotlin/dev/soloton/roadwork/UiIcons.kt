package dev.soloton.roadwork

import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Icons shared by the HUD, the picker and the settings screen, drawn with a filled [ShapeRenderer] in its current colour. */
internal object UiIcons {
    /** A settings gear centred on ([cx], [cy]): eight teeth round a ring, about 80 px across at [scale] 1. */
    fun gear(shapes: ShapeRenderer, cx: Float, cy: Float, scale: Float = 1f) {
        for (i in 0 until 8) {
            val a = (i * PI / 4).toFloat()
            val c = cos(a)
            val s = sin(a)
            shapes.rectLine(cx + c * 27f * scale, cy + s * 27f * scale, cx + c * 39f * scale, cy + s * 39f * scale, 14f * scale)
        }
        // The ring is an annulus of short segments, so the centre stays see-through.
        for (i in 0 until 16) {
            val a0 = (i * PI / 8).toFloat()
            val a1 = ((i + 1) * PI / 8).toFloat()
            shapes.rectLine(
                cx + cos(a0) * 21f * scale, cy + sin(a0) * 21f * scale,
                cx + cos(a1) * 21f * scale, cy + sin(a1) * 21f * scale, 17f * scale,
            )
        }
    }

    /** A rectangle with rounded corners, built without overlap so a translucent colour has an even tone. */
    fun roundRect(shapes: ShapeRenderer, x: Float, y: Float, w: Float, h: Float, radius: Float) {
        shapes.rect(x + radius, y, w - 2 * radius, h)
        shapes.rect(x, y + radius, radius, h - 2 * radius)
        shapes.rect(x + w - radius, y + radius, radius, h - 2 * radius)
        shapes.arc(x + radius, y + radius, radius, 180f, 90f, 8)
        shapes.arc(x + w - radius, y + radius, radius, 270f, 90f, 8)
        shapes.arc(x + w - radius, y + h - radius, radius, 0f, 90f, 8)
        shapes.arc(x + radius, y + h - radius, radius, 90f, 90f, 8)
    }
}
