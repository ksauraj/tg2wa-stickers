package dev.ksauraj.tg2wa

import dev.ksauraj.tg2wa.convert.WebpMuxer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebpMuxerTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes()

    private val outDir = File("build/test-output").apply { mkdirs() }

    @Test
    fun parsesStillChunks() {
        assertEquals(listOf("VP8X", "ALPH", "VP8 "), WebpMuxer.parseChunks(fixture("frame_alpha.webp")).map { it.fourcc })
        assertEquals(listOf("VP8 "), WebpMuxer.parseChunks(fixture("frame_opaque.webp")).map { it.fourcc })
        assertEquals(listOf("VP8L"), WebpMuxer.parseChunks(fixture("frame_lossless.webp")).map { it.fourcc })
    }

    @Test
    fun muxesAnimatedWebp() {
        val frames = listOf(
            WebpMuxer.Frame(fixture("frame_alpha.webp"), 40),
            WebpMuxer.Frame(fixture("frame_opaque.webp"), 41),
            WebpMuxer.Frame(fixture("frame_lossless.webp"), 42),
        )
        val out = WebpMuxer.mux(frames, 512, 512)
        File(outDir, "muxed.webp").writeBytes(out)

        val chunks = WebpMuxer.parseChunks(out)
        assertEquals(listOf("VP8X", "ANIM", "ANMF", "ANMF", "ANMF"), chunks.map { it.fourcc })
        val vp8x = chunks[0].data
        assertEquals(0x12, vp8x[0].toInt()) // alpha + animation
        assertEquals(511, (vp8x[4].toInt() and 0xFF) or ((vp8x[5].toInt() and 0xFF) shl 8))
        assertEquals(listOf(40, 41, 42), WebpMuxer.frameDurations(out))
        // RIFF size covers the whole file
        val riff = (out[4].toInt() and 0xFF) or ((out[5].toInt() and 0xFF) shl 8) or
            ((out[6].toInt() and 0xFF) shl 16) or ((out[7].toInt() and 0xFF) shl 24)
        assertEquals(out.size - 8, riff)
        // every ANMF is "no blend, no dispose"
        chunks.drop(2).forEach { assertEquals(0x02, it.data[15].toInt()) }
    }

    @Test
    fun firstFrameAsStill() {
        val out = WebpMuxer.mux(listOf(WebpMuxer.Frame(fixture("frame_alpha.webp"), 50), WebpMuxer.Frame(fixture("frame_opaque.webp"), 50)), 512, 512)
        val still = WebpMuxer.firstFrameAsStill(out)
        File(outDir, "first_frame.webp").writeBytes(still)
        val chunks = WebpMuxer.parseChunks(still)
        assertEquals(listOf("VP8X", "ALPH", "VP8 "), chunks.map { it.fourcc })
        val original = WebpMuxer.parseChunks(fixture("frame_alpha.webp"))
        assertArrayEquals(original[2].data, chunks[2].data)
        assertTrue(WebpMuxer.frameDurations(still).isEmpty())
        // still images pass through untouched
        assertArrayEquals(fixture("frame_opaque.webp"), WebpMuxer.firstFrameAsStill(fixture("frame_opaque.webp")))
    }
}
