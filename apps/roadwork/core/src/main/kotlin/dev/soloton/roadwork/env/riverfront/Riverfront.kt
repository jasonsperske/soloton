package dev.soloton.roadwork.env.riverfront

import dev.soloton.roadwork.Environment

/**
 * The first environment: a bike ride along a sunny city riverfront, pacing a jogger who is out on
 * their roadwork a few metres ahead.
 */
object Riverfront {
    val environment = Environment(
        id = "riverfront",
        title = "Riverfront Roadwork",
        musicFolder = "Mike Tyson's Punch-Out!!",
    ) { seed -> RiverfrontScene(seed) }
}
