package dev.soloton.roadwork.env.skylift

import dev.soloton.roadwork.Environment

/**
 * A desert trail followed from behind a space runner. With the resistance low the runner runs
 * along the ground, swerving round what comes the other way; turning the resistance up lifts them
 * into flight, higher among the floating rocks, and brings the traffic on thicker and faster.
 */
object Skylift {
    val environment = Environment(
        id = "skylift-mesa",
        title = "Skylift Mesa",
        musicFolder = "Space Harrier II",
    ) { seed -> SkyliftScene(seed) }
}
