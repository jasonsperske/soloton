package dev.soloton.music

import kotlin.random.Random

/**
 * Hands out every item once in random order, then reshuffles. The first item of a new cycle is never
 * the last item of the previous one (when there are at least two items), so no track plays twice in a row.
 * Not thread-safe: confine to one thread.
 */
class ShuffleBag<T>(items: List<T>, private val random: Random = Random.Default, justPlayed: T? = null) {
    private val items: List<T> = items.toList()
    private val order = ArrayList<T>(items.size)
    private var index = 0
    /** [justPlayed] is not handed out first, so replacing the bag mid-track does not repeat the track. */
    private var last: T? = justPlayed

    init {
        require(this.items.isNotEmpty()) { "ShuffleBag needs at least one item" }
        refill()
    }

    val size: Int get() = items.size

    fun next(): T {
        if (index >= order.size) refill()
        val item = order[index++]
        last = item
        return item
    }

    private fun refill() {
        order.clear()
        order.addAll(items)
        order.shuffle(random)
        val previous = last
        if (order.size > 1 && previous != null && order[0] == previous) {
            val swapWith = 1 + random.nextInt(order.size - 1)
            order[0] = order[swapWith]
            order[swapWith] = previous
        }
        index = 0
    }
}
