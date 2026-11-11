package dev.soloton.roadwork.env.pedalcraft

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector3
import dev.soloton.roadwork.SceneLight

/**
 * The island's light: a clear midday, deep blue overhead and pale at the horizon, with the sun
 * high in the south-west. The sun is fixed in the sky, so as the craft circles the island the
 * light comes round from every side. It does not change during a ride.
 */
class IslandSky : SceneLight {
    val zenith = Color(0.16f, 0.46f, 0.88f, 1f)
    val horizon = Color(0.78f, 0.89f, 0.97f, 1f)
    override val sunColor = Color(1.00f, 0.97f, 0.90f, 1f)
    override val ambientSky = Color(0.50f, 0.58f, 0.70f, 1f)
    override val ambientGround = Color(0.36f, 0.36f, 0.32f, 1f)

    /** Unit vector pointing toward the sun (x east, y up, z south). */
    override val sunDirection: Vector3 = Vector3(-0.42f, 0.78f, 0.46f).nor()
}
