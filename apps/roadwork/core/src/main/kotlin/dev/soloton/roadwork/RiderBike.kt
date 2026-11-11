package dev.soloton.roadwork

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the rider's own bike ([BikeOverlay]) over a finished scene: in eye space, with a cleared
 * depth buffer, lit by the scene's [SceneLight] and never fogged. The same in every environment.
 */
internal class RiderBike {
    private val overlay = BikeOverlay()
    private lateinit var shader: ShaderProgram
    private lateinit var mesh: Mesh
    private lateinit var writer: MeshWriter
    private lateinit var camera: PerspectiveCamera

    fun create() {
        shader = Shaders.compile(Shaders.SCENE_VERTEX, Shaders.SCENE_FRAGMENT)
        writer = MeshWriter(BikeOverlay.MAX_VERTICES)
        mesh = SceneMeshes.create(false, BikeOverlay.MAX_VERTICES)
        camera = PerspectiveCamera(BikeOverlay.FOV, 16f, 9f).apply {
            near = BikeOverlay.NEAR; far = BikeOverlay.FAR
            val pitch = Math.toRadians(BikeOverlay.PITCH_DEGREES.toDouble())
            position.set(0f, 0f, 0f)
            direction.set(0f, -sin(pitch).toFloat(), -cos(pitch).toFloat())
            up.set(0f, cos(pitch).toFloat(), -sin(pitch).toFloat())
        }
    }

    fun resize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()
    }

    fun draw(ride: RideState, light: SceneLight) {
        val gl = Gdx.gl
        // Whatever the scene left behind: depth on and writable, back faces culled, no blending.
        gl.glEnable(GL20.GL_DEPTH_TEST)
        gl.glDepthFunc(GL20.GL_LEQUAL)
        gl.glDepthMask(true)
        gl.glEnable(GL20.GL_CULL_FACE)
        gl.glCullFace(GL20.GL_BACK)
        gl.glDisable(GL20.GL_BLEND)
        gl.glClear(GL20.GL_DEPTH_BUFFER_BIT)

        overlay.build(writer, ride.wheelAngle, ride.crankAngle, ride.speed,
            RideState.sway(ride.crankAngle, ride.speed), RideState.bob(ride.crankAngle, ride.speed))
        SceneMeshes.upload(mesh, writer)

        shader.bind()
        shader.setUniformf("u_sunDir", light.sunDirection)
        shader.setUniformf("u_sunColor", light.sunColor.r, light.sunColor.g, light.sunColor.b)
        shader.setUniformf("u_ambSky", light.ambientSky.r, light.ambientSky.g, light.ambientSky.b)
        shader.setUniformf("u_ambGround", light.ambientGround.r, light.ambientGround.g, light.ambientGround.b)
        // No fog, no grout fade, no water sheen: the bike is at arm's length.
        shader.setUniformf("u_fogColor", 0f, 0f, 0f)
        shader.setUniformf("u_groutTo", 0f, 0f, 0f)
        shader.setUniformf("u_water", 0f)
        shader.setUniformf("u_fog", 1e6f, 1f, 0f)
        shader.setUniformf("u_detail", 0f, 0f, 1e9f, 0f)
        shader.setUniformMatrix("u_projView", camera.combined)
        shader.setUniformf("u_camPos", 0f, 0f, 0f)
        shader.setUniformf("u_offset", 0f, 0f, 0f)
        mesh.render(shader, GL20.GL_TRIANGLES, 0, writer.vertexCount)
    }

    fun dispose() {
        if (::mesh.isInitialized) mesh.dispose()
        if (::shader.isInitialized) shader.dispose()
    }
}
