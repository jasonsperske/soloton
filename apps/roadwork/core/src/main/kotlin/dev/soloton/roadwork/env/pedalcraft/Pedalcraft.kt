package dev.soloton.roadwork.env.pedalcraft

import dev.soloton.roadwork.Environment
import dev.soloton.roadwork.EnvironmentOption

/**
 * Pedal-powered flight round an island: a glider, a paramotor and a light plane in turn, each
 * flown from one landing spot to the next, about twenty minutes a leg. Pedalling turns the
 * propeller; the bike's resistance adds height. In its settings the rider can have a random
 * craft each leg, or always the same one.
 */
object Pedalcraft {
    /** The key of the option that chooses the aircraft; its choices are the [Fleet]s. */
    const val CRAFT = "craft"

    val environment = Environment(
        id = "pedalcraft-island",
        title = "Pedalcraft Island",
        musicFolder = "Pilotwings",
        // Each craft already has its own gearing (5, 3 or 7 metres flown per metre ridden), sized so a
        // leg takes 20 to 24 minutes. Scaling the world on top would shorten the legs by the same factor.
        speedScale = 1f,
        options = listOf(
            EnvironmentOption(
                key = CRAFT,
                title = "Aircraft",
                choices = Fleet.entries.map { EnvironmentOption.Choice(it.id, it.title) },
                note = "During a flight, a change starts with the next leg",
            ),
        ),
    ) { seed -> PedalcraftScene(seed) }
}
