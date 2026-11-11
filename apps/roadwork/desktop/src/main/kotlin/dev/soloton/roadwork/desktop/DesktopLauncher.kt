package dev.soloton.roadwork.desktop

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import dev.soloton.api.SilentMusicController
import dev.soloton.roadwork.AdjustableResistance
import dev.soloton.roadwork.Environment
import dev.soloton.roadwork.Environments
import dev.soloton.roadwork.RoadworkGame
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Usage: roadwork-desktop [--screenshot <out.png> [--at <seconds>] [--size <W>x<H>]]
                        [--stats [--duration <seconds>]] [--silent-music]
                        [--env <id>] [--picker] [--list-envs] [--view rider|<name>]
                        [--resistance <0-100>]

Without arguments, opens a 1280x720 window on the environment picker.
Picker keys: left/right choose, Enter ride, Escape quit.
Ride keys: space pause, left/right pace, R/F raise or lower resistance, N skip, S settings,
           Escape or E back to the picker.
--screenshot    render one frame offscreen and write it as PNG, then exit: a ride (in --env, or the
                default environment), or with --picker the picker
--at            simulated seconds to advance to (fixed 1/60 s steps), default 0
--size          image size, default 1920x1080 (the bike's display)
--stats         open a window (vsync off) and log frame time, draw calls and per-frame allocation
--duration      with --stats, exit after this many seconds (default: run until closed)
--silent-music  use the no-op music controller instead of the demo track list
--env           skip the picker and ride this environment
--picker        start on the picker even with --screenshot or --stats; --env then only says which
                environment is outlined as the one ridden last
--list-envs     print each environment's id, title and music folder, then exit
--view          camera: rider (default, from the saddle) or one of the environment's development
                cameras (riverfront: jogger-side, jogger-front, jogger-back)
--resistance    the bike's resistance in percent to start with, default 0 (environments that use
                it, such as skylift-mesa, fly higher and get busier as it rises)

Music is a silent demo. Set SOLOTON_MUSIC_DIR to a folder holding one sub-folder per game (the
layout of the tablet's music root) to see real song names and counts; see .env.example."""

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
    if (opts.listEnvs) {
        print(describeEnvironments(Environments.all, Environments.default))
        return
    }
    val environment = Environments.find(opts.env ?: Environments.default.id) ?: run {
        System.err.println("error: no environment '${opts.env}'. Known: ${Environments.all.joinToString(", ") { it.id }}")
        exitProcess(2)
    }
    // A scene touches no OpenGL until it is created, so one can be asked for its camera names here.
    val views = environment.createScene(RoadworkGame.DEFAULT_SEED).inspectionViews
    if (opts.view != null && opts.view !in views) {
        System.err.println("error: --view must be ${(listOf("rider") + views).joinToString(", ")} for ${environment.id}")
        exitProcess(2)
    }

    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Roadwork")
        // Match the device: OpenGL ES 2.0 feature level.
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL20, 2, 0)
    }

    val music = if (opts.silentMusic) SilentMusicController("(desktop)") else DemoMusicController(DemoMusicController.musicDirFromEnvironment())
    val game = RoadworkGame(
        music = music,
        environment = environment,
        startOnPicker = opts.startsOnPicker,
        resistance = AdjustableResistance(opts.resistancePercent / 100f),
    )
    game.setInspectionView(opts.view)
    val shot = opts.screenshot
    if (shot == null) {
        if (opts.stats) {
            config.setWindowedMode(1920, 1080)
            config.useVsync(false)
            config.setForegroundFPS(0)
            Lwjgl3Application(StatsRunner(game, opts.durationSeconds), config)
            exitProcess(0)
        }
        config.setWindowedMode(1280, 720)
        config.useVsync(true)
        config.setForegroundFPS(60)
        Lwjgl3Application(game, config)
        return
    }

    config.setWindowedMode(640, 360)
    config.setResizable(false)
    val runner = ScreenshotRunner(game, shot, opts.atSeconds, opts.width, opts.height)
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
    println("wrote $shot (${opts.width}x${opts.height}, ${if (opts.startsOnPicker) "picker" else "t=${opts.atSeconds}s"})")
    exitProcess(0)
}

internal data class Options(
    val screenshot: File? = null,
    val atSeconds: Double = 0.0,
    val width: Int = 1920,
    val height: Int = 1080,
    val help: Boolean = false,
    val stats: Boolean = false,
    val durationSeconds: Float = 0f,
    val silentMusic: Boolean = false,
    /** Environment id, or null for the default. */
    val env: String? = null,
    val listEnvs: Boolean = false,
    /** One of the environment's development cameras, or null for the rider's view. */
    val view: String? = null,
    /** Start on the picker even where a ride is the default (screenshots, stats). */
    val picker: Boolean = false,
    /** The resistance the ride starts with, 0 to 100. */
    val resistancePercent: Float = 0f,
) {
    /** The window opens on the picker; `--env`, `--screenshot` and `--stats` go straight to a ride unless `--picker` is given. */
    val startsOnPicker: Boolean get() = picker || (env == null && screenshot == null && !stats)
}

/** One line per environment: id, title and music folder, with the default marked. */
internal fun describeEnvironments(all: List<Environment>, default: Environment): String = buildString {
    val idWidth = all.maxOf { it.id.length }
    val titleWidth = all.maxOf { it.title.length }
    for (e in all) {
        append(e.id.padEnd(idWidth)).append("  ").append(e.title.padEnd(titleWidth)).append("  ")
        append(e.musicFolder?.let { "music: $it" } ?: "music: (whole music root)")
        if (e === default) append("  [default]")
        append('\n')
    }
}

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
            "--stats" -> opts = opts.copy(stats = true)
            "--duration" -> opts = opts.copy(
                durationSeconds = value(a).toFloatOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("--duration needs a positive number"),
            )
            "--silent-music" -> opts = opts.copy(silentMusic = true)
            "--env" -> opts = opts.copy(env = value(a))
            "--picker" -> opts = opts.copy(picker = true)
            "--resistance" -> opts = opts.copy(
                resistancePercent = value(a).toFloatOrNull()?.takeIf { it in 0f..100f }
                    ?: throw IllegalArgumentException("--resistance needs a number from 0 to 100"),
            )
            "--list-envs" -> opts = opts.copy(listEnvs = true)
            "--view" -> opts = opts.copy(view = value(a).takeIf { it != "rider" })
            "-h", "--help" -> opts = opts.copy(help = true)
            else -> throw IllegalArgumentException("unknown argument: $a")
        }
        i++
    }
    return opts
}
