package dev.soloton.roadwork

import kotlin.math.floor

/**
 * Which stretch of path each of a fixed number of chunk slots holds. Chunk `i` covers world
 * distance `[i * length, (i + 1) * length)`. As the runner moves on, slots that fall
 * [behindMeters] behind are handed the next index ahead, so the slot count never grows.
 */
class ChunkTrack(
    val length: Float,
    val slotCount: Int,
    private val behindMeters: Float = length,
) {
    init {
        require(length > 0f && slotCount >= 2)
    }

    /** Chunk index held by each slot; slots are not kept in any particular order. */
    val indices = LongArray(slotCount) { it - 1L }

    /** Lowest index held. */
    var firstIndex: Long = -1L
        private set

    /** Distance the slots reach ahead of the runner. */
    val reachAhead: Float get() = slotCount * length - behindMeters

    /**
     * Moves every slot that is now too far behind [distance] to the front. Calls [onRecycle]
     * with (slot, new index) for each recycled slot, so its geometry can be rebuilt.
     * Returns the number of slots recycled.
     */
    inline fun update(distance: Double, onRecycle: (slot: Int, index: Long) -> Unit): Int {
        val wantFirst = floor((distance - behindLength()) / length).toLong()
        if (wantFirst <= firstIndex) return 0
        var recycled = 0
        val oldFirst = firstIndex
        for (slot in 0 until slotCount) {
            val idx = indices[slot]
            if (idx < wantFirst) {
                // A long jump can skip whole chunks: lay out the new ones contiguously from wantFirst.
                val newIndex = if (wantFirst - oldFirst >= slotCount) wantFirst + recycled
                else idx + slotCount
                indices[slot] = newIndex
                onRecycle(slot, newIndex)
                recycled++
            }
        }
        setFirst(wantFirst)
        return recycled
    }

    @PublishedApi internal fun behindLength(): Float = behindMeters

    @PublishedApi internal fun setFirst(value: Long) { firstIndex = value }
}
