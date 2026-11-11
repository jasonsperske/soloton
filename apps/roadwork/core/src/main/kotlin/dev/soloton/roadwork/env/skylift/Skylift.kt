package dev.soloton.roadwork.env.skylift

import dev.soloton.roadwork.Environment

/**
 * A desert trail that the bike lifts off from. With the resistance low the bike rides along the
 * ground, swerving round what comes the other way; turning the resistance up lifts it higher among
 * the floating rocks and brings the traffic on thicker and faster.
 */
object Skylift {
    val environment = Environment(
        id = "skylift-mesa",
        title = "Skylift Mesa",
        musicFolder = "Space Harrier II",
    ) { seed -> SkyliftScene(seed) }
}
