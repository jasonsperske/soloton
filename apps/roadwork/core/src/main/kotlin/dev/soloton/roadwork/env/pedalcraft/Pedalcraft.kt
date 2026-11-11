package dev.soloton.roadwork.env.pedalcraft

import dev.soloton.roadwork.Environment

/**
 * Pedal-powered flight round an island: a glider, a paramotor and a light plane in turn, each
 * flown from one landing spot to the next, about twenty minutes a leg. Pedalling turns the
 * propeller; the bike's resistance adds height.
 */
object Pedalcraft {
    val environment = Environment(
        id = "pedalcraft-island",
        title = "Pedalcraft Island",
        musicFolder = "Pilotwings",
    ) { seed -> PedalcraftScene(seed) }
}
