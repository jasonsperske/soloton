package dev.soloton.roadwork.desktop

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import dev.soloton.roadwork.RoadworkGame
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Usage: roadwork-desktop [--screenshot <out.png> --at <seconds> [--size <W>x<H>]]

Without arguments, opens a 1280x720 window.
--screenshot  render one frame offscreen and write it as PNG, then exit
--at          simulated seconds to advance to (fixed 1/60 s steps), default 0
--size        image size, default 1920x1080 (the bike's display)"""

fun main(args: Array<String>) {
    val opts = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}\n\n$USAGE")
        exitProcess(2)
    }
    if (opts.help) {
        println(USAGE)
        return
    }

    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Roadwork")
        // Match the device: OpenGL ES 2.0 feature level.
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL20, 2, 0)
    }

    val shot = opts.screenshot
    if (shot == null) {
        config.setWindowedMode(1280, 720)
        config.useVsync(true)
        config.setForegroundFPS(60)
        Lwjgl3Application(RoadworkGame(), config)
        return
    }

    config.setWindowedMode(640, 360)
    config.setResizable(false)
    val runner = ScreenshotRunner(RoadworkGame(), shot, opts.atSeconds, opts.width, opts.height)
    Lwjgl3Application(runner, config) // returns after the app exits
    runner.failure?.let {
        System.err.println("screenshot failed: $it")
        it.printStackTrace()
        exitProcess(1)
    }
    if (!shot.isFile || shot.length() == 0L) {
        System.err.println("screenshot failed: $shot was not written")
        exitProcess(1)
    }
    println("wrote $shot (${opts.width}x${opts.height}, t=${opts.atSeconds}s)")
    exitProcess(0)
}

internal data class Options(
    val screenshot: File? = null,
    val atSeconds: Double = 0.0,
    val width: Int = 1920,
    val height: Int = 1080,
    val help: Boolean = false,
)

internal fun parseArgs(args: Array<String>): Options {
    var opts = Options()
    var i = 0
    fun value(flag: String): String = args.getOrNull(++i) ?: throw IllegalArgumentException("$flag needs a value")
    while (i < args.size) {
        when (val a = args[i]) {
            "--screenshot" -> opts = opts.copy(screenshot = File(value(a)).absoluteFile)
            "--at" -> opts = opts.copy(
                atSeconds = value(a).toDoubleOrNull()?.takeIf { it >= 0 }
                    ?: throw IllegalArgumentException("--at needs a non-negative number"),
            )
            "--size" -> {
                val m = Regex("""(\d+)x(\d+)""").matchEntire(value(a))
                    ?: throw IllegalArgumentException("--size must look like 1920x1080")
                opts = opts.copy(width = m.groupValues[1].toInt(), height = m.groupValues[2].toInt())
            }
            "-h", "--help" -> opts = opts.copy(help = true)
            else -> throw IllegalArgumentException("unknown argument: $a")
        }
        i++
    }
    return opts
}
