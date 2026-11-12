package dev.soloton.roadwork

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector3
import dev.soloton.api.PlaylistRef

/**
 * A place to ride: a 3D scene paired with the folder of music that plays in it.
 *
 * Environments are listed in [Environments]. Each one lives in its own package under
 * `dev.soloton.roadwork.env` (see docs/ENVIRONMENTS.md).
 *
 * @param id stable kebab-case identifier; saved in settings and used on the command line, so never rename it
 * @param title name shown to the rider
 * @param musicFolder the sub-folder of the music root whose songs play here until the rider chooses
 *   other folders or songs in music settings (a folder name, taken literally), or null to start
 *   with everything under the root
 * @param speedScale how many metres of this world go by for each metre the bike covers, until the
 *   rider changes it in the environment's settings. 1 is life size. An open place where there is
 *   little near the rider to judge speed by wants more than a narrow one
 * @param options what the rider can choose about this environment in its settings, beyond the
 *   units and goal every environment has; the chosen values reach the scene through
 *   [EnvironmentScene.applyOptions]
 * @param sceneFactory builds a new, not yet created scene from a seed
 */
class Environment(
    val id: String,
    val title: String,
    val musicFolder: String?,
    val options: List<EnvironmentOption> = emptyList(),
    val speedScale: Float = 1f,
    private val sceneFactory: (seed: Long) -> EnvironmentScene,
) {
    init {
        require(isValidId(id)) { "environment id must be kebab-case (a-z, 0-9, single dashes): '$id'" }
        require(title.isNotBlank()) { "environment $id needs a title" }
        require(musicFolder == null || musicFolder.isNotBlank()) { "environment $id: musicFolder must be null or a folder name" }
        require(options.map { it.key }.toSet().size == options.size) { "environment $id has two options with the same key" }
        require(speedScale in RideState.MIN_WORLD_SCALE..RideState.MAX_WORLD_SCALE) { "environment $id: speedScale $speedScale is out of range" }
    }

    /** This environment's music: the rider's selection is saved under [id], starting from [musicFolder]. */
    val playlist: PlaylistRef = PlaylistRef(id, title, musicFolder)

    /** A new scene. Nothing touches OpenGL until [EnvironmentScene.create]. */
    fun createScene(seed: Long): EnvironmentScene = sceneFactory(seed)

    override fun toString(): String = id

    companion object {
        private val ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")

        fun isValidId(id: String): Boolean = ID.matches(id)
    }
}

/**
 * The world of one [Environment], as [RoadworkGame] drives it. The game owns time, the ride
 * ([RideState]: elapsed time, distance, speed, wheel and crank angles), the HUD and the rider's
 * own bike; the scene owns everything else that is drawn.
 *
 * Calls arrive on the render thread, in this order: [create] once; then any number of [resize],
 * [step] and [draw]; then [dispose] once. Time only moves in [step], which the game calls in
 * fixed 1/60 s steps, so [draw] must depend on nothing but the ride and what [step] has
 * accumulated: that is what makes screenshots at a given second reproducible.
 */
interface EnvironmentScene {
    /**
     * Compiles shaders and builds meshes. [ride] is where the scene starts from; it is at zero for a
     * new session and further along after [RoadworkGame.switchEnvironment].
     */
    fun create(ride: RideState)

    fun resize(width: Int, height: Int)

    /** One fixed simulation step of [dt] seconds. [ride] has already been advanced by it. */
    fun step(dt: Float, ride: RideState)

    /**
     * Clears the frame and draws the world for the current [ride], without advancing time. The
     * game then draws the rider's bike (if [riderView]) and the HUD on top.
     */
    fun draw(ride: RideState)

    /** The light the rider's bike is lit with, so it matches the world. Read after [draw]. */
    val light: SceneLight

    /** Names of extra development cameras (kebab-case), for `--view` on the desktop launcher. */
    val inspectionViews: List<String> get() = emptyList()

    /**
     * Selects one of [inspectionViews], or the rider's own view with null.
     * @return false, changing nothing, if this scene has no view with that name
     */
    fun setInspectionView(name: String?): Boolean = name == null

    /**
     * The rider's choices for this environment's [Environment.options]: the chosen choice id for
     * each option key. Called before [create] with the saved settings, and again whenever the
     * rider changes them during a ride. A scene without options ignores it; one that cannot apply
     * a change at once (a craft already in the air) applies it when it next can.
     */
    fun applyOptions(options: Map<String, String>) {}

    /** True when the camera is the rider's eyes, so the game should draw the rider's bike. */
    val riderView: Boolean get() = true

    /** Frees everything [create] made. Safe to call if [create] failed part way. */
    fun dispose()
}

/** Sun and ambient light of a scene. Implementations update the values in place. */
interface SceneLight {
    /** Unit vector pointing toward the sun. */
    val sunDirection: Vector3
    val sunColor: Color
    val ambientSky: Color
    val ambientGround: Color
}
