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

/**
 * Roadwork: a first-person jog along a waterfront path at dawn, behind a coach on a bicycle.
 *
 * Contract used by the launchers:
 * - [render] advances simulated time by real frame time in fixed steps (unless paused), then draws.
 * - [simulateTo] advances simulated time to an absolute second deterministically (screenshot mode).
 * - [draw] draws the current state without advancing time.
 *
 * [create] calls [MusicController.start]. Input (touch and keys) is handled by the HUD, which is
 * installed as `Gdx.input.inputProcessor` in [create].
 */
class RoadworkGame(
    private val music: MusicController = SilentMusicController(),
    private val pace: PaceSource = AdjustablePace(),
    private val actions: PlatformActions = NoPlatformActions,
    private val seed: Long = DEFAULT_SEED,
) : ApplicationAdapter() {
    private val timestep = FixedTimestep(stepSeconds = 1f / 60f)
    private val run = RunState(pace.metersPerSecond)
    private val coachMotion = CoachMotion()
    private val sky = DawnSky()
    private val track = ChunkTrack(Scenery.CHUNK_LENGTH, CHUNK_SLOTS)
    private val rng = Rng(seed)

    /** True while the rider has paused the session: time, distance and the scene stand still. */
    var paused = false
        private set

    /**
     * Inspection camera for development screenshots: [View.RUNNER] is the normal first-person view;
     * the others orbit the coach so the bike and rider animation can be checked up close.
     */
    enum class View { RUNNER, COACH_SIDE, COACH_FRONT }
    var view = View.RUNNER

    val simulatedSeconds: Double get() = timestep.simulatedSeconds
    val elapsedSeconds: Double get() = run.elapsedSeconds
    val distanceMeters: Double get() = run.distanceMeters

    private lateinit var camera: PerspectiveCamera
    private lateinit var farCamera: PerspectiveCamera
    private lateinit var sceneShader: ShaderProgram
    private lateinit var skyShader: ShaderProgram
    private lateinit var skyMesh: Mesh
    private lateinit var farMesh: Mesh
    private lateinit var waterMesh: Mesh
    private lateinit var coachMesh: Mesh
    private lateinit var chunkMeshes: Array<Mesh>
    private val chunkVertices = IntArray(CHUNK_SLOTS)
    private var farVertices = 0
    private var waterVertices = 0
    private lateinit var chunkWriter: MeshWriter
    private lateinit var coachWriter: MeshWriter
    private val coachModel = CoachModel()
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

        chunkWriter = MeshWriter(FAR_MAX_VERTICES)
        Scenery.buildFar(chunkWriter, seed, rng)
        farVertices = chunkWriter.vertexCount
        farMesh = sceneMesh(true, farVertices)
        farMesh.setVertices(chunkWriter.floats, 0, farVertices * MeshWriter.STRIDE)

        Scenery.buildNearWater(chunkWriter, seed)
        waterVertices = chunkWriter.vertexCount
        waterMesh = sceneMesh(true, waterVertices)
        waterMesh.setVertices(chunkWriter.floats, 0, waterVertices * MeshWriter.STRIDE)

        chunkMeshes = Array(CHUNK_SLOTS) { sceneMesh(false, Scenery.CHUNK_MAX_VERTICES) }
        for (slot in 0 until CHUNK_SLOTS) buildChunk(slot, track.indices[slot])

        coachWriter = MeshWriter(CoachModel.MAX_VERTICES)
        coachMesh = sceneMesh(false, CoachModel.MAX_VERTICES)

        camera = PerspectiveCamera(FOV, 16f, 9f).apply { near = 0.3f; far = NEAR_FAR }
        farCamera = PerspectiveCamera(FOV, 16f, 9f).apply { near = 2f; far = 4500f }

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

    private fun buildChunk(slot: Int, index: Long) {
        Scenery.buildChunk(chunkWriter, index, seed, rng)
        chunkVertices[slot] = chunkWriter.vertexCount
        chunkMeshes[slot].setVertices(chunkWriter.floats, 0, chunkWriter.vertexCount * MeshWriter.STRIDE)
    }

    override fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewWidth = width
        viewHeight = height
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        farCamera.viewportWidth = width.toFloat()
        farCamera.viewportHeight = height.toFloat()
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
        run.step(dt, pace.metersPerSecond)
        coachMotion.step(dt, run.elapsedSeconds, run.speed)
    }

    fun draw() {
        val t = run.elapsedSeconds
        val distance = run.distanceMeters
        sky.update(t)
        track.update(distance) { slot, index -> buildChunk(slot, index) }
        placeCameras()

        val gl = Gdx.gl
        gl.glClearColor(sky.horizon.r, sky.horizon.g, sky.horizon.b, 1f)
        gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        // 1. Sky.
        gl.glDisable(GL20.GL_DEPTH_TEST)
        gl.glDepthMask(false)
        gl.glDisable(GL20.GL_CULL_FACE)
        gl.glDisable(GL20.GL_BLEND)
        drawSky()

        // 2. Far layer, painter's order, no depth.
        gl.glEnable(GL20.GL_CULL_FACE)
        gl.glCullFace(GL20.GL_BACK)
        sceneShader.bind()
        setLighting()
        sceneShader.setUniformMatrix("u_projView", farCamera.combined)
        sceneShader.setUniformf("u_camPos", farCamera.position)
        sceneShader.setUniformf("u_fog", FOG_START, 1f / (FOG_END - FOG_START), FAR_FOG_MAX)
        sceneShader.setUniformf("u_offset", 0f, 0f, 0f)
        sceneShader.setUniformf("u_water", 0.6f)
        farMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, farVertices)

        // 3. Near scene with depth.
        gl.glEnable(GL20.GL_DEPTH_TEST)
        gl.glDepthFunc(GL20.GL_LEQUAL)
        gl.glDepthMask(true)
        sceneShader.setUniformMatrix("u_projView", camera.combined)
        sceneShader.setUniformf("u_camPos", camera.position)
        sceneShader.setUniformf("u_fog", FOG_START, 1f / (FOG_END - FOG_START), 1f)
        sceneShader.setUniformf("u_water", 0f)
        for (slot in 0 until CHUNK_SLOTS) {
            val start = track.indices[slot] * Scenery.CHUNK_LENGTH.toDouble()
            // Camera-relative offset computed in double, so precision holds after hours of running.
            sceneShader.setUniformf("u_offset", 0f, 0f, (distance - start).toFloat())
            chunkMeshes[slot].render(sceneShader, GL20.GL_TRIANGLES, 0, chunkVertices[slot])
        }
        coachModel.build(coachWriter, t, coachMotion, CoachMotion.lateral(t), -CoachMotion.gap(t))
        coachMesh.setVertices(coachWriter.floats, 0, coachWriter.vertexCount * MeshWriter.STRIDE)
        sceneShader.setUniformf("u_offset", 0f, 0f, 0f)
        coachMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, coachWriter.vertexCount)

        sceneShader.setUniformf("u_offset", 0f, 0f, (distance % Scenery.WATER_PERIOD).toFloat())
        sceneShader.setUniformf("u_water", 1f)
        waterMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, waterVertices)

        gl.glDepthMask(true)
        gl.glDisable(GL20.GL_DEPTH_TEST)
        gl.glDisable(GL20.GL_CULL_FACE)

        // 4. HUD.
        hud.draw(t, distance, run.speed, music.state, paused)
    }

    private fun placeCameras() {
        val phase = run.stridePhase
        val speed = run.speed
        val bob = RunState.bob(phase, speed)
        val sway = RunState.sway(phase, speed)
        camera.position.set(CAMERA_X + sway, EYE_HEIGHT + bob, 0f)
        camera.direction.set(sway * 0.12f, -0.075f, -1f).nor()
        camera.up.set(sway * 0.25f, 1f, 0f)
        camera.normalizeUp()
        if (view != View.RUNNER) {
            val t = run.elapsedSeconds
            val cx = CoachMotion.lateral(t)
            val cz = -CoachMotion.gap(t)
            if (view == View.COACH_SIDE) camera.position.set(cx + 2.6f, 1.1f, cz - 0.1f)
            else camera.position.set(cx + 1.4f, 1.5f, cz - 2.4f)
            camera.up.set(0f, 1f, 0f)
            camera.lookAt(cx, 0.85f, cz)
        }
        camera.update()
        farCamera.position.set(camera.position)
        farCamera.direction.set(camera.direction)
        farCamera.up.set(camera.up)
        farCamera.update()
    }

    private fun setLighting() {
        sceneShader.setUniformf("u_sunDir", sky.sunDirection)
        sceneShader.setUniformf("u_sunColor", sky.sunColor.r, sky.sunColor.g, sky.sunColor.b)
        sceneShader.setUniformf("u_ambSky", sky.ambientSky.r, sky.ambientSky.g, sky.ambientSky.b)
        sceneShader.setUniformf("u_ambGround", sky.ambientGround.r, sky.ambientGround.g, sky.ambientGround.b)
        sceneShader.setUniformf("u_fogColor", sky.horizon.r, sky.horizon.g, sky.horizon.b)
        sceneShader.setUniformf("u_glow", sky.lampGlow)
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
        if (::coachMesh.isInitialized) coachMesh.dispose()
        if (::waterMesh.isInitialized) waterMesh.dispose()
        if (::farMesh.isInitialized) farMesh.dispose()
        if (::skyMesh.isInitialized) skyMesh.dispose()
        if (::skyShader.isInitialized) skyShader.dispose()
        if (::sceneShader.isInitialized) sceneShader.dispose()
    }

    companion object {
        const val DEFAULT_SEED = 0x50_1070_2024L
        const val CHUNK_SLOTS = 13
        const val FOV = 60f
        const val NEAR_FAR = 235f
        const val FOG_START = 18f
        const val FOG_END = 225f
        /** The far layer never fogs out completely, so the hills and the city stay as soft silhouettes. */
        const val FAR_FOG_MAX = 0.72f
        const val CAMERA_X = -1.0f
        const val EYE_HEIGHT = 1.62f
        const val FAR_MAX_VERTICES = 40000
    }
}
