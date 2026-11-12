package dev.soloton.roadwork.env.pedalcraft

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Vector3
import dev.soloton.roadwork.EnvironmentScene
import dev.soloton.roadwork.MeshWriter
import dev.soloton.roadwork.RideState
import dev.soloton.roadwork.RiderCamera
import dev.soloton.roadwork.Rng
import dev.soloton.roadwork.SceneLight
import dev.soloton.roadwork.SceneMeshes
import dev.soloton.roadwork.Shaders
import kotlin.math.sqrt

/**
 * Pedal-powered flight round an island. The rider's pedalling drives the propeller of one of three
 * craft ([Craft]) along a route between landing spots ([FlightMotion]); the bike's resistance adds
 * height. The view follows the craft from behind, so no handlebars are drawn.
 *
 * Unlike the trail environments, the world here stands still and the craft moves through it.
 *
 * Draw order per frame: sky; the sea (no depth, centred under the camera); the whole island,
 * its shallows, markings, buildings and clouds through a camera whose near plane is far out, for
 * depth precision at a distance; then, with fresh depth, the nearby tiles again and the craft with
 * its shadow. The game then draws the HUD.
 */
class PedalcraftScene(private val seed: Long) : EnvironmentScene {
    private val terrain = IslandTerrain(seed)
    private val motion = FlightMotion(terrain, seed)
    private val sky = IslandSky()
    private val rng = Rng(seed)

    /** Development cameras: the whole island from above, and the craft from its right side. */
    private enum class View(val label: String) { OVERVIEW("overview"), CRAFT_SIDE("craft-side") }
    private var view: View? = null

    override val light: SceneLight get() = sky
    override val inspectionViews: List<String> = View.entries.map { it.label }

    /** The view follows the craft from behind, so the game does not draw the rider's handlebars. */
    override val riderView: Boolean get() = false

    private lateinit var camera: PerspectiveCamera
    private lateinit var farCamera: PerspectiveCamera
    private lateinit var seaCamera: PerspectiveCamera
    private lateinit var sceneShader: ShaderProgram
    private lateinit var skyShader: ShaderProgram
    private lateinit var skyMesh: Mesh
    private lateinit var seaMesh: Mesh
    private lateinit var shallowsMesh: Mesh
    private lateinit var cloudsMesh: Mesh
    private lateinit var markingsMesh: Mesh
    private lateinit var buildingsMesh: Mesh
    private lateinit var craftMesh: Mesh
    private lateinit var shadowMesh: Mesh
    private lateinit var tileMeshes: Array<Mesh>
    private val tileVertices = IntArray(IslandMeshes.TILES * IslandMeshes.TILES)
    private var seaVertices = 0
    private var shallowsVertices = 0
    private var cloudsVertices = 0
    private var markingsVertices = 0
    private var buildingsVertices = 0
    private lateinit var craftWriter: MeshWriter
    private lateinit var shadowWriter: MeshWriter

    private var viewWidth = 1
    private var viewHeight = 1
    private val tmp = Vector3()

    override fun create(ride: RideState) {
        sceneShader = Shaders.compile(Shaders.SCENE_VERTEX, Shaders.SCENE_FRAGMENT)
        skyShader = Shaders.compile(Shaders.SKY_VERTEX, Shaders.SKY_FRAGMENT)

        skyMesh = Mesh(true, 4, 0, VertexAttribute(Usage.Position, 2, "a_position"))
        skyMesh.setVertices(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f))

        val w = MeshWriter(IslandMeshes.SHALLOWS_MAX_VERTICES)
        val heights = IslandMeshes.heights(terrain)
        tileMeshes = Array(IslandMeshes.TILES * IslandMeshes.TILES) { t ->
            IslandMeshes.buildTile(w, terrain, heights, t % IslandMeshes.TILES, t / IslandMeshes.TILES, seed)
            tileVertices[t] = w.vertexCount
            SceneMeshes.static(w)
        }
        IslandMeshes.buildShallows(w, heights)
        shallowsVertices = w.vertexCount
        shallowsMesh = SceneMeshes.static(w)
        IslandMeshes.buildSea(w)
        seaVertices = w.vertexCount
        seaMesh = SceneMeshes.static(w)
        IslandMeshes.buildClouds(w, seed, rng)
        cloudsVertices = w.vertexCount
        cloudsMesh = SceneMeshes.static(w)
        IslandMeshes.buildMarkings(w, terrain)
        markingsVertices = w.vertexCount
        markingsMesh = SceneMeshes.static(w)
        IslandMeshes.buildBuildings(w, terrain, seed)
        buildingsVertices = w.vertexCount
        buildingsMesh = SceneMeshes.static(w)

        craftWriter = MeshWriter(CraftModel.MAX_VERTICES)
        craftMesh = SceneMeshes.create(false, CraftModel.MAX_VERTICES)
        shadowWriter = MeshWriter(CraftModel.SHADOW_VERTICES)
        shadowMesh = SceneMeshes.create(false, CraftModel.SHADOW_VERTICES)

        camera = PerspectiveCamera(RiderCamera.FOV, 16f, 9f).apply { near = NEAR_NEAR; far = NEAR_FAR }
        farCamera = PerspectiveCamera(RiderCamera.FOV, 16f, 9f).apply { near = FAR_NEAR; far = FAR_FAR }
        // The sea is drawn without depth, so one camera can take it from under the craft to the horizon.
        seaCamera = PerspectiveCamera(RiderCamera.FOV, 16f, 9f).apply { near = NEAR_NEAR; far = FAR_FAR }
    }

    override fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewWidth = width
        viewHeight = height
        for (c in arrayOf(camera, farCamera, seaCamera)) {
            c.viewportWidth = width.toFloat()
            c.viewportHeight = height.toFloat()
        }
    }

    /** The rider's choice of aircraft. Chosen during a flight, it takes effect on the next leg. */
    override fun applyOptions(options: Map<String, String>) {
        motion.fleet = Fleet.byId(options[Pedalcraft.CRAFT])
    }

    override fun step(dt: Float, ride: RideState) {
        motion.step(dt, ride.worldSpeed, ride.resistance)
    }

    override fun setInspectionView(name: String?): Boolean {
        val wanted = if (name == null) null else View.entries.firstOrNull { it.label == name } ?: return false
        view = wanted
        return true
    }

    override fun draw(ride: RideState) {
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

        // 2. The open sea, centred under the camera, no depth.
        gl.glEnable(GL20.GL_CULL_FACE)
        gl.glCullFace(GL20.GL_BACK)
        sceneShader.bind()
        setLighting()
        sceneShader.setUniformf("u_fog", FOG_START, 1f / (FOG_END - FOG_START), FOG_MAX)
        sceneShader.setUniformf("u_detail", 0f, 0f, 1e9f, 0f)
        sceneShader.setUniformMatrix("u_projView", seaCamera.combined)
        sceneShader.setUniformf("u_camPos", seaCamera.position)
        sceneShader.setUniformf("u_water", 1f)
        sceneShader.setUniformf("u_offset", seaCamera.position.x, 0f, seaCamera.position.z)
        seaMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, seaVertices)
        sceneShader.setUniformf("u_offset", 0f, 0f, 0f)

        // 3. The island from afar, with depth.
        gl.glEnable(GL20.GL_DEPTH_TEST)
        gl.glDepthFunc(GL20.GL_LEQUAL)
        gl.glDepthMask(true)
        sceneShader.setUniformMatrix("u_projView", farCamera.combined)
        sceneShader.setUniformf("u_camPos", farCamera.position)
        drawIsland(nearOnly = false)
        sceneShader.setUniformf("u_water", 0f)
        cloudsMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, cloudsVertices)

        // 4. What is close, again, with fresh depth; then the craft and its shadow.
        gl.glClear(GL20.GL_DEPTH_BUFFER_BIT)
        sceneShader.setUniformMatrix("u_projView", camera.combined)
        sceneShader.setUniformf("u_camPos", camera.position)
        drawIsland(nearOnly = true)

        CraftModel.buildShadow(shadowWriter, motion.craft, motion.x, terrain.ground(motion.x, motion.z) + 0.3f, motion.z, motion.heightAboveGround)
        SceneMeshes.upload(shadowMesh, shadowWriter)
        gl.glEnable(GL20.GL_POLYGON_OFFSET_FILL)
        gl.glPolygonOffset(-4f, -4f)
        shadowMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, shadowWriter.vertexCount)
        gl.glDisable(GL20.GL_POLYGON_OFFSET_FILL)

        CraftModel.build(
            craftWriter, motion.craft, motion.x, motion.y, motion.z, motion.dirX, motion.dirZ,
            motion.bankDegrees, motion.pitchDegrees, ride.crankAngle,
        )
        SceneMeshes.upload(craftMesh, craftWriter)
        // Wings and canopies are single sheets, seen from both sides (see CraftModel.sail).
        gl.glDisable(GL20.GL_CULL_FACE)
        craftMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, craftWriter.vertexCount)
    }

    /** Land tiles, shallows, markings and buildings through the camera already bound; [nearOnly] skips far tiles. */
    private fun drawIsland(nearOnly: Boolean) {
        val gl = Gdx.gl
        sceneShader.setUniformf("u_water", 0f)
        val tileSize = IslandMeshes.TILE_CELLS * IslandMeshes.CELL
        for (t in tileMeshes.indices) {
            if (tileVertices[t] == 0) continue
            if (nearOnly) {
                val cx = -IslandMeshes.HALF + (t % IslandMeshes.TILES + 0.5f) * tileSize
                val cz = -IslandMeshes.HALF + (t / IslandMeshes.TILES + 0.5f) * tileSize
                val dx = cx - camera.position.x
                val dz = cz - camera.position.z
                if (sqrt(dx * dx + dz * dz) - IslandMeshes.TILE_REACH > NEAR_FAR) continue
            }
            tileMeshes[t].render(sceneShader, GL20.GL_TRIANGLES, 0, tileVertices[t])
        }
        sceneShader.setUniformf("u_water", 1f)
        shallowsMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, shallowsVertices)
        sceneShader.setUniformf("u_water", 0f)
        // Paint on the ground: pulled toward the camera in depth, so it never fights the land under it.
        gl.glEnable(GL20.GL_POLYGON_OFFSET_FILL)
        gl.glPolygonOffset(-4f, -4f)
        markingsMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, markingsVertices)
        gl.glDisable(GL20.GL_POLYGON_OFFSET_FILL)
        buildingsMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, buildingsVertices)
    }

    /** Behind and above the craft, looking past it along its course; never under the ground. */
    private fun placeCameras() {
        val craft = motion.craft
        when (view) {
            View.OVERVIEW -> {
                camera.position.set(0f, 4300f, 3300f)
                camera.direction.set(0f, -150f, 100f).sub(camera.position).nor()
            }
            View.CRAFT_SIDE -> {
                camera.position.set(motion.x - motion.dirZ * 11f - motion.dirX * 3f, motion.y + 2.5f, motion.z + motion.dirX * 11f - motion.dirZ * 3f)
                camera.direction.set(motion.x, motion.y + 1.5f, motion.z).sub(camera.position).nor()
            }
            null -> {
                val cx = motion.x - motion.dirX * craft.cameraBack
                val cz = motion.z - motion.dirZ * craft.cameraBack
                val cy = maxOf(motion.y + craft.cameraUp, terrain.ground(cx, cz) + 2f)
                camera.position.set(cx, cy, cz)
                camera.direction.set(motion.x + motion.dirX * LOOK_AHEAD, motion.y + LOOK_UP, motion.z + motion.dirZ * LOOK_AHEAD)
                    .sub(camera.position).nor()
            }
        }
        camera.up.set(0f, 1f, 0f)
        camera.normalizeUp()
        camera.update()
        follow(farCamera)
        follow(seaCamera)
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
        sceneShader.setUniformf("u_groutTo", 0f, 0f, 0f)
    }

    private fun drawSky() {
        val w = viewWidth.toFloat()
        val h = viewHeight.toFloat()
        // Horizon: a point far ahead at eye height.
        tmp.set(farCamera.direction.x, 0f, farCamera.direction.z)
        if (tmp.len2() < 1e-6f) tmp.set(0f, 0f, -1f)
        tmp.nor().scl(4000f).add(farCamera.position)
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
        if (::tileMeshes.isInitialized) tileMeshes.forEach { it.dispose() }
        for (m in arrayOf(
            if (::shadowMesh.isInitialized) shadowMesh else null,
            if (::craftMesh.isInitialized) craftMesh else null,
            if (::buildingsMesh.isInitialized) buildingsMesh else null,
            if (::markingsMesh.isInitialized) markingsMesh else null,
            if (::cloudsMesh.isInitialized) cloudsMesh else null,
            if (::seaMesh.isInitialized) seaMesh else null,
            if (::shallowsMesh.isInitialized) shallowsMesh else null,
            if (::skyMesh.isInitialized) skyMesh else null,
        )) m?.dispose()
        if (::skyShader.isInitialized) skyShader.dispose()
        if (::sceneShader.isInitialized) sceneShader.dispose()
    }

    companion object {
        /** The close pass: the craft and the land within a few hundred metres. */
        const val NEAR_NEAR = 1f
        const val NEAR_FAR = 500f
        /** The distant pass starts well inside the close pass's reach, so the two overlap. */
        const val FAR_NEAR = 150f
        const val FAR_FAR = 40000f
        const val FOG_START = 500f
        const val FOG_END = 12000f
        const val FOG_MAX = 0.88f
        /** The camera looks at a point this far ahead of the craft and this far above its seat. */
        const val LOOK_AHEAD = 14f
        const val LOOK_UP = 1.6f
    }
}
