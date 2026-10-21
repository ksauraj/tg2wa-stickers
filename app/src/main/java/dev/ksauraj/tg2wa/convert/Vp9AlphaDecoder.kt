package dev.ksauraj.tg2wa.convert

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * Decodes a demuxed Telegram video sticker (VP9 + optional VP9 alpha stream) into
 * ARGB frames. Two MediaCodec instances run in lockstep: one for colour, one for the
 * alpha plane, whose luma channel *is* the alpha value.
 */
class Vp9AlphaDecoder(private val video: WebmDemuxer.Video) {

    init {
        require(video.codecId == "V_VP9" || video.codecId == "V_VP8") {
            "unsupported video codec ${video.codecId}"
        }
    }

    private val mime = if (video.codecId == "V_VP8") MediaFormat.MIMETYPE_VIDEO_VP8 else MediaFormat.MIMETYPE_VIDEO_VP9

    /**
     * Calls [onFrame] for each decoded frame in order. The bitmap is reused between
     * calls, so copy it if you need to keep it. Frames for which [needed] is false are
     * still decoded (VP9 frames depend on each other) but not colour-converted.
     */
    fun decode(needed: (index: Int) -> Boolean = { true }, onFrame: (index: Int, timeMs: Long, frame: Bitmap) -> Unit) {
        val w = video.width
        val h = video.height
        // Only trust the alpha stream when every frame carries one; otherwise treat as opaque.
        val withAlpha = video.frames.all { it.alpha != null && it.alpha.isNotEmpty() }
        val color = Stream(createCodec(), video.frames.map { it.data })
        val alpha = if (withAlpha) Stream(createCodec(), video.frames.map { it.alpha ?: ByteArray(0) }) else null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val argb = IntArray(w * h)
        val alphaPlane = if (withAlpha) ByteArray(w * h) else null
        try {
            color.start(w, h)
            alpha?.start(w, h)
            var next = 0
            while (next < video.frames.size) {
                val c = color.nextFrame(timeUs(next)) ?: break
                val a = alpha?.nextFrame(timeUs(next))
                val want = needed(next)
                try {
                    if (want) {
                        if (a != null && alphaPlane != null) copyLuma(a.image, w, h, alphaPlane)
                        yuvToArgb(c.image, w, h, if (a != null) alphaPlane else null, argb)
                    }
                } finally {
                    c.close()
                    a?.close()
                }
                if (want) {
                    bitmap.setPixels(argb, 0, w, 0, 0, w, h)
                    onFrame(next, video.frames[next].timeMs, bitmap)
                }
                next++
            }
            check(next > 0) { "decoder produced no frames" }
        } finally {
            color.release()
            alpha?.release()
            bitmap.recycle()
        }
    }

    private fun timeUs(i: Int) = video.frames[i].timeMs * 1000L

    private fun createCodec(): MediaCodec {
        // Prefer the platform software decoder: always present, handles small odd sizes.
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        val sorted = infos.sortedBy { if (isSoftware(it)) 0 else 1 }
        for (info in sorted) {
            try {
                return MediaCodec.createByCodecName(info.name)
            } catch (_: Exception) {
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun isSoftware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
        else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")

    /** Feeds one elementary stream and pulls decoded images back out in order. */
    private inner class Stream(val codec: MediaCodec, val packets: List<ByteArray>) {
        private var fed = 0
        private var inputDone = false
        private val info = MediaCodec.BufferInfo()

        fun start(w: Int, h: Int) {
            val format = MediaFormat.createVideoFormat(mime, w, h)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec.configure(format, null, null, 0)
            codec.start()
        }

        /** Returns the next decoded frame at or after [wantUs], or null at end of stream. */
        fun nextFrame(wantUs: Long): Decoded? {
            var idle = 0
            while (true) {
                if (!inputDone) feed()
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                if (idx >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (eos && info.size == 0) {
                        codec.releaseOutputBuffer(idx, false)
                        return null
                    }
                    if (info.presentationTimeUs < wantUs) { // stale frame, drop it
                        codec.releaseOutputBuffer(idx, false)
                        continue
                    }
                    val image = codec.getOutputImage(idx)
                    if (image == null) {
                        codec.releaseOutputBuffer(idx, false)
                        throw IllegalStateException("decoder gave no image output")
                    }
                    return Decoded(image) { codec.releaseOutputBuffer(idx, false) }
                } else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone && ++idle > 300) return null // ~3s with nothing coming out
                }
            }
        }

        private fun feed() {
            while (!inputDone) {
                val idx = codec.dequeueInputBuffer(0)
                if (idx < 0) return
                if (fed >= packets.size) {
                    codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                    return
                }
                val data = packets[fed]
                val buf = codec.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data)
                val flags = if (fed == 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                codec.queueInputBuffer(idx, 0, data.size, timeUs(fed), flags)
                fed++
            }
        }

        fun release() {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
    }

    private class Decoded(val image: Image, private val release: () -> Unit) {
        fun close() {
            image.close()
            release()
        }
    }

    // Plane bytes are copied out in one bulk get per plane: per-pixel ByteBuffer.get()
    // calls are several times slower on ART.
    private var yBuf = ByteArray(0)
    private var uBuf = ByteArray(0)
    private var vBuf = ByteArray(0)
    private var aBuf = ByteArray(0)

    private fun grab(plane: Image.Plane, cache: ByteArray): ByteArray {
        val src = plane.buffer.duplicate()
        src.position(0)
        val n = src.remaining()
        val dst = if (cache.size >= n) cache else ByteArray(n)
        src.get(dst, 0, n)
        return dst
    }

    private fun copyLuma(img: Image, w: Int, h: Int, out: ByteArray) {
        val crop = img.cropRect
        val y = img.planes[0]
        aBuf = grab(y, aBuf)
        val rs = y.rowStride
        val ps = y.pixelStride
        val buf = aBuf
        for (row in 0 until h) {
            val base = (row + crop.top) * rs + crop.left * ps
            if (ps == 1) {
                System.arraycopy(buf, base, out, row * w, w)
            } else {
                for (col in 0 until w) out[row * w + col] = buf[base + col * ps]
            }
        }
    }

    private fun yuvToArgb(img: Image, w: Int, h: Int, alpha: ByteArray?, out: IntArray) {
        val crop = img.cropRect
        val planes = img.planes
        val yP = planes[0]; val uP = planes[1]; val vP = planes[2]
        yBuf = grab(yP, yBuf); uBuf = grab(uP, uBuf); vBuf = grab(vP, vBuf)
        val yb = yBuf; val ub = uBuf; val vb = vBuf
        val yRs = yP.rowStride; val yPs = yP.pixelStride
        val uRs = uP.rowStride; val uPs = uP.pixelStride
        val vRs = vP.rowStride; val vPs = vP.pixelStride
        val left = crop.left
        for (row in 0 until h) {
            val yr = row + crop.top
            val yRow = yr * yRs
            val uRow = (yr shr 1) * uRs
            val vRow = (yr shr 1) * vRs
            val outRow = row * w
            for (col in 0 until w) {
                val yc = col + left
                val cc = yc shr 1
                val yy = (yb[yRow + yc * yPs].toInt() and 0xFF) - 16
                val u = (ub[uRow + cc * uPs].toInt() and 0xFF) - 128
                val v = (vb[vRow + cc * vPs].toInt() and 0xFF) - 128
                val c = 298 * (if (yy < 0) 0 else yy)
                val r = clamp((c + 409 * v + 128) shr 8)
                val g = clamp((c - 100 * u - 208 * v + 128) shr 8)
                val b = clamp((c + 516 * u + 128) shr 8)
                val a = if (alpha != null) alpha[outRow + col].toInt() and 0xFF else 0xFF
                out[outRow + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
}
