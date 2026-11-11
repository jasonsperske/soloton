package dev.soloton.roadwork.env.skylift

import dev.soloton.roadwork.MeshWriter
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpaceRunnerModelTest {
    private val model = SpaceRunnerModel()
    private val w = MeshWriter(SpaceRunnerModel.MAX_VERTICES)

    private fun x(v: Int) = w.floats[v * MeshWriter.STRIDE]
    private fun y(v: Int) = w.floats[v * MeshWriter.STRIDE + 1]
    private fun z(v: Int) = w.floats[v * MeshWriter.STRIDE + 2]
    private fun lowest() = (0 until w.vertexCount).minOf { y(it) }
    private fun highest() = (0 until w.vertexCount).maxOf { y(it) }

    @Test
    fun theRunnerFitsItsBufferInEveryPose() {
        var most = 0
        for (altitude in listOf(0f, 0.5f, 1.5f, 2.5f, 12f, SkyliftMotion.MAX_ALTITUDE)) {
            for (speed in listOf(0f, 1.5f, 3f, 6f)) {
                for (step in 0 until 16) {
                    val stride = (2 * PI * step / 16).toFloat()
                    model.build(w, 1.3f, altitude, stride, speed, 12f, step * 0.37f) // throws if it overflows
                    assertEquals(0, w.vertexCount % 3)
                    most = maxOf(most, w.vertexCount)
                }
            }
        }
        assertTrue(most in 600..SpaceRunnerModel.MAX_VERTICES, "$most vertices at most")
    }

    @Test
    fun onTheGroundTheRunnerStandsOnItAndIsAboutTheHeightOfAPerson() {
        for (speed in listOf(0f, 3f, 6f)) {
            for (step in 0 until 16) {
                model.build(w, 0f, 0f, (2 * PI * step / 16).toFloat(), speed, 0f, 0f)
                assertTrue(lowest() > -0.06f, "speed $speed, step $step: a foot ${-lowest()} m under the ground")
                assertTrue(highest() in 1.6f..2.0f, "speed $speed, step $step: ${highest()} m tall")
            }
        }
        // Standing still, both feet are down and level.
        model.build(w, 0f, 0f, 1f, 0f, 0f, 0f)
        assertTrue(lowest() < 0.06f, "standing with the feet ${lowest()} m off the ground")
    }

    @Test
    fun theRunnerRunsBelowAndFliesFlatAbove() {
        assertEquals(0f, SpaceRunnerModel.flightFor(0f))
        assertEquals(1f, SpaceRunnerModel.flightFor(SpaceRunnerModel.FULL_FLIGHT_ALTITUDE))
        assertEquals(1f, SpaceRunnerModel.flightFor(30f))
        assertTrue(SpaceRunnerModel.flightFor(1f) in 0.1f..0.9f)

        // Running: upright, taller than long. Flying: tipped forward, longer than tall, and off the ground.
        model.build(w, 0f, 0f, 0.7f, 3f, 0f, 0f)
        val runBody = 3 * 10 until w.vertexCount // skip the ten shadow triangles
        val runHeight = runBody.maxOf { y(it) } - runBody.minOf { y(it) }
        val runLength = runBody.maxOf { z(it) } - runBody.minOf { z(it) }
        assertTrue(runHeight > runLength, "running: $runHeight m tall, $runLength m long")

        model.build(w, 0f, 20f, 0.7f, 3f, 0f, 0f)
        val flyBody = 3 * 10 until w.vertexCount
        val flyHeight = flyBody.maxOf { y(it) } - flyBody.minOf { y(it) }
        val flyLength = flyBody.maxOf { z(it) } - flyBody.minOf { z(it) }
        assertTrue(flyLength > flyHeight, "flying: $flyHeight m tall, $flyLength m long")
        assertTrue(flyBody.minOf { y(it) } > 19f, "flying at 20 m")
        // The head leads: it is the part furthest ahead (most negative z).
        assertTrue(flyBody.minOf { z(it) } < -0.5f)
    }

    @Test
    fun theShadowStaysOnTheGroundUnderTheRunnerAndShrinksWithHeight() {
        fun shadowWidth(altitude: Float): Float {
            model.build(w, 2f, altitude, 0f, 3f, 0f, 0f)
            val shadow = 0 until 30
            for (v in shadow) assertTrue(y(v) in 0f..0.05f, "shadow vertex at y ${y(v)}")
            assertEquals(2f, (shadow.maxOf { x(it) } + shadow.minOf { x(it) }) / 2f, 0.05f)
            return shadow.maxOf { x(it) } - shadow.minOf { x(it) }
        }
        assertTrue(shadowWidth(0f) > shadowWidth(10f) && shadowWidth(10f) > shadowWidth(40f))
    }

    @Test
    fun theRunnerMovesSidewaysWithTheSwerve() {
        model.build(w, -2.6f, 0f, 0f, 3f, 0f, 0f)
        val body = 3 * 10 until w.vertexCount
        assertTrue(body.maxOf { x(it) } < -2f && body.minOf { x(it) } > -3.2f)
    }
}
