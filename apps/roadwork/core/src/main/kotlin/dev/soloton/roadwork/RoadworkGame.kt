package dev.soloton.roadwork

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.GL20
import dev.soloton.api.MemorySettingsStore
import dev.soloton.api.MusicController
import dev.soloton.api.MusicState
import dev.soloton.api.NoPlatformActions
import dev.soloton.bike.PaceSource
import dev.soloton.api.PlatformActions
import dev.soloton.bike.ResistanceSource
import dev.soloton.api.SettingsStore
import dev.soloton.api.SilentMusicController

/**
 * Roadwork: a first-person bike ride through an [Environment], a 3D scene paired with the rider's
 * choice of music for it. The app opens on a picker listing every environment ([Picker]); choosing
 * one starts a ride, and the pause overlay's "Environments" button or the back button returns to
 * the picker. [AppFlow] holds those rules. During a ride the game owns what every environment
 * shares: time, the ride (pace, distance), the rider's handlebars, the HUD and the music controls.
 * The world itself is the environment's [EnvironmentScene].
 *
 * Contract used by the launchers:
 * - [render] advances simulated time by real frame time in fixed steps (unless paused), then draws.
 * - [simulateTo] advances simulated time to an absolute second deterministically (screenshot mode).
 * - [draw] draws the current state without advancing time.
 *
 * Starting a ride selects the environment's playlist ([MusicController.usePlaylist]) and then calls
 * [MusicController.start]; leaving one calls [MusicController.stop]. Input (touch and keys) goes to
 * the picker, the HUD or the settings screen, whichever is on screen.
 *
 * Each environment has settings ([EnvironmentSettings]): its music, the units shown, the ride's
 * goal and the environment's own options. The gear on an environment's card in the picker and the
 * gear on the HUD both open the same [SettingsScreen] over whatever was showing. A ride is paused
 * while it is open. Changes are saved in [settingsStore] and take effect at once.
 *
 * Draw order per ride frame: the scene; the rider's handlebars (fresh depth, eye-space camera); the HUD.
 *
 * @param initialEnvironment the environment to ride, or with [startOnPicker] the one to outline as last
 *   ridden. Deliberately not called `environment`: inside this class that name must always mean the
 *   property, the environment being ridden now, and a constructor parameter of the same name would
 *   quietly take its place in the listeners below.
 * @param startOnPicker open on the picker instead of going straight to a ride
 * @param onEnvironmentChanged called on the render thread whenever a ride starts or changes
 *   environment, so a launcher can remember the choice
 * @param manualPace the pace the slower/faster buttons adjust, if any. Defaults to [pace] when that is
 *   an [AdjustablePace]. Pass it separately when [pace] wraps it, for example a bike sensor source
 *   that falls back to the buttons.
 * @param manualPaceActive whether [manualPace] is what currently drives the ride. Polled every frame;
 *   the buttons are hidden while it is false (the bike's own cadence is setting the pace).
 * @param resistance how hard the bike is set, passed to scenes through [RideState.resistance]
 * @param manualResistance the resistance the R (raise) and F (lower) keys adjust, if any. Defaults to
 *   [resistance] when that is an [AdjustableResistance]; pass it separately when [resistance] wraps it.
 * @param settingsStore where each environment's settings are kept between runs
 * @param openSettingsAtStart open on [initialEnvironment]'s settings screen (for development screenshots)
 */
class RoadworkGame(
    private val music: MusicController = SilentMusicController(),
    private val pace: PaceSource = AdjustablePace(),
    private val actions: PlatformActions = NoPlatformActions,
    private val seed: Long = DEFAULT_SEED,
    initialEnvironment: Environment = Environments.default,
    startOnPicker: Boolean = false,
    private val onEnvironmentChanged: (Environment) -> Unit = {},
    private val manualPace: AdjustablePace? = pace as? AdjustablePace,
    private val manualPaceActive: () -> Boolean = { true },
    private val resistance: ResistanceSource = AdjustableResistance(),
    private val manualResistance: AdjustableResistance? = resistance as? AdjustableResistance,
    settingsStore: SettingsStore = MemorySettingsStore(),
    private val openSettingsAtStart: Boolean = false,
) : ApplicationAdapter() {
    private val environmentSettings = EnvironmentSettingsStore(settingsStore)

    private val stage = object : AppFlow.Stage {
        override fun pickerShown(highlight: Environment) {
            picker.show(highlight)
            Gdx.input.inputProcessor = picker
        }

        override fun pickerHidden() = picker.hide()

        override fun rideStarted(session: RideSession) {
            hud.setEnvironmentTitle(session.host.environment.title)
            Gdx.input.inputProcessor = hud
        }

        override fun rideEnded() {}
    }

    private val flow = AppFlow(
        music = music,
        initialSpeed = { pace.metersPerSecond },
        seed = seed,
        initial = initialEnvironment,
        startOnPicker = startOnPicker,
        stage = stage,
        onRideStarted = { next ->
            this.environment = next
            if (created) hud.setSettings(environmentSettings.load(next))
            onEnvironmentChanged(next)
        },
        options = { environmentSettings.load(it).options },
        worldScale = { environmentSettings.load(it).speedScaleFor(it) },
    )

    /** The environment being ridden (on the picker: the one ridden last). Safe to read from any thread. */
    @Volatile
    var environment: Environment = initialEnvironment
        private set

    /** Which screen is showing; null before [create]. Safe to read from any thread. */
    @Volatile
    var screen: AppFlow.Screen? = null
        private set

    /** True while the rider has paused the session: time, distance and the scene stand still. */
    var paused = false
        private set

    val simulatedSeconds: Double get() = flow.session?.timestep?.simulatedSeconds ?: 0.0
    val elapsedSeconds: Double get() = flow.session?.ride?.elapsedSeconds ?: 0.0
    val distanceMeters: Double get() = flow.session?.ride?.distanceMeters ?: 0.0

    private val riderBike = RiderBike()
    private lateinit var hud: Hud
    private lateinit var picker: Picker
    private lateinit var settingsScreen: SettingsScreen
    private var created = false

    /** Whether the ride was already paused when the settings were opened over it, so closing them leaves it as it was. */
    private var pausedBeforeSettings = false

    /** The environment whose settings were last asked for, by either gear; null until one is. */
    var settingsRequestedFor: Environment? = null
        private set

    /** True while an environment's settings screen is showing. Safe to read from any thread. */
    @Volatile
    var settingsOpen = false
        private set

    private val hudListener = object : Hud.Listener {
        override fun onSlower() { manualPace?.slower() }
        override fun onFaster() { manualPace?.faster() }
        // With nothing to skip, the button tries again (start() retries after a problem), so music
        // comes back once the folder has been copied over, without leaving the ride.
        override fun onSkip() { if (music.state is MusicState.Problem) music.start() else music.skip() }
        override fun onTogglePause() { setPaused(!paused) }
        // The environment being ridden now, which is not necessarily the one the app opened with.
        override fun onSettings() { showSettings(environment) }
        override fun onEnvironments() { showPicker() }
        override fun onResistanceUp() { manualResistance?.raise() }
        override fun onResistanceDown() { manualResistance?.lower() }
    }

    private val pickerListener = object : Picker.Listener {
        override fun onRide(environment: Environment) { ride(environment) }
        override fun onSettings(environment: Environment) { showSettings(environment) }
        override fun onExit() { Gdx.app.exit() }
    }

    private val settingsListener = object : SettingsScreen.Listener {
        override fun onChanged(environment: Environment, settings: EnvironmentSettings) {
            environmentSettings.save(environment, settings)
            // If this is the environment being ridden, the ride takes the change now.
            val session = flow.session
            if (session != null && session.host.environment.id == environment.id) {
                hud.setSettings(settings)
                session.ride.worldScale = settings.speedScaleFor(environment)
                session.host.refreshOptions()
            }
        }

        override fun onMusic(environment: Environment) { actions.openMusicSettings(environment.playlist) }
        override fun onDone() { closeSettings() }
    }

    override fun create() {
        riderBike.create()
        hud = Hud(hudListener)
        hud.create()
        hud.setPaceAdjustable(manualPace != null && manualPaceActive())
        picker = Picker(Environments.all, music, seed, pickerListener)
        picker.create()
        settingsScreen = SettingsScreen(music, settingsListener)
        settingsScreen.create()
        // Back comes to the input processor instead of closing the app: the ride returns to the picker.
        Gdx.input.setCatchKey(Input.Keys.BACK, true)

        created = true
        flow.resize(Gdx.graphics.width, Gdx.graphics.height)
        flow.open()
        screen = flow.screen
        resize(Gdx.graphics.width, Gdx.graphics.height)
        if (openSettingsAtStart) showSettings(environment)
    }

    /**
     * From the picker, starts a ride in [next]: its playlist is selected, its scene built and music
     * started, from zero elapsed time. During a ride it does nothing (see [switchEnvironment]).
     * Render thread only.
     */
    fun ride(next: Environment) {
        if (!created) return
        closeSettings()
        paused = false
        flow.ride(next)
        screen = flow.screen
    }

    /** Leaves the ride for the picker: music stops and the scene is disposed. Render thread only. */
    fun showPicker() {
        if (!created || flow.screen != AppFlow.Screen.RIDE) return
        closeSettings()
        paused = false
        flow.toPicker()
        screen = flow.screen
    }

    /**
     * Opens [of]'s settings over the picker or the ride, whichever is showing. A ride is paused
     * until they are closed. Render thread only.
     */
    fun showSettings(of: Environment) {
        settingsRequestedFor = of
        if (!created) return
        if (!settingsOpen) {
            pausedBeforeSettings = paused
            setPaused(true)
        }
        settingsScreen.show(of, environmentSettings.load(of))
        settingsOpen = true
        Gdx.input.inputProcessor = settingsScreen
    }

    /** What the gear on the HUD does during a ride: opens the settings of the environment being ridden. */
    internal fun pressSettingsGear() = hudListener.onSettings()

    /** Closes the settings, back to the picker or to the ride as it was. Does nothing if they are not open. */
    fun closeSettings() {
        if (!settingsOpen) return
        settingsScreen.hide()
        settingsOpen = false
        if (flow.session != null) {
            Gdx.input.inputProcessor = hud
            setPaused(pausedBeforeSettings)
        } else {
            Gdx.input.inputProcessor = picker
        }
    }

    /** The settings of [of] as saved: its units, goal and options. */
    fun settingsOf(of: Environment): EnvironmentSettings = environmentSettings.load(of)

    /**
     * Goes to a ride in the environment with this [id] from wherever the app is (a launch request
     * arriving while it is already running). Render thread only.
     * @return false if the id is unknown
     */
    fun rideEnvironment(id: String): Boolean {
        val next = Environments.find(id) ?: return false
        if (!created) return flow.switchEnvironment(next) || next.id == environment.id
        if (flow.screen == AppFlow.Screen.RIDE) {
            if (next.id == environment.id) return true
            showPicker()
        }
        ride(next)
        return true
    }

    /**
     * Changes the scenery of the ride in progress to the environment with this [id]: the old scene
     * is disposed, the new one is built, and music switches to the new environment's playlist. The
     * ride carries on: elapsed time, distance, pace and the paused state are kept, and the new scene
     * starts from them.
     *
     * Call on the render thread (from another thread, through `Gdx.app.postRunnable`). Before
     * [create] it only chooses which environment [create] starts with.
     * @return false, changing nothing, if the id is unknown or already current, or the picker is showing
     */
    fun switchEnvironment(id: String): Boolean {
        val next = Environments.find(id) ?: return false
        if (!flow.switchEnvironment(next)) return false
        environment = next
        if (created) hud.setEnvironmentTitle(next.title)
        return true
    }

    /** Names of the current scene's development cameras; see [setInspectionView]. */
    val inspectionViews: List<String> get() = flow.session?.host?.scene?.inspectionViews ?: emptyList()

    /**
     * Looks at the scene through one of its development cameras instead of the rider's eyes
     * (null: back to the rider). May be called before [create]; an unknown name then falls back
     * to the rider's view when the scene is built.
     * @return false if the current scene has no such view
     */
    fun setInspectionView(name: String?): Boolean = flow.setInspectionView(name)

    override fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || !created) return
        flow.resize(width, height)
        riderBike.resize(width, height)
        hud.resize(width, height)
        picker.resize(width, height)
        settingsScreen.resize(width, height)
    }

    override fun render() {
        val session = flow.session
        if (session != null && !paused) session.timestep.advance(Gdx.graphics.deltaTime) { update(session, it) }
        // Here, not in draw(): previews are drawn into their own framebuffers, which cannot nest.
        if (session == null) picker.renderPreviews()
        draw()
    }

    fun simulateTo(seconds: Double) {
        val session = flow.session ?: return
        session.timestep.advanceTo(seconds) { update(session, it) }
    }

    fun setPaused(value: Boolean) {
        if (value == paused || flow.session == null) return
        paused = value
        if (paused) music.pause() else music.resume()
    }

    private fun update(session: RideSession, dt: Float) {
        session.ride.step(dt, pace.metersPerSecond, resistance.fraction)
        session.host.step(dt)
    }

    fun draw() {
        if (settingsOpen) {
            settingsScreen.draw()
            return
        }
        val session = flow.session
        if (session == null) {
            if (picker.shown) picker.draw()
            return
        }
        val scene = session.host.scene ?: return
        val ride = session.ride
        scene.draw(ride)
        if (scene.riderView) riderBike.draw(ride, scene.light)

        val gl = Gdx.gl
        gl.glDepthMask(true)
        gl.glDisable(GL20.GL_DEPTH_TEST)
        gl.glDisable(GL20.GL_CULL_FACE)

        hud.setPaceAdjustable(manualPace != null && manualPaceActive())
        hud.draw(ride.elapsedSeconds, ride.distanceMeters, ride.speed, music.state, paused)
    }

    /** The app is back in the foreground. If the GL context was lost meanwhile, the previews went with it. */
    override fun resume() {
        if (created) picker.invalidatePreviews()
    }

    override fun dispose() {
        flow.dispose()
        if (::settingsScreen.isInitialized) settingsScreen.dispose()
        if (::picker.isInitialized) picker.dispose()
        if (::hud.isInitialized) hud.dispose()
        riderBike.dispose()
    }

    companion object {
        const val DEFAULT_SEED = 0x50_1070_2024L
    }
}
