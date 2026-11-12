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
        // A narrow path with trees, posts and a jogger close by reads as fast already; a little over life size.
        speedScale = 1.5f,
    ) { seed -> RiverfrontScene(seed) }
}
