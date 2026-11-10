package dev.soloton.roadwork

/**
 * Small deterministic random generator (SplitMix64). Reseedable without allocating, so a recycled
 * chunk can rebuild its contents from its index alone and the scene is identical on every run.
 */
class Rng(seed: Long = 0L) {
    private var state = seed

    fun reset(seed: Long) { state = seed }

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        return mix(state)
    }

    /** Uniform in [0, 1). */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() / (1L shl 24).toFloat()

    fun range(from: Float, until: Float): Float = from + (until - from) * nextFloat()

    fun nextInt(bound: Int): Int = ((nextLong() ushr 33) % bound).toInt()

    fun chance(p: Float): Boolean = nextFloat() < p

    companion object {
        fun mix(z0: Long): Long {
            var z = z0
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
            return z xor (z ushr 31)
        }

        /** Stable hash of two integers to [0, 1), for seamless noise across chunk boundaries. */
        fun hash01(a: Long, b: Long, seed: Long): Float =
            (mix(seed * 31 + a * 0x632BE59BD9B4E019L + b * 0x7F4A7C15L) ushr 40).toFloat() / (1L shl 24).toFloat()
    }
}
