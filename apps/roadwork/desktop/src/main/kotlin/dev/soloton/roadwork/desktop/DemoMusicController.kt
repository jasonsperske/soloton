package dev.soloton.roadwork.desktop

import dev.soloton.api.MusicController
import dev.soloton.api.MusicState
import dev.soloton.api.Track

/**
 * Desktop stand-in for the music player: plays nothing, but cycles through a few made-up track
 * names so the HUD's now-playing line, skip and pause can be seen and screenshotted.
 */
class DemoMusicController : MusicController {
    private val tracks = listOf(
        Track("Harbour Lights", "The Early Risers", "Morning Tides", "/demo/01.ogg"),
        Track("Second Wind", "Lantern Street", "Long Way Round", "/demo/02.ogg"),
        Track("Tidewater Run", "Gulls and Gears", null, "/demo/03.ogg"),
    )
    private var index = 0

    @Volatile
    override var state: MusicState = MusicState.Idle
        private set

    override fun start() { state = MusicState.Playing(tracks[index]) }

    override fun skip() {
        index = (index + 1) % tracks.size
        state = when (state) {
            is MusicState.Paused -> MusicState.Paused(tracks[index])
            else -> MusicState.Playing(tracks[index])
        }
    }

    override fun pause() {
        (state as? MusicState.Playing)?.let { state = MusicState.Paused(it.track) }
    }

    override fun resume() {
        (state as? MusicState.Paused)?.let { state = MusicState.Playing(it.track) }
    }
}
