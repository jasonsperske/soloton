package dev.soloton.roadwork.env.nightstreets

import dev.soloton.roadwork.Environment

/**
 * A bike ride through an endless city at night: along the bike lane past towers, houses and
 * parks, turning at random at the junctions, with cars driving the streets round the rider.
 */
object NightStreets {
    val environment = Environment(
        id = "night-streets",
        title = "Night Streets",
        musicFolder = "Sim City",
        // Blocks are 100 m: at life size an easy pedal takes half a minute to cross one. Twice that is city cycling.
        speedScale = 2f,
    ) { seed -> NightStreetsScene(seed) }
}
