package dev.soloton.roadwork.env.nightstreets

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector3
import dev.soloton.roadwork.SceneLight

/**
 * Night over the city: a deep indigo sky with the last of the dusk along the horizon, and a moon.
 *
 * There are two lights here. The **city** is drawn with [cityAmbientSky], [cityAmbientGround] and
 * [moonColor]: an even, bright light under which colours show much as they were written, so a lit
 * window is bright and a wall is dark because their colours say so, with only a little shading
 * from the moon to tell one face of a building from the next. The **rider's bike**, drawn by the
 * game with this object as its [SceneLight], gets an ordinary dim, cool light, so the handlebars
 * look like a bike at night under street lamps.
 */
class NightSky : SceneLight {
    val zenith = Color(0.02f, 0.03f, 0.10f, 1f)
    val horizon = Color(0.26f, 0.16f, 0.30f, 1f)

    /** The moon's disc and glow in the sky. */
    val moonGlow = Color(0.55f, 0.60f, 0.78f, 1f)

    val cityAmbientSky = Color(0.80f, 0.82f, 0.95f, 1f)
    val cityAmbientGround = Color(0.62f, 0.58f, 0.66f, 1f)
    val moonColor = Color(0.22f, 0.25f, 0.36f, 1f)

    /** Unit vector pointing toward the moon (x east, y up, z south). */
    override val sunDirection: Vector3 = Vector3(0.45f, 0.62f, -0.64f).nor()
    override val sunColor = Color(0.42f, 0.45f, 0.60f, 1f)
    override val ambientSky = Color(0.30f, 0.33f, 0.46f, 1f)
    override val ambientGround = Color(0.26f, 0.22f, 0.20f, 1f)
}
