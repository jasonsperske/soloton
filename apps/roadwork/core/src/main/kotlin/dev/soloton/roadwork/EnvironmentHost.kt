package dev.soloton.roadwork

import dev.soloton.api.MusicController

/**
 * Holds the current [Environment] and its live scene, and swaps them. Keeping this apart from
 * [RoadworkGame] means the rules (which playlist, what gets disposed, in what order) can be unit
 * tested with fake scenes and no OpenGL.
 *
 * The [ride] is shared with every scene and is not reset by a switch: elapsed time, distance and
 * speed belong to the workout, not to the scenery.
 *
 * Render thread only.
 */
class EnvironmentHost(
    initial: Environment,
    private val music: MusicController,
    private val ride: RideState,
    private val seed: Long,
) {
    var environment: Environment = initial
        private set

    /** The live scene; null before [open], after [dispose], and if building a scene failed. */
    var scene: EnvironmentScene? = null
        private set

    /** The scene's development camera, or null for the rider's view. Kept across a switch if the new scene has it. */
    var inspectionView: String? = null
        private set

    private var opened = false
    private var width = 0
    private var height = 0

    /** Selects the playlist and builds the scene of the current environment. Call once, from `create()`. */
    fun open() {
        check(!opened) { "already open" }
        opened = true
        music.usePlaylist(environment.playlist)
        scene = build(environment)
    }

    /**
     * Makes [next] the current environment: switches the playlist, disposes the old scene and builds
     * the new one from the ride as it stands. Before [open] it only changes which environment
     * [open] will build.
     * @return false, doing nothing, if [next] is already current
     */
    fun switchTo(next: Environment): Boolean {
        if (next.id == environment.id) return false
        environment = next
        music.usePlaylist(next.playlist)
        if (opened) {
            // Old scene out before the new one is built: only one set of meshes in memory at a time.
            val old = scene
            scene = null
            old?.dispose()
            scene = build(next)
        }
        return true
    }

    /** @return false if the current scene has no such view (the view is then left as it was) */
    fun setInspectionView(name: String?): Boolean {
        val s = scene
        if (s != null && !s.setInspectionView(name)) return false
        inspectionView = name
        return true
    }

    fun resize(width: Int, height: Int) {
        this.width = width
        this.height = height
        scene?.resize(width, height)
    }

    fun step(dt: Float) {
        scene?.step(dt, ride)
    }

    fun dispose() {
        val old = scene
        scene = null
        old?.dispose()
    }

    private fun build(environment: Environment): EnvironmentScene {
        val s = environment.createScene(seed)
        try {
            s.create(ride)
            if (width > 0 && height > 0) s.resize(width, height)
            if (!s.setInspectionView(inspectionView)) inspectionView = null
        } catch (e: RuntimeException) {
            s.dispose()
            throw e
        }
        return s
    }
}
