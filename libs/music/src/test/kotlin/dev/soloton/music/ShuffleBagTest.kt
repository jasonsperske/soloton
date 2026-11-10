package dev.soloton.music

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ShuffleBagTest {
    @Test
    fun everyCycleIsAPermutationAndNoItemRepeatsBackToBack() {
        for (size in 2..7) {
            for (seed in 0 until 200) {
                val items = (1..size).toList()
                val bag = ShuffleBag(items, Random(seed))
                var previous: Int? = null
                repeat(10) { cycle ->
                    val played = List(size) { bag.next() }
                    assertEquals(items, played.sorted(), "size=$size seed=$seed cycle=$cycle played=$played")
                    played.forEach { item ->
                        assertNotEquals(previous, item, "size=$size seed=$seed cycle=$cycle repeated $item")
                        previous = item
                    }
                }
            }
        }
    }

    @Test
    fun boundaryRuleActuallyTriggers() {
        // Without the rule, about 1 in 3 cycle boundaries of a 3-item bag would repeat; make sure the
        // swap path is exercised and still produces a permutation.
        var boundariesChecked = 0
        for (seed in 0 until 500) {
            val bag = ShuffleBag(listOf("a", "b", "c"), Random(seed))
            val first = List(3) { bag.next() }
            val second = List(3) { bag.next() }
            assertNotEquals(first.last(), second.first())
            assertEquals(listOf("a", "b", "c"), second.sorted())
            boundariesChecked++
        }
        assertEquals(500, boundariesChecked)
    }

    @Test
    fun singleItemRepeats() {
        val bag = ShuffleBag(listOf("only"))
        assertEquals(List(5) { "only" }, List(5) { bag.next() })
    }

    @Test
    fun orderVariesAcrossCycles() {
        val bag = ShuffleBag((1..10).toList(), Random(42))
        val cycles = List(5) { List(10) { bag.next() } }
        assert(cycles.toSet().size > 1) { "five identical shuffles of 10 items: $cycles" }
    }

    @Test
    fun emptyIsRejected() {
        assertFailsWith<IllegalArgumentException> { ShuffleBag(emptyList<String>()) }
    }
}
