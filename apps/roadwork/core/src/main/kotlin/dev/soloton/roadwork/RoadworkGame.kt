package dev.soloton.roadwork

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Vector3
import dev.soloton.api.MusicController
import dev.soloton.api.NoPlatformActions
import dev.soloton.api.PaceSource
import dev.soloton.api.PlatformActions
import dev.soloton.api.SilentMusicController
import kotlin.math.cos
import kotlin.math.sin

/**
 * Roadwork: a first-person bike ride along a sunny city riverfront, pacing a jogger who is out on
 * their roadwork a few metres ahead.
 *
 * Contract used by the launchers:
 * - [render] advances simulated time by real frame time in fixed steps (unless paused), then draws.
 * - [simulateTo] advances simulated time to an absolute second deterministically (screenshot mode).
 * - [draw] draws the current state without advancing time.
 *
 * [create] calls [MusicController.start]. Input (touch and keys) is handled by the HUD, which is
 * installed as `Gdx.input.inputProcessor` in [create].
 *
 * Draw order per frame: sky; flat far planes (no depth); the far-bank skyline, distant treeline
 * and boats (depth, from a camera whose near plane is 100 m); near chunks, water and the jogger
 * (fresh depth); the rider's handlebars (fresh depth, eye-space camera); the HUD.
 */
class RoadworkGame(
    private val music: MusicController = SilentMusicController(),
    private val pace: PaceSource = AdjustablePace(),
    private val actions: PlatformActions = NoPlatformActions,
    private val seed: Long = DEFAULT_SEED,
) : ApplicationAdapter() {
    private val timestep = FixedTimestep(stepSeconds = 1f / 60f)
    private val ride = RideState(pace.metersPerSecond)
    private val jogger = JoggerMotion(seed)
    private val sky = DaySky()
    private val track = ChunkTrack(Scenery.CHUNK_LENGTH, CHUNK_SLOTS)
    private val rng = Rng(seed)

    init {
        // Start the jogger already running at the rider's pace (a zero-length step sets its speed).
        jogger.step(0f, 0.0, ride.speed)
    }

    /** True while the rider has paused the session: time, distance and the scene stand still. */
    var paused = false
        private set

    /**
     * Inspection camera for development screenshots: [View.RIDER] is the normal first-person view
     * from the saddle; the others look at the jogger up close so the run cycle can be checked.
     */
    enum class View { RIDER, JOGGER_SIDE, JOGGER_FRONT, JOGGER_BACK }
    var view = View.RIDER

    val simulatedSeconds: Double get() = timestep.simulatedSeconds
    val elapsedSeconds: Double get() = ride.elapsedSeconds
    val distanceMeters: Double get() = ride.distanceMeters

    private lateinit var camera: PerspectiveCamera
    private lateinit var farCamera: PerspectiveCamera
    private lateinit var skylineCamera: PerspectiveCamera
    private lateinit var overlayCamera: PerspectiveCamera
    private lateinit var sceneShader: ShaderProgram
    private lateinit var skyShader: ShaderProgram
    private lateinit var skyMesh: Mesh
    private lateinit var farMesh: Mesh
    private lateinit var skylineMeshes: Array<Mesh>
    private lateinit var skylineSimpleMeshes: Array<Mesh>
    private lateinit var cargoMesh: Mesh
    private lateinit var ferryMesh: Mesh
    private lateinit var waterMesh: Mesh
    private lateinit var joggerMesh: Mesh
    private lateinit var overlayMesh: Mesh
    private lateinit var chunkMeshes: Array<Mesh>
    private val chunkVertices = IntArray(CHUNK_SLOTS)
    private var farVertices = 0
    private val skylineVertices = IntArray(Skyline.SECTIONS)
    private val skylineSimpleVertices = IntArray(Skyline.SECTIONS)
    private var cargoVertices = 0
    private var ferryVertices = 0
    private var waterVertices = 0
    private lateinit var chunkWriter: MeshWriter
    private lateinit var joggerWriter: MeshWriter
    private lateinit var overlayWriter: MeshWriter
    private val joggerModel = JoggerModel()
    private val bikeOverlay = BikeOverlay()
    private lateinit var hud: Hud

    private var viewWidth = 1
    private var viewHeight = 1
    private val tmp = Vector3()

    private val hudListener = object : Hud.Listener {
        override fun onSlower() { (pace as? AdjustablePace)?.slower() }
        override fun onFaster() { (pace as? AdjustablePace)?.faster() }
        override fun onSkip() { music.skip() }
        override fun onTogglePause() { setPaused(!paused) }
        override fun onSettings() { actions.openMusicSettings() }
    }

    override fun create() {
        sceneShader = Shaders.compile(Shaders.SCENE_VERTEX, Shaders.SCENE_FRAGMENT)
        skyShader = Shaders.compile(Shaders.SKY_VERTEX, Shaders.SKY_FRAGMENT)

        skyMesh = Mesh(true, 4, 0, VertexAttribute(Usage.Position, 2, "a_position"))
        skyMesh.setVertices(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f))

        chunkWriter = MeshWriter(Skyline.MAX_VERTICES)
        Scenery.buildFarPlanes(chunkWriter)
        farVertices = chunkWriter.vertexCount
        farMesh = staticMesh(chunkWriter)
        skylineMeshes = Array(Skyline.SECTIONS) { s ->
            Skyline.buildDetailed(chunkWriter, seed, rng, s)
            skylineVertices[s] = chunkWriter.vertexCount
            staticMesh(chunkWriter)
        }
        skylineSimpleMeshes = Array(Skyline.SECTIONS) { s ->
            Skyline.buildSimple(chunkWriter, seed, rng, s)
            skylineSimpleVertices[s] = chunkWriter.vertexCount
            staticMesh(chunkWriter)
        }
        Skyline.buildCargoBoat(chunkWriter)
        cargoVertices = chunkWriter.vertexCount
        cargoMesh = staticMesh(chunkWriter)
        Skyline.buildFerry(chunkWriter)
        ferryVertices = chunkWriter.vertexCount
        ferryMesh = staticMesh(chunkWriter)
        Scenery.buildNearWater(chunkWriter, seed)
        waterVertices = chunkWriter.vertexCount
        waterMesh = staticMesh(chunkWriter)

        chunkMeshes = Array(CHUNK_SLOTS) { sceneMesh(false, Scenery.CHUNK_MAX_VERTICES) }
        for (slot in 0 until CHUNK_SLOTS) buildChunk(slot, track.indices[slot])

        joggerWriter = MeshWriter(JoggerModel.MAX_VERTICES)
        joggerMesh = sceneMesh(false, JoggerModel.MAX_VERTICES)
        overlayWriter = MeshWriter(BikeOverlay.MAX_VERTICES)
        overlayMesh = sceneMesh(false, BikeOverlay.MAX_VERTICES)

        camera = PerspectiveCamera(FOV, 16f, 9f).apply { near = 0.3f; far = NEAR_FAR }
        farCamera = PerspectiveCamera(FOV, 16f, 9f).apply { near = 2f; far = 8000f }
        skylineCamera = PerspectiveCamera(FOV, 16f, 9f).apply { near = SKYLINE_NEAR; far = SKYLINE_FAR }
        overlayCamera = PerspectiveCamera(BikeOverlay.FOV, 16f, 9f).apply {
            near = BikeOverlay.NEAR; far = BikeOverlay.FAR
            val pitch = Math.toRadians(BikeOverlay.PITCH_DEGREES.toDouble())
            position.set(0f, 0f, 0f)
            direction.set(0f, -sin(pitch).toFloat(), -cos(pitch).toFloat())
            up.set(0f, cos(pitch).toFloat(), -sin(pitch).toFloat())
        }

        hud = Hud(hudListener)
        hud.create()
        hud.setPaceAdjustable(pace is AdjustablePace)
        Gdx.input.inputProcessor = hud

        resize(Gdx.graphics.width, Gdx.graphics.height)
        music.start()
    }

    private fun sceneMesh(static: Boolean, maxVertices: Int) = Mesh(
        static, maxOf(maxVertices, 3), 0,
        VertexAttribute(Usage.Position, 3, "a_position"),
        VertexAttribute(Usage.Normal, 3, "a_normal"),
        VertexAttribute(Usage.ColorPacked, 4, GL20.GL_UNSIGNED_BYTE, true, "a_color"),
    )

    private fun staticMesh(w: MeshWriter): Mesh {
        val m = sceneMesh(true, w.vertexCount)
        m.setVertices(w.floats, 0, w.vertexCount * MeshWriter.STRIDE)
        return m
    }

    private fun buildChunk(slot: Int, index: Long) {
        Scenery.buildChunk(chunkWriter, index, seed, rng)
        chunkVertices[slot] = chunkWriter.vertexCount
        chunkMeshes[slot].setVertices(chunkWriter.floats, 0, chunkWriter.vertexCount * MeshWriter.STRIDE)
    }

    override fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewWidth = width
        viewHeight = height
        for (c in arrayOf(camera, farCamera, skylineCamera, overlayCamera)) {
            c.viewportWidth = width.toFloat()
            c.viewportHeight = height.toFloat()
        }
        overlayCamera.update()
        hud.resize(width, height)
    }

    override fun render() {
        if (!paused) timestep.advance(Gdx.graphics.deltaTime) { update(it) }
        draw()
    }

    fun simulateTo(seconds: Double) {
        timestep.advanceTo(seconds) { update(it) }
    }

    fun setPaused(value: Boolean) {
        if (value == paused) return
        paused = value
        if (paused) music.pause() else music.resume()
    }

    private fun update(dt: Float) {
        ride.step(dt, pace.metersPerSecond)
        jogger.step(dt, ride.elapsedSeconds, ride.speed)
    }

    fun draw() {
        val t = ride.elapsedSeconds
        val distance = ride.distanceMeters
        sky.update(t)
        track.update(distance) { slot, index -> buildChunk(slot, index) }
        placeCameras()

        val gl = Gdx.gl
        gl.glDepthMask(true)
        gl.glClearColor(sky.horizon.r, sky.horizon.g, sky.horizon.b, 1f)
        gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        // 1. Sky.
        gl.glDisable(GL20.GL_DEPTH_TEST)
        gl.glDepthMask(false)
        gl.glDisable(GL20.GL_CULL_FACE)
        gl.glDisable(GL20.GL_BLEND)
        drawSky()

        // 2. Flat far planes, painter's order, no depth.
        gl.glEnable(GL20.GL_CULL_FACE)
        gl.glCullFace(GL20.GL_BACK)
        sceneShader.bind()
        setLighting()
        sceneShader.setUniformf("u_fog", FOG_START, 1f / (FOG_END - FOG_START), FOG_MAX)
        sceneShader.setUniformf("u_detail", 0f, 0f, 1e9f, 0f)
        sceneShader.setUniformf("u_water", 0f)
        sceneShader.setUniformMatrix("u_projView", farCamera.combined)
        sceneShader.setUniformf("u_camPos", farCamera.position)
        sceneShader.setUniformf("u_offset", 0f, 0f, 0f)
        farMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, farVertices)

        // 3. Far bank, treeline and boats, with depth.
        gl.glEnable(GL20.GL_DEPTH_TEST)
        gl.glDepthFunc(GL20.GL_LEQUAL)
        gl.glDepthMask(true)
        sceneShader.setUniformMatrix("u_projView", skylineCamera.combined)
        sceneShader.setUniformf("u_camPos", skylineCamera.position)
        drawSkyline(distance)
        drawBoats(t, distance)

        // 4. Near scene with fresh depth.
        gl.glClear(GL20.GL_DEPTH_BUFFER_BIT)
        sceneShader.setUniformMatrix("u_projView", camera.combined)
        sceneShader.setUniformf("u_camPos", camera.position)
        sceneShader.setUniformf("u_detail", GROUT_FADE_START, 1f / GROUT_FADE_LENGTH, OBJECT_FADE_START, 1f / (NEAR_FAR - 10f - OBJECT_FADE_START))
        for (slot in 0 until CHUNK_SLOTS) {
            val start = track.indices[slot] * Scenery.CHUNK_LENGTH.toDouble()
            // Camera-relative offset computed in double, so precision holds after hours of riding.
            sceneShader.setUniformf("u_offset", 0f, 0f, (distance - start).toFloat())
            chunkMeshes[slot].render(sceneShader, GL20.GL_TRIANGLES, 0, chunkVertices[slot])
        }
        joggerModel.build(joggerWriter, t, jogger, JoggerMotion.lateral(t), -jogger.gap, JoggerMotion.headingDegrees(t))
        joggerMesh.setVertices(joggerWriter.floats, 0, joggerWriter.vertexCount * MeshWriter.STRIDE)
        sceneShader.setUniformf("u_offset", 0f, 0f, 0f)
        joggerMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, joggerWriter.vertexCount)

        sceneShader.setUniformf("u_offset", 0f, 0f, (distance % Scenery.WATER_PERIOD).toFloat())
        sceneShader.setUniformf("u_water", 1f)
        waterMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, waterVertices)
        sceneShader.setUniformf("u_water", 0f)

        // 5. The rider's handlebars, in eye space over everything else.
        if (view == View.RIDER) {
            gl.glClear(GL20.GL_DEPTH_BUFFER_BIT)
            bikeOverlay.build(overlayWriter, ride.wheelAngle, ride.crankAngle, ride.speed,
                RideState.sway(ride.crankAngle, ride.speed), RideState.bob(ride.crankAngle, ride.speed))
            overlayMesh.setVertices(overlayWriter.floats, 0, overlayWriter.vertexCount * MeshWriter.STRIDE)
            sceneShader.setUniformMatrix("u_projView", overlayCamera.combined)
            sceneShader.setUniformf("u_camPos", 0f, 0f, 0f)
            sceneShader.setUniformf("u_offset", 0f, 0f, 0f)
            sceneShader.setUniformf("u_fog", 1e6f, 1f, 0f)
            sceneShader.setUniformf("u_detail", 0f, 0f, 1e9f, 0f)
            overlayMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, overlayWriter.vertexCount)
        }

        gl.glDepthMask(true)
        gl.glDisable(GL20.GL_DEPTH_TEST)
        gl.glDisable(GL20.GL_CULL_FACE)

        // 6. HUD.
        hud.draw(t, distance, ride.speed, music.state, paused)
    }

    /**
     * Draws the skyline as copies one [Skyline.PERIOD] apart, slid by distance so it scrolls past.
     * Each copy is drawn section by section: sections whose near end is within
     * [SKYLINE_DETAIL_DISTANCE] get window striping, farther ones the plain version, and sections
     * wholly behind the camera or past the far plane are skipped.
     */
    private fun drawSkyline(distance: Double) {
        val o = (distance % Skyline.PERIOD).toFloat()
        for (k in 0 until SKYLINE_COPIES) {
            val zOff = o - k * Skyline.PERIOD
            sceneShader.setUniformf("u_offset", 0f, 0f, zOff)
            for (s in 0 until Skyline.SECTIONS) {
                val near = zOff - s * Skyline.SECTION_LENGTH
                val far = near - Skyline.SECTION_LENGTH - Skyline.SECTION_OVERHANG
                if (far > 0f || near < -SKYLINE_FAR) continue
                if (near > -SKYLINE_DETAIL_DISTANCE) skylineMeshes[s].render(sceneShader, GL20.GL_TRIANGLES, 0, skylineVertices[s])
                else skylineSimpleMeshes[s].render(sceneShader, GL20.GL_TRIANGLES, 0, skylineSimpleVertices[s])
            }
        }
    }

    /** A cargo boat heading upstream and a ferry heading downstream, looping along the river. */
    private fun drawBoats(t: Double, distance: Double) {
        val cargoZ = loop(-1400.0 - 2.2 * t + distance)
        sceneShader.setUniformf("u_offset", 250f, 0f, cargoZ)
        cargoMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, cargoVertices)
        val ferryZ = loop(-600.0 + 4.5 * t + distance)
        sceneShader.setUniformf("u_offset", 175f, 0f, ferryZ)
        ferryMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, ferryVertices)
    }

    /** Wraps a render-space z into the stretch of river where boats are drawn. */
    private fun loop(z: Double): Float {
        val span = BOAT_LOOP
        val r = ((z + span - 200.0) % span + span) % span
        return (r - span + 200.0).toFloat()
    }

    private fun placeCameras() {
        val crank = ride.crankAngle
        val speed = ride.speed
        val sway = RideState.sway(crank, speed)
        val bob = RideState.bob(crank, speed)
        val roll = Math.toRadians(RideState.roll(crank, speed).toDouble()).toFloat()
        camera.position.set(CAMERA_X + sway, EYE_HEIGHT + bob, 0f)
        camera.direction.set(sway * 0.08f + CAMERA_YAW, -CAMERA_PITCH, -1f).nor()
        camera.up.set(sin(roll), cos(roll), 0f)
        camera.normalizeUp()
        if (view != View.RIDER) {
            val t = ride.elapsedSeconds
            val jx = JoggerMotion.lateral(t)
            val jz = -jogger.gap
            when (view) {
                View.JOGGER_SIDE -> camera.position.set(jx - 2.6f, 1.05f, jz - 0.1f)
                View.JOGGER_FRONT -> camera.position.set(jx - 1.1f, 1.45f, jz - 2.6f)
                else -> camera.position.set(jx - 0.7f, 1.5f, jz + 2.8f)
            }
            camera.up.set(0f, 1f, 0f)
            camera.lookAt(jx, 0.95f, jz)
        }
        camera.update()
        follow(farCamera)
        follow(skylineCamera)
    }

    private fun follow(c: PerspectiveCamera) {
        c.position.set(camera.position)
        c.direction.set(camera.direction)
        c.up.set(camera.up)
        c.update()
    }

    private fun setLighting() {
        sceneShader.setUniformf("u_sunDir", sky.sunDirection)
        sceneShader.setUniformf("u_sunColor", sky.sunColor.r, sky.sunColor.g, sky.sunColor.b)
        sceneShader.setUniformf("u_ambSky", sky.ambientSky.r, sky.ambientSky.g, sky.ambientSky.b)
        sceneShader.setUniformf("u_ambGround", sky.ambientGround.r, sky.ambientGround.g, sky.ambientGround.b)
        sceneShader.setUniformf("u_fogColor", sky.horizon.r, sky.horizon.g, sky.horizon.b)
        val g = Scenery.TILE_BASE
        sceneShader.setUniformf("u_groutTo", g[0], g[1], g[2])
    }

    private fun drawSky() {
        val w = viewWidth.toFloat()
        val h = viewHeight.toFloat()
        // Horizon: a point far ahead at eye height.
        tmp.set(farCamera.direction.x, 0f, farCamera.direction.z).nor().scl(4000f).add(farCamera.position)
        tmp.y = farCamera.position.y
        farCamera.project(tmp, 0f, 0f, w, h)
        val horizonY = tmp.y
        val sunAhead = sky.sunDirection.dot(farCamera.direction) > 0.05f
        tmp.set(sky.sunDirection).scl(4000f).add(farCamera.position)
        farCamera.project(tmp, 0f, 0f, w, h)
        skyShader.bind()
        skyShader.setUniformf("u_zenith", sky.zenith.r, sky.zenith.g, sky.zenith.b)
        skyShader.setUniformf("u_horizon", sky.horizon.r, sky.horizon.g, sky.horizon.b)
        skyShader.setUniformf("u_sunColor", sky.sunColor.r, sky.sunColor.g, sky.sunColor.b)
        skyShader.setUniformf("u_geom", horizonY, 1f / h, tmp.x, tmp.y)
        skyShader.setUniformf("u_sun", if (sunAhead) 1f else 0f)
        skyMesh.render(skyShader, GL20.GL_TRIANGLE_FAN, 0, 4)
    }

    override fun dispose() {
        if (::hud.isInitialized) hud.dispose()
        if (::chunkMeshes.isInitialized) chunkMeshes.forEach { it.dispose() }
        if (::skylineMeshes.isInitialized) skylineMeshes.forEach { it.dispose() }
        if (::skylineSimpleMeshes.isInitialized) skylineSimpleMeshes.forEach { it.dispose() }
        for (m in arrayOf(
            if (::joggerMesh.isInitialized) joggerMesh else null,
            if (::overlayMesh.isInitialized) overlayMesh else null,
            if (::waterMesh.isInitialized) waterMesh else null,
            if (::ferryMesh.isInitialized) ferryMesh else null,
            if (::cargoMesh.isInitialized) cargoMesh else null,
            if (::farMesh.isInitialized) farMesh else null,
            if (::skyMesh.isInitialized) skyMesh else null,
        )) m?.dispose()
        if (::skyShader.isInitialized) skyShader.dispose()
        if (::sceneShader.isInitialized) sceneShader.dispose()
    }

    companion object {
        const val DEFAULT_SEED = 0x50_1070_2024L
        const val CHUNK_SLOTS = 14
        const val FOV = 60f
        /** Near-scene far plane: just past the reach of the chunk slots. */
        const val NEAR_FAR = (CHUNK_SLOTS - 1) * Scenery.CHUNK_LENGTH + 18f
        const val FOG_START = 60f
        const val FOG_END = 5600f
        /** The far bank never fogs out completely: a light haze at the horizon. */
        const val FOG_MAX = 0.85f
        const val GROUT_FADE_START = 10f
        const val GROUT_FADE_LENGTH = 30f
        /** Objects in the near chunks fade into the haze from here to the near far plane. */
        const val OBJECT_FADE_START = 250f
        const val SKYLINE_NEAR = 100f
        const val SKYLINE_FAR = 6000f
        const val SKYLINE_COPIES = 3
        const val SKYLINE_DETAIL_DISTANCE = 1500f
        const val BOAT_LOOP = 4200.0
        const val CAMERA_X = -0.15f
        const val EYE_HEIGHT = 1.55f
        /** Forward lean: the view tips down a little, as from a bike saddle. */
        const val CAMERA_PITCH = 0.10f
        const val CAMERA_YAW = 0.0f
    }
}
