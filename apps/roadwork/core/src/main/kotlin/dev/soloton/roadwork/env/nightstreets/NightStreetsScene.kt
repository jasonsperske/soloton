package dev.soloton.roadwork.env.nightstreets

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
import kotlin.math.floor

/**
 * A bike ride through a city at night. The rider keeps to the bike lane and at each junction
 * goes straight on, left or right at random ([CityRider]); cars drive the streets round them,
 * stopping for the lights and for the rider ([CityTraffic]). The view is from the saddle, with
 * the handlebars.
 *
 * The city is endless: it is built a cell at a time ([CityBlocks]) for the [REACH] cells each
 * way round the rider, a few each frame as the rider moves on. Everything is drawn relative to
 * the rider, so positions stay small however far the ride goes.
 *
 * Draw order per frame: sky with the moon; the distant skyline (no depth); the cells ahead and
 * around, the cars and the traffic lights (fresh depth). The game then draws the handlebars and
 * the HUD.
 */
class NightStreetsScene(private val seed: Long) : EnvironmentScene {
    private val rider = CityRider(seed)
    private val traffic = CityTraffic(seed)
    private val sky = NightSky()
    private val rng = Rng(seed)

    /** A development camera looking down on the rider's neighbourhood. */
    private var overhead = false

    override val light: SceneLight get() = sky
    override val inspectionViews: List<String> = listOf(OVERHEAD)
    override val riderView: Boolean get() = !overhead

    private lateinit var camera: PerspectiveCamera
    private lateinit var farCamera: PerspectiveCamera
    private lateinit var sceneShader: ShaderProgram
    private lateinit var skyShader: ShaderProgram
    private lateinit var skyMesh: Mesh
    private lateinit var skylineMesh: Mesh
    private lateinit var carsMesh: Mesh
    private lateinit var signalsMesh: Mesh
    private lateinit var cellMeshes: Array<Mesh>
    private val cellVertices = IntArray(SLOTS * SLOTS)

    /** Which cell each slot holds; a slot is cell (i, j)'s when both match. */
    private val cellI = IntArray(SLOTS * SLOTS) { Int.MIN_VALUE }
    private val cellJ = IntArray(SLOTS * SLOTS) { Int.MIN_VALUE }
    private var skylineVertices = 0
    private lateinit var writer: MeshWriter
    private lateinit var carsWriter: MeshWriter
    private lateinit var signalsWriter: MeshWriter

    private var viewWidth = 1
    private var viewHeight = 1
    private val tmp = Vector3()

    override fun create(ride: RideState) {
        traffic.populate(rider)

        sceneShader = Shaders.compile(Shaders.SCENE_VERTEX, Shaders.SCENE_FRAGMENT)
        skyShader = Shaders.compile(Shaders.SKY_VERTEX, Shaders.SKY_FRAGMENT)

        skyMesh = Mesh(true, 4, 0, VertexAttribute(Usage.Position, 2, "a_position"))
        skyMesh.setVertices(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f))

        writer = MeshWriter(maxOf(CityBlocks.CELL_MAX_VERTICES, CityBlocks.SKYLINE_MAX_VERTICES))
        CityBlocks.buildSkyline(writer, seed, rng)
        skylineVertices = writer.vertexCount
        skylineMesh = SceneMeshes.static(writer)

        cellMeshes = Array(SLOTS * SLOTS) { SceneMeshes.create(false, CityBlocks.CELL_MAX_VERTICES) }
        loadCells(Int.MAX_VALUE)

        carsWriter = MeshWriter(CityBlocks.CARS_MAX_VERTICES)
        carsMesh = SceneMeshes.create(false, CityBlocks.CARS_MAX_VERTICES)
        signalsWriter = MeshWriter(CityBlocks.SIGNALS_MAX_VERTICES)
        signalsMesh = SceneMeshes.create(false, CityBlocks.SIGNALS_MAX_VERTICES)

        camera = PerspectiveCamera(RiderCamera.FOV, 16f, 9f).apply { near = 0.3f; far = NEAR_FAR }
        farCamera = PerspectiveCamera(RiderCamera.FOV, 16f, 9f).apply { near = 100f; far = 3000f }
    }

    /** The cell the rider is in: the one whose block lies south-east of the node at its corner. */
    private fun riderCell(v: Float): Int = floor((v + CityGrid.HALF_STREET) / CityGrid.PERIOD).toInt()

    /**
     * Builds up to [limit] of the cells round the rider that are not yet held. Cell (i, j) always
     * goes in slot (i mod [SLOTS], j mod [SLOTS]), so the cells within reach never share a slot and
     * a slot is rebuilt only when its cell has gone out of reach and another comes in.
     */
    private fun loadCells(limit: Int) {
        val ri = riderCell(rider.x)
        val rj = riderCell(rider.z)
        // Riding on brings one row or column into reach at a time. More missing than that means
        // the rider has jumped (a long pause, a screenshot at a later time): build them all now
        // rather than leave the rider in an empty city for a second.
        var missing = 0
        for (j in rj - REACH..rj + REACH) for (i in ri - REACH..ri + REACH) {
            val slot = Math.floorMod(i, SLOTS) * SLOTS + Math.floorMod(j, SLOTS)
            if (cellI[slot] != i || cellJ[slot] != j) missing++
        }
        if (missing == 0) return
        @Suppress("NAME_SHADOWING")
        val limit = if (missing > 2 * SLOTS) Int.MAX_VALUE else limit
        var built = 0
        for (j in rj - REACH..rj + REACH) for (i in ri - REACH..ri + REACH) {
            val slot = Math.floorMod(i, SLOTS) * SLOTS + Math.floorMod(j, SLOTS)
            if (cellI[slot] == i && cellJ[slot] == j) continue
            if (built >= limit) return
            CityBlocks.buildCell(writer, i, j, seed, rng)
            cellVertices[slot] = writer.vertexCount
            SceneMeshes.upload(cellMeshes[slot], writer)
            cellI[slot] = i
            cellJ[slot] = j
            built++
        }
    }

    override fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewWidth = width
        viewHeight = height
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        farCamera.viewportWidth = width.toFloat()
        farCamera.viewportHeight = height.toFloat()
    }

    override fun step(dt: Float, ride: RideState) {
        rider.step(dt, ride.worldSpeed)
        traffic.step(dt, rider)
    }

    override fun setInspectionView(name: String?): Boolean {
        if (name != null && name != OVERHEAD) return false
        overhead = name != null
        return true
    }

    override fun draw(ride: RideState) {
        loadCells(CELLS_PER_FRAME)
        placeCameras(ride)

        val gl = Gdx.gl
        gl.glDepthMask(true)
        gl.glClearColor(sky.horizon.r, sky.horizon.g, sky.horizon.b, 1f)
        gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        // 1. Sky and moon.
        gl.glDisable(GL20.GL_DEPTH_TEST)
        gl.glDepthMask(false)
        gl.glDisable(GL20.GL_CULL_FACE)
        gl.glDisable(GL20.GL_BLEND)
        drawSky()

        // 2. The far skyline, painter's order, no depth, hazed but never lost.
        gl.glEnable(GL20.GL_CULL_FACE)
        gl.glCullFace(GL20.GL_BACK)
        sceneShader.bind()
        sceneShader.setUniformf("u_sunDir", sky.sunDirection)
        sceneShader.setUniformf("u_sunColor", sky.moonColor.r, sky.moonColor.g, sky.moonColor.b)
        sceneShader.setUniformf("u_ambSky", sky.cityAmbientSky.r, sky.cityAmbientSky.g, sky.cityAmbientSky.b)
        sceneShader.setUniformf("u_ambGround", sky.cityAmbientGround.r, sky.cityAmbientGround.g, sky.cityAmbientGround.b)
        sceneShader.setUniformf("u_fogColor", sky.horizon.r, sky.horizon.g, sky.horizon.b)
        sceneShader.setUniformf("u_groutTo", 0f, 0f, 0f)
        sceneShader.setUniformf("u_detail", 0f, 0f, 1e9f, 0f)
        sceneShader.setUniformf("u_water", 0f)
        sceneShader.setUniformf("u_fog", 0f, 1f / SKYLINE_FOG_END, SKYLINE_FOG_MAX)
        sceneShader.setUniformMatrix("u_projView", farCamera.combined)
        sceneShader.setUniformf("u_camPos", farCamera.position)
        sceneShader.setUniformf("u_offset", 0f, 0f, 0f)
        skylineMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, skylineVertices)

        // 3. The city round the rider, with depth: cells, then cars and traffic lights.
        gl.glEnable(GL20.GL_DEPTH_TEST)
        gl.glDepthFunc(GL20.GL_LEQUAL)
        gl.glDepthMask(true)
        gl.glClear(GL20.GL_DEPTH_BUFFER_BIT)
        // The overhead camera is for looking at the layout, so it sees through the haze.
        sceneShader.setUniformf("u_fog", FOG_START, 1f / (FOG_END - FOG_START), if (overhead) 0f else 1f)
        sceneShader.setUniformMatrix("u_projView", camera.combined)
        sceneShader.setUniformf("u_camPos", camera.position)
        val fx = CityGrid.headingX(rider.heading)
        val fz = CityGrid.headingZ(rider.heading)
        for (slot in cellMeshes.indices) {
            if (cellVertices[slot] == 0 || cellI[slot] == Int.MIN_VALUE) continue
            val ox = cellI[slot] * CityGrid.PERIOD - rider.x
            val oz = cellJ[slot] * CityGrid.PERIOD - rider.z
            // Skip cells behind the rider or off to the side of the view (the overhead camera sees them all).
            // A cell is judged by its middle, with its half-diagonal allowed for both ways.
            if (!overhead) {
                val ahead = (ox + CityGrid.HALF) * fx + (oz + CityGrid.HALF) * fz
                val aside = kotlin.math.abs(-(ox + CityGrid.HALF) * fz + (oz + CityGrid.HALF) * fx)
                if (ahead < -CELL_REACH || aside > VIEW_SLOPE * (ahead + CELL_REACH) + CELL_REACH) continue
            }
            sceneShader.setUniformf("u_offset", ox, 0f, oz)
            cellMeshes[slot].render(sceneShader, GL20.GL_TRIANGLES, 0, cellVertices[slot])
        }
        sceneShader.setUniformf("u_offset", 0f, 0f, 0f)
        CityBlocks.buildCars(carsWriter, traffic, rider.x, rider.z)
        if (carsWriter.vertexCount > 0) {
            SceneMeshes.upload(carsMesh, carsWriter)
            carsMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, carsWriter.vertexCount)
        }
        CityBlocks.buildSignals(signalsWriter, rider.nodeX, rider.nodeZ, traffic.time, seed, rider.x, rider.z)
        SceneMeshes.upload(signalsMesh, signalsWriter)
        signalsMesh.render(sceneShader, GL20.GL_TRIANGLES, 0, signalsWriter.vertexCount)
    }

    /**
     * The rider's eyes ([RiderCamera]), turned to the rider's heading and leaning a little into a
     * turn. The rider is the origin of everything drawn, so only the direction changes.
     */
    private fun placeCameras(ride: RideState) {
        if (overhead) {
            camera.position.set(0f, 150f, 95f)
            camera.direction.set(0f, -150f, -95f).nor()
            camera.up.set(0f, 1f, 0f)
        } else {
            RiderCamera.place(camera, ride)
            val fx = CityGrid.headingX(rider.heading)
            val fz = CityGrid.headingZ(rider.heading)
            // Right of the way the rider faces: right of east is south.
            val rx = -fz
            val rz = fx
            val p = camera.position
            p.set(rx * p.x - fx * p.z, p.y, rz * p.x - fz * p.z)
            val d = camera.direction
            d.set(rx * d.x - fx * d.z, d.y, rz * d.x - fz * d.z)
            val lean = (rider.turnRate * LEAN_PER_TURN).coerceIn(-MAX_LEAN, MAX_LEAN)
            val u = camera.up
            val side = u.x + lean
            u.set(rx * side, u.y, rz * side)
        }
        camera.normalizeUp()
        camera.update()
        farCamera.position.set(camera.position)
        farCamera.direction.set(camera.direction)
        farCamera.up.set(camera.up)
        farCamera.update()
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
        val moonAhead = sky.sunDirection.dot(farCamera.direction) > 0.05f
        tmp.set(sky.sunDirection).scl(4000f).add(farCamera.position)
        farCamera.project(tmp, 0f, 0f, w, h)
        skyShader.bind()
        skyShader.setUniformf("u_zenith", sky.zenith.r, sky.zenith.g, sky.zenith.b)
        skyShader.setUniformf("u_horizon", sky.horizon.r, sky.horizon.g, sky.horizon.b)
        skyShader.setUniformf("u_sunColor", sky.moonGlow.r, sky.moonGlow.g, sky.moonGlow.b)
        skyShader.setUniformf("u_geom", horizonY, 1f / h, tmp.x, tmp.y)
        skyShader.setUniformf("u_sun", if (moonAhead) 1f else 0f)
        skyMesh.render(skyShader, GL20.GL_TRIANGLE_FAN, 0, 4)
    }

    override fun dispose() {
        if (::cellMeshes.isInitialized) cellMeshes.forEach { it.dispose() }
        if (::signalsMesh.isInitialized) signalsMesh.dispose()
        if (::carsMesh.isInitialized) carsMesh.dispose()
        if (::skylineMesh.isInitialized) skylineMesh.dispose()
        if (::skyMesh.isInitialized) skyMesh.dispose()
        if (::skyShader.isInitialized) skyShader.dispose()
        if (::sceneShader.isInitialized) sceneShader.dispose()
    }

    companion object {
        const val OVERHEAD = "overhead"

        /** Cells are kept this many each way from the rider's, in a square of [SLOTS] by [SLOTS]. */
        const val REACH = 2
        const val SLOTS = 2 * REACH + 1

        /** New cells built per frame as the rider moves on; a row of them comes into reach at once. */
        const val CELLS_PER_FRAME = 2

        /**
         * The city fades into the night haze from [FOG_START] and is gone by [FOG_END], which is no
         * further than the nearest edge of the cells held: a cell is never seen arriving.
         */
        const val FOG_START = 60f
        const val FOG_END = 200f
        const val NEAR_FAR = 380f

        /** Half the diagonal of a cell, and the slope of the edge of the view (a little wider than the camera's). */
        const val CELL_REACH = 71f
        const val VIEW_SLOPE = 1.25f
        const val SKYLINE_FOG_END = 1700f
        const val SKYLINE_FOG_MAX = 0.62f
        const val LEAN_PER_TURN = 0.05f
        const val MAX_LEAN = 0.07f
    }
}
