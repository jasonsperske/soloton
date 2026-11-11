package dev.soloton.roadwork.env.skylift

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Vector3
import dev.soloton.roadwork.ChunkTrack
import dev.soloton.roadwork.EnvironmentScene
import dev.soloton.roadwork.MeshWriter
import dev.soloton.roadwork.RideState
import dev.soloton.roadwork.RiderCamera
import dev.soloton.roadwork.Rng
import dev.soloton.roadwork.SceneLight
import dev.soloton.roadwork.SceneMeshes
import dev.soloton.roadwork.Shaders

/**
 * The mesa: a desert trail the bike rides along and, as the rider turns the resistance up, lifts
 * off from. The harder the bike is set, the higher it flies ([SkyliftMotion.liftFor]) and the more
 * often drifters come the other way; the bike swerves round the ones in its path.
 *
 * Draw order per frame: sky; the far sand and mesas (no depth); the near chunks and the traffic
 * (fresh depth). The game then draws the rider's handlebars and the HUD.
 */
class SkyliftScene(private val seed: Long) : EnvironmentScene {
    private val motion = SkyliftMotion(seed)
    private val sky = MesaSky()
    private val track = ChunkTrack(MesaScenery.CHUNK_LENGTH, CHUNK_SLOTS)
    private val rng = Rng(seed)

    override val light: SceneLight get() = sky

    private lateinit var camera: PerspectiveCamera
    private lateinit var farCamera: PerspectiveCamera
    private lateinit var sceneShader: ShaderProgram
    private lateinit var skyShader: ShaderProgram
    private lateinit var skyMesh: Mesh
    private lateinit var farMesh: Mesh
    private lateinit var trafficMesh: Mesh
    private lateinit var chunkMeshes: Array<Mesh>
    private val chunkVertices = IntArray(CHUNK_SLOTS)
    private var farVertices = 0
    private lateinit var chunkWriter: MeshWriter
    private lateinit var trafficWriter: MeshWriter

    private var viewWidth = 1
    private var viewHeight = 1
    private val tmp = Vector3()

    override fun create(ride: RideState) {
        // Start at the height the resistance already calls for (a switch part way through a ride).
        motion.start(ride.resistance)

        sceneShader = Shaders.compile(Shaders.SCENE_VERTEX, Shaders.SCENE_FRAGMENT)
        skyShader = Shaders.compile(Shaders.SKY_VERTEX, Shaders.SKY_FRAGMENT)

        skyMesh = Mesh(true, 4, 0, VertexAttribute(Usage.Position, 2, "a_position"))
        skyMesh.setVertices(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f))

        chunkWriter = MeshWriter(maxOf(MesaScenery.CHUNK_MAX_VERTICES, MesaScenery.FAR_MAX_VERTICES))
        MesaScenery.buildFar(chunkWriter, seed, rng)
        farVertices = chunkWriter.vertexCount
        farMesh = SceneMeshes.static(chunkWriter)

        chunkMeshes = Array(CHUNK_SLOTS) { SceneMeshes.create(false, MesaScenery.CHUNK_MAX_VERTICES) }
        for (slot in 0 until CHUNK_SLOTS) buildChunk(slot, track.indices[slot])

        trafficWriter = MeshWriter(MesaScenery.TRAFFIC_MAX_VERTICES)
        trafficMesh = SceneMeshes.create(false, MesaScenery.TRAFFIC_MAX_VERTICES)

        camera = PerspectiveCamera(RiderCamera.FOV, 16f, 9f).apply { near = 0.3f; far = NEAR_FAR }
        farCamera = PerspectiveCamera(RiderCamera.FOV, 16f, 9f).apply { near = 2f; far = 12000f }
    }

    private fun buildChunk(slot: Int, index: Long) {
        MesaScenery.buildChunk(chunkWriter, index, seed, rng)
        chunkVertices[slot] = chunkWriter.vertexCount
        SceneMeshes.upload(chunkMeshes[slot], chunkWriter)
    }

    override fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewWidth = width
        viewHeight = height
        for (c in arrayOf(camera, farCamera)) {
            c.viewportWidth = width.toFloat()
            c.viewportHeight = height.toFloat()
        }
    }

    override fun step(dt: Float, ride: RideState) {
        motion.step(dt, ride.speed, ride.resistance)
    }

    override fun draw(ride: RideState) {
        val distance = ride.distanceMeters
        track.update(distance) { slot, index -> buildChunk(slot, index) }
        placeCameras(ride)

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

        // 2. Far sand and mesas, painter's order, no depth.
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

        // 3. Near chunks and the traffic, with depth.
        gl.glEnable(GL20.GL_DEPTH_TEST)
        gl.glDepthFunc(GL20.GL_LEQUAL)
        gl.glDepthMask(true)
        gl.glClear(GL20.GL_DEPTH_BUFFER_BIT)
        sceneShader.setUniformMatrix("u_projView", camera.combined)
        sceneShader.setUniformf("u_camPos", camera.position)
        sceneShader.setUniformf("u_detail", RIPPLE_FADE_START, 1f / RIPPLE_FADE_LENGTH, OBJECT_FADE_START, 1f / (NEAR_FAR - 10f - OBJECT_FADE_START))
        for (slot in 0 until CHUNK_SLOTS) {
            val start = track.indices[slot] * MesaScenery.CHUNK_LENGTH.toDouble()
            // Camera-relative offset computed in double, so precision holds after hours of riding.
            sceneShader.setUniformf("u_offset", 0f, 0f, (distance - start).toFloat())
            chunkMeshes[slot].render(sceneShader, GL20.GL_TRIANGLES, 0, chunkVertices[slot])
        }
        MesaScenery.buildTraffic(trafficWriter, motion)
        if (trafficWriter.vertexCount > 0) {
            SceneMeshes.upload(trafficMesh, trafficWriter)
            sceneShader.setUniformf("u_offset", 0f, 0f, 0f)
            trafficMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, trafficWriter.vertexCount)
        }
    }

    /** The rider's view, raised by the lift, moved sideways by the swerve and leaning into it. */
    private fun placeCameras(ride: RideState) {
        RiderCamera.place(camera, ride)
        camera.position.x += motion.dodge
        camera.position.y += motion.altitude
        // Higher up, look down a little more, so the ground stays in view.
        camera.direction.y -= EXTRA_PITCH * motion.altitude / SkyliftMotion.MAX_ALTITUDE
        camera.direction.nor()
        camera.up.x += (motion.dodgeSpeed * LEAN_PER_SPEED).coerceIn(-MAX_LEAN, MAX_LEAN)
        camera.normalizeUp()
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
        val s = MesaScenery.SAND
        sceneShader.setUniformf("u_groutTo", s[0], s[1], s[2])
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
        if (::chunkMeshes.isInitialized) chunkMeshes.forEach { it.dispose() }
        if (::trafficMesh.isInitialized) trafficMesh.dispose()
        if (::farMesh.isInitialized) farMesh.dispose()
        if (::skyMesh.isInitialized) skyMesh.dispose()
        if (::skyShader.isInitialized) skyShader.dispose()
        if (::sceneShader.isInitialized) sceneShader.dispose()
    }

    companion object {
        const val CHUNK_SLOTS = 14
        /** Near-scene far plane: just past the reach of the chunk slots. */
        const val NEAR_FAR = (CHUNK_SLOTS - 1) * MesaScenery.CHUNK_LENGTH + 18f
        const val FOG_START = 80f
        const val FOG_END = 6000f
        /** The far mesas never fog out completely. */
        const val FOG_MAX = 0.8f
        /** The sand's ripples fade into plain sand over this stretch. */
        const val RIPPLE_FADE_START = 40f
        const val RIPPLE_FADE_LENGTH = 160f
        /** Spires, plants, floating rocks and traffic fade into the haze from here to the near far plane. */
        const val OBJECT_FADE_START = 300f
        /** Extra downward tilt of the view at full altitude. */
        const val EXTRA_PITCH = 0.14f
        const val LEAN_PER_SPEED = 0.035f
        const val MAX_LEAN = 0.12f
    }
}
