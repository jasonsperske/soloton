package dev.soloton.roadwork.desktop

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import dev.soloton.roadwork.RoadworkGame
import java.io.File
import java.util.zip.Deflater

/**
 * Wraps the game for screenshot mode: advances simulated time to [atSeconds] in fixed steps
 * (a ride; the picker has no clock), draws one frame into an offscreen [width]x[height] framebuffer (independent of window and
 * screen size), writes it as PNG and exits. Any error is kept in [failure].
 */
class ScreenshotRunner(
    private val game: RoadworkGame,
    private val output: File,
    private val atSeconds: Double,
    private val width: Int,
    private val height: Int,
) : ApplicationAdapter() {
    var failure: Throwable? = null
        private set
    private var done = false

    override fun create() {
        try {
            game.create()
        } catch (t: Throwable) {
            failure = t
            done = true
            Gdx.app.exit()
        }
    }

    override fun render() {
        if (done) return
        done = true
        try {
            game.simulateTo(atSeconds)
            val fbo = FrameBuffer(Pixmap.Format.RGBA8888, width, height, true)
            try {
                fbo.begin()
                game.resize(width, height)
                game.draw()
                val pixmap = Pixmap.createFromFrameBuffer(0, 0, width, height)
                fbo.end()
                try {
                    output.parentFile?.mkdirs()
                    PixmapIO.writePNG(FileHandle(output), pixmap, Deflater.DEFAULT_COMPRESSION, true)
                } finally {
                    pixmap.dispose()
                }
            } finally {
                fbo.dispose()
            }
        } catch (t: Throwable) {
            failure = t
        }
        Gdx.app.exit()
    }

    override fun dispose() {
        try {
            game.dispose()
        } catch (t: Throwable) {
            if (failure == null) failure = t
        }
    }
}
