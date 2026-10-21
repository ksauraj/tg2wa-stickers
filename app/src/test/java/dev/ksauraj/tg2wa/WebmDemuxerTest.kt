package dev.ksauraj.tg2wa

import dev.ksauraj.tg2wa.convert.WebmDemuxer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebmDemuxerTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes()

    @Test
    fun readsVp9WithAlphaStream() {
        val v = WebmDemuxer.parse(fixture("alpha.webm"))
        assertEquals("V_VP9", v.codecId)
        assertEquals(512, v.width)
        assertEquals(512, v.height)
        assertEquals(60, v.frames.size)
        assertTrue("every frame carries an alpha stream", v.frames.all { it.alpha?.isNotEmpty() == true })
        assertEquals(0L, v.frames.first().timeMs)
        assertTrue(v.frames.zipWithNext().all { (a, b) -> b.timeMs > a.timeMs })
        assertTrue("duration ${v.durationMs}", v.durationMs in 1950..2050)
    }

    @Test
    fun readsNonSquareSticker() {
        val v = WebmDemuxer.parse(fixture("wide.webm"))
        assertEquals(512, v.width)
        assertEquals(384, v.height)
        assertEquals(45, v.frames.size)
        assertTrue(v.hasAlpha)
        assertTrue("duration ${v.durationMs}", v.durationMs in 1450..1550)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGarbage() {
        WebmDemuxer.parse(ByteArray(64) { 0x42 })
    }
}
