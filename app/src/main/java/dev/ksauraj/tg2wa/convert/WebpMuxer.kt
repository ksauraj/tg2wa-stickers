package dev.ksauraj.tg2wa.convert

import java.io.ByteArrayOutputStream

/**
 * Minimal animated-WebP writer.
 *
 * Android can only *encode* still WebP images (Bitmap.compress). This takes those
 * single-frame files, pulls out their bitstream chunks (ALPH + VP8, or VP8L) and wraps
 * them in ANMF frames under a VP8X/ANIM header, per the WebP container spec.
 * Pure Kotlin, no native code.
 */
object WebpMuxer {

    class Frame(val webp: ByteArray, val durationMs: Int)

    data class Chunk(val fourcc: String, val data: ByteArray)

    fun parseChunks(webp: ByteArray): List<Chunk> {
        require(webp.size >= 12) { "not a WebP file: too short" }
        require(ascii(webp, 0) == "RIFF" && ascii(webp, 8) == "WEBP") { "not a RIFF/WEBP file" }
        val riffEnd = minOf(webp.size, 8 + le32(webp, 4))
        val out = ArrayList<Chunk>()
        var i = 12
        while (i + 8 <= riffEnd) {
            val fourcc = ascii(webp, i)
            val size = le32(webp, i + 4)
            val start = i + 8
            require(size >= 0 && start + size <= webp.size) { "truncated chunk $fourcc" }
            out += Chunk(fourcc, webp.copyOfRange(start, start + size))
            i = start + size + (size and 1)
        }
        return out
    }

    /** Builds an infinitely looping animation; every frame must be [width]x[height]. */
    fun mux(frames: List<Frame>, width: Int, height: Int): ByteArray {
        require(frames.isNotEmpty()) { "no frames" }
        require(width in 1..16384 && height in 1..16384)
        val body = ByteArrayOutputStream()
        // VP8X: flags = alpha (0x10) | animation (0x02)
        writeChunk(body, "VP8X", byteArrayOf(0x12, 0, 0, 0) + u24(width - 1) + u24(height - 1))
        // ANIM: background colour (transparent) + loop count 0 (= forever)
        writeChunk(body, "ANIM", byteArrayOf(0, 0, 0, 0, 0, 0))
        for (frame in frames) {
            val payload = ByteArrayOutputStream()
            payload.write(u24(0)) // x offset / 2
            payload.write(u24(0)) // y offset / 2
            payload.write(u24(width - 1))
            payload.write(u24(height - 1))
            payload.write(u24(frame.durationMs.coerceIn(1, 0xFFFFFF)))
            // flags: bit1 = do NOT blend (frames are full-canvas with their own alpha), bit0 = no dispose
            payload.write(0x02)
            var hasImage = false
            for (c in parseChunks(frame.webp)) {
                when (c.fourcc) {
                    "ALPH" -> writeChunk(payload, c.fourcc, c.data)
                    "VP8 ", "VP8L" -> { writeChunk(payload, c.fourcc, c.data); hasImage = true }
                }
            }
            require(hasImage) { "frame has no VP8/VP8L bitstream" }
            writeChunk(body, "ANMF", payload.toByteArray())
        }
        val bytes = body.toByteArray()
        val out = ByteArrayOutputStream(bytes.size + 12)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le32Bytes(4 + bytes.size))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        return out.toByteArray()
    }

    /**
     * First frame of an animated WebP as a still WebP (for tray icons / previews).
     * Still images are returned unchanged.
     */
    fun firstFrameAsStill(webp: ByteArray): ByteArray {
        val anmf = parseChunks(webp).firstOrNull { it.fourcc == "ANMF" } ?: return webp
        val d = anmf.data
        val w = u24At(d, 6) + 1
        val h = u24At(d, 9) + 1
        val frameChunks = d.copyOfRange(16, d.size) // already-framed ALPH/VP8/VP8L chunks
        val body = ByteArrayOutputStream()
        writeChunk(body, "VP8X", byteArrayOf(0x10, 0, 0, 0) + u24(w - 1) + u24(h - 1))
        body.write(frameChunks)
        val bytes = body.toByteArray()
        val out = ByteArrayOutputStream(bytes.size + 12)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le32Bytes(4 + bytes.size))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        return out.toByteArray()
    }

    /** Frame durations of an animated WebP, or empty for a still image. */
    fun frameDurations(webp: ByteArray): List<Int> =
        parseChunks(webp).filter { it.fourcc == "ANMF" }.map { u24At(it.data, 12) }

    private fun writeChunk(out: ByteArrayOutputStream, fourcc: String, data: ByteArray) {
        out.write(fourcc.toByteArray(Charsets.US_ASCII))
        out.write(le32Bytes(data.size))
        out.write(data)
        if (data.size and 1 == 1) out.write(0)
    }

    private fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)
    private fun le32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
    private fun u24At(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16)
    private fun le32Bytes(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun u24(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())
}
