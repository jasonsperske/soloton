package dev.soloton.roadwork

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Matrix4
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Writes flat-shaded, vertex-coloured triangles into a preallocated float array
 * (position, face normal, packed colour), ready for `Mesh.setVertices`.
 *
 * Every triangle gets its own three vertices and its face normal, which is what makes the
 * look faceted. Triangles are counter-clockwise seen from the outside. Positions pass through
 * [transform] (rotation, translation, positive scale only). Nothing here allocates.
 */
class MeshWriter(val maxVertices: Int) {
    val floats = FloatArray(maxVertices * STRIDE)
    var vertexCount = 0
        private set

    /** Applied to every position written. Reset to identity by [reset]. */
    val transform = Matrix4()

    private val m = transform.`val`

    fun reset() {
        vertexCount = 0
        transform.idt()
    }

    val triangleCount: Int get() = vertexCount / 3

    // Scratch for transformed corners.
    private var ax = 0f; private var ay = 0f; private var az = 0f
    private var bx = 0f; private var by = 0f; private var bz = 0f
    private var cx = 0f; private var cy = 0f; private var cz = 0f

    fun tri(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        color: Float,
    ) {
        check(vertexCount + 3 <= maxVertices) { "MeshWriter full ($maxVertices vertices)" }
        ax = m[0] * x0 + m[4] * y0 + m[8] * z0 + m[12]
        ay = m[1] * x0 + m[5] * y0 + m[9] * z0 + m[13]
        az = m[2] * x0 + m[6] * y0 + m[10] * z0 + m[14]
        bx = m[0] * x1 + m[4] * y1 + m[8] * z1 + m[12]
        by = m[1] * x1 + m[5] * y1 + m[9] * z1 + m[13]
        bz = m[2] * x1 + m[6] * y1 + m[10] * z1 + m[14]
        cx = m[0] * x2 + m[4] * y2 + m[8] * z2 + m[12]
        cy = m[1] * x2 + m[5] * y2 + m[9] * z2 + m[13]
        cz = m[2] * x2 + m[6] * y2 + m[10] * z2 + m[14]
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 1e-12f) { nx /= len; ny /= len; nz /= len } else { nx = 0f; ny = 1f; nz = 0f }
        put(ax, ay, az, nx, ny, nz, color)
        put(bx, by, bz, nx, ny, nz, color)
        put(cx, cy, cz, nx, ny, nz, color)
    }

    private fun put(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, c: Float) {
        val i = vertexCount * STRIDE
        floats[i] = x; floats[i + 1] = y; floats[i + 2] = z
        floats[i + 3] = nx; floats[i + 4] = ny; floats[i + 5] = nz
        floats[i + 6] = c
        vertexCount++
    }

    /** Quad a-b-c-d, counter-clockwise from the visible side. */
    fun quad(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        color: Float,
    ) {
        tri(x0, y0, z0, x1, y1, z1, x2, y2, z2, color)
        tri(x0, y0, z0, x2, y2, z2, x3, y3, z3, color)
    }

    /** Horizontal rectangle facing up, spanning x0..x1 and z0..z1 at height y. */
    fun floor(x0: Float, x1: Float, z0: Float, z1: Float, y: Float, color: Float) {
        // Seen from above (+y), with x right and -z forward, x0z1 -> x1z1 -> x1z0 -> x0z0 is CCW.
        val nearZ = maxOf(z0, z1)
        val farZ = minOf(z0, z1)
        quad(x0, y, nearZ, x1, y, nearZ, x1, y, farZ, x0, y, farZ, color)
    }

    /** Axis-aligned box from (x0,y0,z0) to (x1,y1,z1) in local space. [bottom] = false skips the underside. */
    fun box(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        color: Float,
        bottom: Boolean = false,
    ) {
        // +z face (toward the default camera)
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, color)
        // -z face
        quad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, color)
        // +x face
        quad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, color)
        // -x face
        quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, color)
        // top
        quad(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, color)
        if (bottom) quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, color)
    }

    /** Box centred on (cx, cy, cz) with half sizes. */
    fun boxC(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float, color: Float, bottom: Boolean = true) =
        box(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz, color, bottom)

    /**
     * A square-section beam from point a to point b (local space), [w] wide and [h] tall, both ends capped.
     * The beam's "up" is world-ish up unless the beam is nearly vertical.
     */
    fun beam(
        ax0: Float, ay0: Float, az0: Float,
        bx0: Float, by0: Float, bz0: Float,
        w: Float, h: Float,
        color: Float,
    ) {
        var dx = bx0 - ax0; var dy = by0 - ay0; var dz = bz0 - az0
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-6f) return
        dx /= len; dy /= len; dz /= len
        // side = d x up (or d x forward when nearly vertical)
        var sx: Float; var sy: Float; var sz: Float
        if (kotlin.math.abs(dy) < 0.95f) {
            sx = -dz; sy = 0f; sz = dx // d x (0,1,0)
        } else {
            sx = 0f; sy = dz; sz = -dy // d x (1,0,0) -> (0, dz, -dy)
        }
        val sl = sqrt(sx * sx + sy * sy + sz * sz)
        sx /= sl; sy /= sl; sz /= sl
        // up2 = side x d
        val ux = sy * dz - sz * dy
        val uy = sz * dx - sx * dz
        val uz = sx * dy - sy * dx
        val hw = w * 0.5f; val hh = h * 0.5f
        // corners around a: (+s+u), (-s+u), (-s-u), (+s-u)
        val s0x = sx * hw; val s0y = sy * hw; val s0z = sz * hw
        val u0x = ux * hh; val u0y = uy * hh; val u0z = uz * hh
        // a corners
        val a1x = ax0 + s0x + u0x; val a1y = ay0 + s0y + u0y; val a1z = az0 + s0z + u0z
        val a2x = ax0 - s0x + u0x; val a2y = ay0 - s0y + u0y; val a2z = az0 - s0z + u0z
        val a3x = ax0 - s0x - u0x; val a3y = ay0 - s0y - u0y; val a3z = az0 - s0z - u0z
        val a4x = ax0 + s0x - u0x; val a4y = ay0 + s0y - u0y; val a4z = az0 + s0z - u0z
        val b1x = bx0 + s0x + u0x; val b1y = by0 + s0y + u0y; val b1z = bz0 + s0z + u0z
        val b2x = bx0 - s0x + u0x; val b2y = by0 - s0y + u0y; val b2z = bz0 - s0z + u0z
        val b3x = bx0 - s0x - u0x; val b3y = by0 - s0y - u0y; val b3z = bz0 - s0z - u0z
        val b4x = bx0 + s0x - u0x; val b4y = by0 + s0y - u0y; val b4z = bz0 + s0z - u0z
        // Sides: going around (+s+u) -> (-s+u) -> (-s-u) -> (+s-u). Outward winding: a_i, a_j, b_j, b_i
        // reversed when needed; we pick the order whose normal points away from the axis.
        side(a1x, a1y, a1z, b1x, b1y, b1z, b2x, b2y, b2z, a2x, a2y, a2z, ux, uy, uz, color)
        side(a2x, a2y, a2z, b2x, b2y, b2z, b3x, b3y, b3z, a3x, a3y, a3z, -sx, -sy, -sz, color)
        side(a3x, a3y, a3z, b3x, b3y, b3z, b4x, b4y, b4z, a4x, a4y, a4z, -ux, -uy, -uz, color)
        side(a4x, a4y, a4z, b4x, b4y, b4z, b1x, b1y, b1z, a1x, a1y, a1z, sx, sy, sz, color)
        side(a1x, a1y, a1z, a2x, a2y, a2z, a3x, a3y, a3z, a4x, a4y, a4z, -dx, -dy, -dz, color)
        side(b1x, b1y, b1z, b2x, b2y, b2z, b3x, b3y, b3z, b4x, b4y, b4z, dx, dy, dz, color)
    }

    /** Quad whose winding is chosen so its local-space normal agrees with (ox, oy, oz). */
    private fun side(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        ox: Float, oy: Float, oz: Float,
        color: Float,
    ) {
        val ux = x1 - x0; val uy = y1 - y0; val uz = z1 - z0
        val vx = x2 - x0; val vy = y2 - y0; val vz = z2 - z0
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        if (nx * ox + ny * oy + nz * oz >= 0f) {
            quad(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, color)
        } else {
            quad(x3, y3, z3, x2, y2, z2, x1, y1, z1, x0, y0, z0, color)
        }
    }

    /**
     * Upright frustum (prism when r0 == r1, cone when r1 == 0) with [sides] faces, base centre at
     * (x, y, z). The top is capped; the bottom is not (it sits on the ground).
     */
    fun prism(x: Float, y: Float, z: Float, r0: Float, r1: Float, height: Float, sides: Int, color: Float, phase: Float = 0f) {
        val step = (2 * PI / sides).toFloat()
        val top = y + height
        for (i in 0 until sides) {
            val a0 = phase + i * step
            val a1 = a0 + step
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            val p0x = x + r0 * c0; val p0z = z - r0 * s0
            val p1x = x + r0 * c1; val p1z = z - r0 * s1
            if (r1 > 0f) {
                val q0x = x + r1 * c0; val q0z = z - r1 * s0
                val q1x = x + r1 * c1; val q1z = z - r1 * s1
                quad(p0x, y, p0z, p1x, y, p1z, q1x, top, q1z, q0x, top, q0z, color)
                tri(x, top, z, q0x, top, q0z, q1x, top, q1z, color)
            } else {
                tri(p0x, y, p0z, p1x, y, p1z, x, top, z, color)
            }
        }
    }

    /** Low-poly ellipsoid: an icosahedron scaled by (rx, ry, rz), 20 faces. */
    fun icosa(x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float, color: Float) {
        for (f in 0 until 20) {
            val i0 = ICO_FACES[f * 3] * 3; val i1 = ICO_FACES[f * 3 + 1] * 3; val i2 = ICO_FACES[f * 3 + 2] * 3
            tri(
                x + ICO_V[i0] * rx, y + ICO_V[i0 + 1] * ry, z + ICO_V[i0 + 2] * rz,
                x + ICO_V[i1] * rx, y + ICO_V[i1 + 1] * ry, z + ICO_V[i1 + 2] * rz,
                x + ICO_V[i2] * rx, y + ICO_V[i2 + 1] * ry, z + ICO_V[i2 + 2] * rz,
                color,
            )
        }
    }

    companion object {
        /** Floats per vertex: position (3), normal (3), packed colour (1). */
        const val STRIDE = 7

        /** Packs a colour; [emissive] (0..1) goes in alpha and makes the face glow regardless of light. */
        fun pack(r: Float, g: Float, b: Float, emissive: Float = 0f): Float =
            Color.toFloatBits(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), emissive.coerceIn(0f, 1f))

        fun pack(c: Color, emissive: Float = 0f): Float = pack(c.r, c.g, c.b, emissive)

        private val T = ((1.0 + sqrt(5.0)) / 2.0).toFloat()
        private val N = 1f / sqrt(1f + T * T)
        private val ICO_V = floatArrayOf(
            -1f, T, 0f, 1f, T, 0f, -1f, -T, 0f, 1f, -T, 0f,
            0f, -1f, T, 0f, 1f, T, 0f, -1f, -T, 0f, 1f, -T,
            T, 0f, -1f, T, 0f, 1f, -T, 0f, -1f, -T, 0f, 1f,
        ).also { v -> for (i in v.indices) v[i] *= N }
        private val ICO_FACES = intArrayOf(
            0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11,
            1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1, 8,
            3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9,
            4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1,
        )
    }
}
