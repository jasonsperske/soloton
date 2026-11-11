package dev.soloton.roadwork.env.pedalcraft

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One flight: from a take-off run at [start], round the island, to a landing at [end]. Pure maths.
 *
 * The path is a smooth curve through the start of the strip, a point beyond its far end, a ring of
 * points circling the island at about [orbit] times the distance of the shore, a point on the
 * approach, the touchdown point and the place the craft rolls to a stop. It is stored as points
 * [SPACING] metres apart, so a distance along the route looks up a position directly.
 *
 * Height has two parts. [cruise] is the route's own height above the sea: the highest ground near
 * the path, smoothed, plus [clearance]. [airborne] is how much of that applies: 0 on the take-off
 * run and after touchdown, rising to 1 over [CLIMB] metres and falling over [DESCENT] metres.
 * [heightAt] puts them together with whatever extra lift the rider is adding.
 *
 * @param startSign,endSign +1 to take off (land) along the spot's strip direction, -1 against it
 * @param clockwise the way round the island, seen from above with north up
 * @param targetLength the route is given as many laps as bring it nearest this length, in metres
 * @param wobble varies the ring's radius, so routes that share an orbit do not share a path
 */
class FlightRoute(
    private val terrain: IslandTerrain,
    val start: Spot,
    startSign: Float,
    val end: Spot,
    endSign: Float,
    clockwise: Boolean,
    val orbit: Float,
    targetLength: Float,
    val clearance: Float,
    wobble: Float,
) {
    private val xs: FloatArray
    private val zs: FloatArray
    private val cruiseHeights: FloatArray
    private val floorsAhead: FloatArray

    /** Length of the route in metres. */
    val length: Float

    /** Distance along the route at which the wheels leave the ground, and at which they touch down. */
    val liftOff: Float
    val touchdown: Float

    init {
        val sx = start.dirX * startSign
        val sz = start.dirZ * startSign
        val ex = end.dirX * endSign
        val ez = end.dirZ * endSign
        val px = ArrayList<Float>()
        val pz = ArrayList<Float>()
        fun add(x: Float, z: Float) { px += x; pz += z }

        add(start.x - sx * start.run, start.z - sz * start.run)
        val outX = start.x + sx * (start.run + CLIMB_OUT)
        val outZ = start.z + sz * (start.run + CLIMB_OUT)
        add(outX, outZ)

        val inX = end.x - ex * (end.run + APPROACH)
        val inZ = end.z - ez * (end.run + APPROACH)
        val turn = if (clockwise) 1f else -1f
        val from = atan2(outZ, outX)
        var sweep = (atan2(inZ, inX) - from) * turn
        while (sweep < 0f) sweep += TWO_PI
        while (sweep >= TWO_PI) sweep -= TWO_PI
        if (sweep < 0.6f) sweep += TWO_PI
        val laps = ((targetLength / (orbit * IslandTerrain.COAST) - sweep) / TWO_PI).roundToInt().coerceAtLeast(0)
        sweep += laps * TWO_PI
        val steps = ceil(sweep / RING_STEP).toInt().coerceAtLeast(3)
        for (k in 1 until steps) {
            val a = from + turn * sweep * k / steps
            val r = orbit * terrain.coastRadius(a) * (1f + 0.12f * sin(k * 0.9f + wobble))
            add(r * cos(a), r * sin(a))
        }
        add(inX, inZ)
        val touchIndex = px.size
        add(end.x - ex * end.run, end.z - ez * end.run)
        add(end.x + ex * end.run * STOP_SHARE, end.z + ez * end.run * STOP_SHARE)

        // A smooth curve through the points, as a fine polyline. Centripetal Catmull-Rom: unlike the
        // uniform kind it cannot overshoot or double back where a short span follows a long one,
        // as the landing roll does after the approach. Each end gets a mirrored extra point.
        val n = px.size
        val fineX = ArrayList<Float>()
        val fineZ = ArrayList<Float>()
        var touchFine = 0
        fun pointX(i: Int) = when { i < 0 -> 2f * px[0] - px[1]; i >= n -> 2f * px[n - 1] - px[n - 2]; else -> px[i] }
        fun pointZ(i: Int) = when { i < 0 -> 2f * pz[0] - pz[1]; i >= n -> 2f * pz[n - 1] - pz[n - 2]; else -> pz[i] }
        fun knot(a: Int, b: Int): Float {
            val dx = pointX(b) - pointX(a)
            val dz = pointZ(b) - pointZ(a)
            return sqrt(sqrt(dx * dx + dz * dz)).coerceAtLeast(1e-3f)
        }
        for (i in 0 until n - 1) {
            if (i == touchIndex) touchFine = fineX.size
            val t0 = 0f
            val t1 = t0 + knot(i - 1, i)
            val t2 = t1 + knot(i, i + 1)
            val t3 = t2 + knot(i + 1, i + 2)
            for (j in 0 until SUBDIVISIONS) {
                val t = t1 + (t2 - t1) * j / SUBDIVISIONS
                fineX += centripetal(pointX(i - 1), pointX(i), pointX(i + 1), pointX(i + 2), t0, t1, t2, t3, t)
                fineZ += centripetal(pointZ(i - 1), pointZ(i), pointZ(i + 1), pointZ(i + 2), t0, t1, t2, t3, t)
            }
        }
        fineX += px[n - 1]
        fineZ += pz[n - 1]

        val along = FloatArray(fineX.size)
        for (i in 1 until fineX.size) {
            val dx = fineX[i] - fineX[i - 1]
            val dz = fineZ[i] - fineZ[i - 1]
            along[i] = along[i - 1] + sqrt(dx * dx + dz * dz)
        }
        length = along.last()
        touchdown = along[touchFine]
        liftOff = start.run * (1f + LIFT_OFF_SHARE)

        // The same curve as points a fixed distance apart.
        val count = (length / SPACING).toInt() + 2
        xs = FloatArray(count)
        zs = FloatArray(count)
        var seg = 0
        for (i in 0 until count) {
            val s = minOf(i * SPACING, length)
            while (seg < along.size - 2 && along[seg + 1] < s) seg++
            val span = along[seg + 1] - along[seg]
            val t = if (span > 0f) ((s - along[seg]) / span).coerceIn(0f, 1f) else 0f
            xs[i] = fineX[seg] + (fineX[seg + 1] - fineX[seg]) * t
            zs[i] = fineZ[seg] + (fineZ[seg + 1] - fineZ[seg]) * t
        }

        // Cruising height: the highest ground near each point, looking along the path and a little
        // to each side; then smoothed; then never less than the ground right here; plus clearance.
        val near = FloatArray(count)
        for (i in 0 until count) {
            val j = minOf(i + 1, count - 1)
            val k = maxOf(j - 1, 0)
            var dx = xs[j] - xs[k]
            var dz = zs[j] - zs[k]
            val len = sqrt(dx * dx + dz * dz)
            if (len > 0f) { dx /= len; dz /= len }
            var top = terrain.ground(xs[i], zs[i])
            top = maxOf(top, terrain.ground(xs[i] - dz * SIDE_LOOK, zs[i] + dx * SIDE_LOOK))
            top = maxOf(top, terrain.ground(xs[i] + dz * SIDE_LOOK, zs[i] - dx * SIDE_LOOK))
            near[i] = top
        }
        // The highest ground on the path itself over the next stretch, for not flying into a rise.
        // On the way down it looks no further than the touchdown point, so it ends at the strip.
        val touchPoint = (touchdown / SPACING).toInt()
        floorsAhead = FloatArray(count)
        for (i in 0 until count) {
            val last = if (i <= touchPoint) minOf(i + AHEAD_POINTS, touchPoint) else i
            var top = terrain.ground(xs[i], zs[i])
            for (j in i + 1..minOf(last, count - 1)) top = maxOf(top, terrain.ground(xs[j], zs[j]))
            floorsAhead[i] = top
        }
        val highest = FloatArray(count)
        for (i in 0 until count) {
            var top = 0f
            for (j in maxOf(i - LOOK_POINTS, 0)..minOf(i + LOOK_POINTS, count - 1)) top = maxOf(top, near[j])
            highest[i] = top
        }
        cruiseHeights = FloatArray(count)
        for (i in 0 until count) {
            var sum = 0f
            var samples = 0
            for (j in maxOf(i - SMOOTH_POINTS, 0)..minOf(i + SMOOTH_POINTS, count - 1)) { sum += highest[j]; samples++ }
            cruiseHeights[i] = maxOf(sum / samples, near[i]) + clearance
        }
    }

    private fun index(s: Float): Float = (s.coerceIn(0f, length) / SPACING).coerceAtMost(xs.size - 1.001f)

    fun x(s: Float): Float { val f = index(s); val i = f.toInt(); return xs[i] + (xs[i + 1] - xs[i]) * (f - i) }

    fun z(s: Float): Float { val f = index(s); val i = f.toInt(); return zs[i] + (zs[i + 1] - zs[i]) * (f - i) }

    /** Compass-free heading at [s]: the angle of the direction of travel, in radians from east toward south. */
    fun heading(s: Float): Float {
        val a = (s - 6f).coerceIn(0f, length - 12f)
        return atan2(z(a + 12f) - z(a), x(a + 12f) - x(a))
    }

    /** The route's own height above the sea at [s], before any lift from the rider. */
    fun cruise(s: Float): Float { val f = index(s); val i = f.toInt(); return cruiseHeights[i] + (cruiseHeights[i + 1] - cruiseHeights[i]) * (f - i) }

    /** 0 on the ground at either end, 1 in full flight. */
    fun airborne(s: Float): Float = minOf(
        IslandTerrain.smooth((s - liftOff) / CLIMB),
        IslandTerrain.smooth((touchdown - s) / DESCENT),
    )

    /**
     * Height above the sea at [s] of a craft whose seat rides [seat] metres above its wheels, with
     * [lift] extra metres asked for by the rider.
     *
     * Between the strips it blends from the strip's height up to [cruise] and back by [airborne].
     * It is never lower than the ground under it, and in the air it also stays above the ground
     * coming up over the next [AHEAD] metres: all of it on the way down, so an approach over
     * higher land holds its height until the strip is clear ahead, and growing with the climb
     * on the way up, so a take-off still starts on the ground.
     */
    fun heightAt(s: Float, lift: Float, seat: Float): Float {
        val w = airborne(s)
        val strip = if (s < length / 2f) start.height else end.height
        val flown = strip + seat + (cruise(s) + lift - strip - seat) * w
        val under = terrain.ground(x(s), z(s))
        val floor = when {
            s >= touchdown -> under
            s > touchdown - DESCENT -> maxOf(floorAhead(s), under)
            else -> under + (maxOf(floorAhead(s), under) - under) * minOf(1f, w * 4f)
        }
        return maxOf(flown, floor + seat + MIN_CLEARANCE * w)
    }

    private fun floorAhead(s: Float): Float { val f = index(s); val i = f.toInt(); return maxOf(floorsAhead[i], floorsAhead[i + 1]) }

    companion object {
        const val SPACING = 10f

        /** Past the end of the strip, the path runs straight on this far before turning for the island. */
        const val CLIMB_OUT = 350f

        /** The path lines up with the strip this far before the touchdown point. */
        const val APPROACH = 1000f

        /** The climb from lift-off to cruising height, and the descent to touchdown, in metres of travel. */
        const val CLIMB = 450f
        const val DESCENT = 900f

        /** The wheels leave the ground this share of the way from the middle of the strip to its end. */
        const val LIFT_OFF_SHARE = 0.6f

        /** After touchdown at the near end the craft rolls this share of the way past the middle. */
        const val STOP_SHARE = 0.6f

        /** A craft in full flight is never closer to the ground under it than this. */
        const val MIN_CLEARANCE = 12f
        /** How far ahead along the path [heightAt] looks for rising ground. */
        const val AHEAD = 200f
        private const val AHEAD_POINTS = (AHEAD / SPACING).toInt()
        private const val RING_STEP = 0.5f
        private const val SUBDIVISIONS = 24
        private const val SIDE_LOOK = 80f
        private const val LOOK_POINTS = 30
        private const val SMOOTH_POINTS = 40
        private const val TWO_PI = (2 * PI).toFloat()

        /** One coordinate of a centripetal Catmull-Rom curve between p1 (at knot t1) and p2 (at t2). */
        private fun centripetal(p0: Float, p1: Float, p2: Float, p3: Float, t0: Float, t1: Float, t2: Float, t3: Float, t: Float): Float {
            val a1 = (t1 - t) / (t1 - t0) * p0 + (t - t0) / (t1 - t0) * p1
            val a2 = (t2 - t) / (t2 - t1) * p1 + (t - t1) / (t2 - t1) * p2
            val a3 = (t3 - t) / (t3 - t2) * p2 + (t - t2) / (t3 - t2) * p3
            val b1 = (t2 - t) / (t2 - t0) * a1 + (t - t0) / (t2 - t0) * a2
            val b2 = (t3 - t) / (t3 - t1) * a2 + (t - t1) / (t3 - t1) * a3
            return (t2 - t) / (t2 - t1) * b1 + (t - t1) / (t2 - t1) * b2
        }
    }
}
