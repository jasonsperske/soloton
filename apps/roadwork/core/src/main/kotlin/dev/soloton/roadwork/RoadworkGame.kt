package dev.soloton.roadwork

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Vector3

/**
 * Placeholder scene from the WP1 scaffold: a flat-shaded cube spinning in front of a dawn
 * gradient sky. WP3 replaces it with the roadwork scene.
 *
 * Contract used by the launchers (keep it when replacing the scene):
 * - [render] advances simulated time by real frame time in fixed steps, then draws.
 * - [simulateTo] advances simulated time to an absolute second deterministically (screenshot mode).
 * - [draw] draws the current state without advancing time.
 */
class RoadworkGame : ApplicationAdapter() {
    private val timestep = FixedTimestep(stepSeconds = 1f / 60f)

    private lateinit var camera: PerspectiveCamera
    private lateinit var skyCamera: OrthographicCamera
    private lateinit var shapes: ShapeRenderer
    private lateinit var batch: ModelBatch
    private lateinit var environment: Environment
    private lateinit var cubeModel: Model
    private lateinit var cube: ModelInstance

    private var angleDegrees = START_ANGLE_DEGREES
    private val spinAxis = Vector3(0.3f, 1f, 0.15f).nor()

    val simulatedSeconds: Double get() = timestep.simulatedSeconds

    override fun create() {
        camera = PerspectiveCamera(55f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat()).apply {
            position.set(0f, 1.2f, 4f)
            lookAt(0f, 0f, 0f)
            near = 0.1f
            far = 100f
            update()
        }
        skyCamera = OrthographicCamera()
        shapes = ShapeRenderer()
        batch = ModelBatch()
        environment = Environment().apply {
            set(ColorAttribute(ColorAttribute.AmbientLight, 0.40f, 0.36f, 0.45f, 1f))
            add(DirectionalLight().set(1f, 0.85f, 0.7f, 0.5f, -0.6f, -0.7f))
        }
        // createBox gives each face its own normal, so lighting is flat per face.
        cubeModel = ModelBuilder().createBox(
            1.6f, 1.6f, 1.6f,
            Material(ColorAttribute.createDiffuse(CUBE_COLOR)),
            (Usage.Position or Usage.Normal).toLong(),
        )
        cube = ModelInstance(cubeModel)
        resize(Gdx.graphics.width, Gdx.graphics.height)
        update(0f)
    }

    override fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()
        skyCamera.setToOrtho(false, width.toFloat(), height.toFloat())
    }

    override fun render() {
        timestep.advance(Gdx.graphics.deltaTime) { update(it) }
        draw()
    }

    fun simulateTo(seconds: Double) {
        timestep.advanceTo(seconds) { update(it) }
    }

    private fun update(dt: Float) {
        angleDegrees = (angleDegrees + SPIN_DEGREES_PER_SECOND * dt) % 360f
        cube.transform.setToRotation(spinAxis, angleDegrees)
    }

    fun draw() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        shapes.projectionMatrix = skyCamera.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        val w = skyCamera.viewportWidth
        val h = skyCamera.viewportHeight
        // rect colours: bottom-left, bottom-right, top-right, top-left
        shapes.rect(0f, 0f, w, h, SKY_HORIZON, SKY_HORIZON, SKY_ZENITH, SKY_ZENITH)
        shapes.end()

        batch.begin(camera)
        batch.render(cube, environment)
        batch.end()
    }

    override fun dispose() {
        batch.dispose()
        shapes.dispose()
        cubeModel.dispose()
    }

    private companion object {
        const val SPIN_DEGREES_PER_SECOND = 40f
        const val START_ANGLE_DEGREES = 35f
        val SKY_ZENITH = Color(0.20f, 0.24f, 0.45f, 1f)
        val SKY_HORIZON = Color(0.98f, 0.70f, 0.50f, 1f)
        val CUBE_COLOR = Color(0.30f, 0.62f, 0.58f, 1f)
    }
}
