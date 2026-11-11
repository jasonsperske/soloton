package dev.soloton.roadwork

import dev.soloton.api.MusicController

/**
 * One ride: its clock, its [RideState] and the [EnvironmentHost] holding the scene. Made new for
 * every ride, so a ride started from the picker begins at zero.
 */
class RideSession(environment: Environment, music: MusicController, initialSpeed: Float, seed: Long) {
    val timestep = FixedTimestep(stepSeconds = 1f / 60f)
    val ride = RideState(initialSpeed)
    val host = EnvironmentHost(environment, music, ride, seed)
}

/**
 * Which screen the app is on, and what happens on the way between them:
 *
 * ```
 *   open() --+--> PICKER --ride(env)--> RIDE --toPicker() / back()--> PICKER --back()--> (exit)
 *            +--> RIDE     (started with an environment: the picker is skipped)
 * ```
 * Starting a ride selects the environment's playlist, builds its scene and starts the music;
 * leaving one stops the music and disposes the scene. Music never plays on the picker. Kept apart
 * from [RoadworkGame] so these rules can be unit tested with fake scenes and no OpenGL; the
 * [Stage] is the part that draws.
 *
 * Render thread only.
 */
class AppFlow(
    private val music: MusicController,
    private val initialSpeed: () -> Float,
    private val seed: Long,
    initial: Environment,
    private val startOnPicker: Boolean,
    private val stage: Stage,
    private val onRideStarted: (Environment) -> Unit = {},
) {
    enum class Screen { PICKER, RIDE }

    /** What the flow asks of the part that owns OpenGL. */
    interface Stage {
        /** The picker is now the screen, with [highlight] (the environment last ridden) marked. */
        fun pickerShown(highlight: Environment)

        /** The picker is going away: free what only it needs (its preview images). */
        fun pickerHidden()

        /** [session]'s scene is built and its music started. */
        fun rideStarted(session: RideSession)

        /** The ride's scene has been disposed. */
        fun rideEnded()
    }

    /** null before [open] and after [dispose]. */
    var screen: Screen? = null
        private set

    /** The environment being ridden; on the picker, the one ridden last (highlighted). */
    var environment: Environment = initial
        private set

    /** The ride in progress, or null on the picker. */
    var session: RideSession? = null
        private set

    private var inspectionView: String? = null
    private var width = 0
    private var height = 0

    /** Shows the picker, or goes straight to a ride in the initial environment. Call once, from `create()`. */
    fun open() {
        check(screen == null) { "already open" }
        if (startOnPicker) showPicker() else beginRide(environment, fromPicker = false)
    }

    /** From the picker: ride [next]. Does nothing during a ride. */
    fun ride(next: Environment) {
        if (screen != Screen.PICKER) return
        stage.pickerHidden()
        beginRide(next, fromPicker = true)
    }

    /** From a ride: stop the music, dispose the scene and show the picker. Does nothing on the picker. */
    fun toPicker() {
        val s = session ?: return
        music.stop()
        session = null
        s.host.dispose()
        stage.rideEnded()
        showPicker()
    }

    /**
     * The back button.
     * @return true if it was used (a ride went back to the picker); false on the picker, where the
     *   caller should exit the app
     */
    fun back(): Boolean {
        if (screen != Screen.RIDE) return false
        toPicker()
        return true
    }

    /**
     * Changes the scenery of the ride in progress, keeping the ride (see [EnvironmentHost.switchTo]).
     * Before [open] it only changes the initial environment.
     * @return false, changing nothing, if [next] is already current or the picker is showing
     */
    fun switchEnvironment(next: Environment): Boolean {
        if (next.id == environment.id) return false
        val s = session
        if (s == null) {
            if (screen != null) return false
            environment = next
            return true
        }
        if (!s.host.switchTo(next)) return false
        environment = next
        onRideStarted(next)
        return true
    }

    /**
     * A development camera for rides (null: the rider's view). Remembered before a ride starts.
     * @return false if the scene being ridden has no such view
     */
    fun setInspectionView(name: String?): Boolean {
        val s = session
        if (s != null && !s.host.setInspectionView(name)) return false
        inspectionView = name
        return true
    }

    fun resize(width: Int, height: Int) {
        this.width = width
        this.height = height
        session?.host?.resize(width, height)
    }

    /** Ends whatever is on screen. Music is left to its owner. */
    fun dispose() {
        val s = session
        session = null
        s?.host?.dispose()
        if (screen == Screen.PICKER) stage.pickerHidden()
        screen = null
    }

    private fun showPicker() {
        screen = Screen.PICKER
        stage.pickerShown(environment)
    }

    private fun beginRide(next: Environment, fromPicker: Boolean) {
        val previous = environment
        environment = next
        screen = Screen.RIDE
        val s = RideSession(next, music, initialSpeed(), seed)
        s.host.setInspectionView(inspectionView)
        if (width > 0 && height > 0) s.host.resize(width, height)
        try {
            s.host.open() // selects the playlist, then builds the scene
        } catch (e: RuntimeException) {
            // No scene: leave the app on the picker rather than on a ride that cannot be drawn.
            if (fromPicker) {
                environment = previous
                showPicker()
            }
            throw e
        }
        session = s
        music.start()
        stage.rideStarted(s)
        onRideStarted(next)
    }
}
