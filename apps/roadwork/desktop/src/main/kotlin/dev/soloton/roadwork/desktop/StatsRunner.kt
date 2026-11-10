package dev.soloton.roadwork.desktop

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.profiling.GLProfiler
import dev.soloton.roadwork.RoadworkGame
import java.lang.management.ManagementFactory

/**
 * Wraps the game in a window and logs frame statistics every [intervalSeconds]: average frame
 * interval, time spent in `render()`, GL draw calls and vertices per frame (libGDX [GLProfiler]),
 * and bytes allocated on the render thread per frame. The first [warmupSeconds] are not counted.
 * Exits after [durationSeconds] when it is positive.
 */
class StatsRunner(
    private val game: RoadworkGame,
    private val durationSeconds: Float,
    private val intervalSeconds: Float = 5f,
    private val warmupSeconds: Float = 3f,
) : ApplicationListener {
    private lateinit var profiler: GLProfiler
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private var wall = 0f
    private var windowTime = 0f
    private var frames = 0
    private var renderNanos = 0L
    private var drawCalls = 0L
    private var vertices = 0.0
    private var allocated = 0L
    private var shaderSwitches = 0L
    private var totalFrames = 0
    private var totalAllocated = 0L

    override fun create() {
        game.create()
        profiler = GLProfiler(Gdx.graphics)
        profiler.enable()
    }

    override fun resize(width: Int, height: Int) = game.resize(width, height)

    override fun render() {
        val dt = Gdx.graphics.deltaTime
        wall += dt
        profiler.reset()
        val alloc0 = threads.currentThreadAllocatedBytes
        val t0 = System.nanoTime()
        game.render()
        val t1 = System.nanoTime()
        val alloc1 = threads.currentThreadAllocatedBytes
        if (wall < warmupSeconds) return
        frames++
        windowTime += dt
        renderNanos += t1 - t0
        drawCalls += profiler.drawCalls
        shaderSwitches += profiler.shaderSwitches
        vertices += profiler.vertexCount.total
        allocated += alloc1 - alloc0
        totalFrames++
        totalAllocated += alloc1 - alloc0
        if (windowTime >= intervalSeconds) {
            println(
                (
                    "stats: %d frames, avg frame %.2f ms (%.1f fps), render() %.2f ms, draw calls/frame %.1f, " +
                        "shader switches/frame %.1f, vertices/frame %.0f (~%.0f triangles), alloc/frame %.0f bytes"
                    ).format(
                    frames, windowTime * 1000f / frames, frames / windowTime, renderNanos / 1e6 / frames,
                    drawCalls.toDouble() / frames, shaderSwitches.toDouble() / frames,
                    vertices / frames, vertices / frames / 3.0, allocated.toDouble() / frames,
                ),
            )
            frames = 0; windowTime = 0f; renderNanos = 0; drawCalls = 0; vertices = 0.0; allocated = 0; shaderSwitches = 0
        }
        if (durationSeconds > 0f && wall >= durationSeconds) {
            println("stats: done, %d frames counted, %.1f bytes allocated per frame on average".format(
                totalFrames, totalAllocated.toDouble() / totalFrames.coerceAtLeast(1)))
            Gdx.app.exit()
        }
    }

    override fun pause() = game.pause()
    override fun resume() = game.resume()
    override fun dispose() {
        profiler.disable()
        game.dispose()
    }
}
