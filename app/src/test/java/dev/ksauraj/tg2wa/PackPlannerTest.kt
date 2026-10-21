package dev.ksauraj.tg2wa

import dev.ksauraj.tg2wa.store.ConvertedSticker
import dev.ksauraj.tg2wa.store.PackPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PackPlannerTest {
    private fun stickers(n: Int, animated: Boolean = false) =
        List(n) { ConvertedSticker(File("s$it.webp"), animated, "x") }

    @Test
    fun splitsEvenly() {
        assertEquals(listOf(30), PackPlanner.split(List(30) { it }).map { it.size })
        assertEquals(listOf(16, 15), PackPlanner.split(List(31) { it }).map { it.size })
        assertEquals(listOf(30, 30), PackPlanner.split(List(60) { it }).map { it.size })
        assertEquals(listOf(21, 20, 20), PackPlanner.split(List(61) { it }).map { it.size })
        assertEquals(listOf(30, 30, 30, 30), PackPlanner.split(List(120) { it }).map { it.size })
    }

    @Test
    fun padsTinySets() {
        val packs = PackPlanner.plan(1, "Tiny", stickers(1))
        assertEquals(1, packs.size)
        assertEquals(3, packs[0].stickers.size)
    }

    @Test
    fun separatesAnimatedFromStatic() {
        val packs = PackPlanner.plan(-5, "Mixed", stickers(4, animated = true) + stickers(35))
        assertEquals(3, packs.size)
        assertTrue(packs[0].animated)
        assertTrue(packs.drop(1).none { it.animated })
        assertEquals(listOf("Mixed 1/3", "Mixed 2/3", "Mixed 3/3"), packs.map { it.name })
        assertEquals("tg_18446744073709551611_a0", packs[0].id)
        packs.forEach { p ->
            assertTrue(p.stickers.size in 3..30)
            assertTrue(p.id.matches(Regex("[\\w-.,'\\s]+")))
        }
    }

    @Test
    fun keepsSingleNameForSmallSets() {
        val packs = PackPlanner.plan(7, "Cats", stickers(12))
        assertEquals(listOf("Cats"), packs.map { it.name })
        assertEquals("tg_7_s0", packs[0].id)
    }

    @Test
    fun fillsMissingEmoji() {
        val p = PackPlanner.plan(1, "E", List(3) { ConvertedSticker(File("a"), false, " ") })
        assertTrue(p[0].stickers.all { it.emoji == PackPlanner.FALLBACK_EMOJI })
    }
}
