package dev.ksauraj.tg2wa.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import com.airbnb.lottie.AsyncUpdates
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class SourceFormat { WEBP, TGS, WEBM }

class ConversionResult(val bytes: ByteArray, val animated: Boolean)

/**
 * Turns a Telegram sticker file into a WhatsApp-valid sticker:
 *  - 512x512 canvas, aspect preserved, transparent padding;
 *  - static: WebP <= 100 KB;
 *  - animated (TGS/Lottie and WebM/VP9): animated WebP <= 500 KB, <= 10 s, frames >= 8 ms.
 * Size targets are reached by stepping quality and frame rate down.
 */
class StickerConverter {

    companion object {
        const val SIZE = 512
        const val TRAY_SIZE = 96
        const val STATIC_LIMIT = 100 * 1024
        const val ANIMATED_LIMIT = 500 * 1024
        const val TRAY_LIMIT = 50 * 1024
        const val MAX_DURATION_MS = 10_000L
        private const val SAFETY = 2 * 1024 // stay clear of the hard limits
        private val STATIC_QUALITY = intArrayOf(92, 85, 78, 70, 60, 50, 40, 30, 20, 10)
        // (fps, quality) ladder, best first.
        private val ANIMATED_LADDER = listOf(
            30 to 80, 25 to 75, 20 to 70, 20 to 55, 15 to 55, 15 to 40, 12 to 35, 10 to 30, 10 to 20, 8 to 15, 6 to 10,
        )
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun convert(src: File, format: SourceFormat): ConversionResult = when (format) {
        SourceFormat.WEBP -> ConversionResult(convertStatic(src), animated = false)
        SourceFormat.TGS -> ConversionResult(encodeAnimated(LottieSource(src)), animated = true)
        SourceFormat.WEBM -> ConversionResult(encodeAnimated(WebmSource(src)), animated = true)
    }

    /** 96x96 PNG tray icon from the first frame of a converted sticker. */
    fun trayIcon(stickerWebp: ByteArray): ByteArray {
        val still = WebpMuxer.firstFrameAsStill(stickerWebp)
        val decoded = BitmapFactory.decodeByteArray(still, 0, still.size)
            ?: throw IllegalArgumentException("cannot decode sticker for tray icon")
        val tray = Bitmap.createBitmap(TRAY_SIZE, TRAY_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(tray).drawBitmap(decoded, null, Rect(0, 0, TRAY_SIZE, TRAY_SIZE), paint)
        decoded.recycle()
        val out = ByteArrayOutputStream()
        tray.compress(Bitmap.CompressFormat.PNG, 100, out)
        var bytes = out.toByteArray()
        if (bytes.size > TRAY_LIMIT) { // practically impossible at 96px, but be strict
            val small = Bitmap.createScaledBitmap(tray, 64, 64, true)
            out.reset(); small.compress(Bitmap.CompressFormat.PNG, 100, out); bytes = out.toByteArray()
            small.recycle()
        }
        tray.recycle()
        return bytes
    }

    // ---- static -------------------------------------------------------------------------

    private fun convertStatic(src: File): ByteArray {
        val bmp = BitmapFactory.decodeFile(src.path) ?: throw IllegalArgumentException("cannot decode ${src.name}")
        val canvas = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        try {
            drawFit(Canvas(canvas), bmp)
            for (q in STATIC_QUALITY) {
                val bytes = webp(canvas, q)
                if (bytes.size <= STATIC_LIMIT - SAFETY) return bytes
            }
            throw IllegalStateException("static sticker stays above 100 KB")
        } finally {
            bmp.recycle(); canvas.recycle()
        }
    }

    // ---- animated -----------------------------------------------------------------------

    /** Something that can render itself at a chosen frame rate. */
    interface FrameSource {
        val durationMs: Long
        /** Render evenly spaced frames; [frame] is reused between calls. */
        fun render(fps: Int, onFrame: (frame: Bitmap) -> Unit)
    }

    private fun encodeAnimated(source: FrameSource): ByteArray {
        val duration = min(max(source.durationMs, 100L), MAX_DURATION_MS)
        var lastError: Throwable? = null
        for ((fps, quality) in ANIMATED_LADDER) {
            val count = max(2, (duration * fps / 1000.0).roundToInt())
            val budget = ANIMATED_LIMIT - SAFETY
            val frames = ArrayList<WebpMuxer.Frame>(count)
            var total = 0
            var tooBig = false
            try {
                val canvasBmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(canvasBmp)
                try {
                    source.render(fps) { frame ->
                        if (frames.size >= count) return@render
                        canvasBmp.eraseColor(Color.TRANSPARENT)
                        drawFit(canvas, frame)
                        val bytes = webp(canvasBmp, quality)
                        total += bytes.size + 24
                        if (total > budget) throw TooBig()
                        val i = frames.size
                        // Spread rounding so the sum of durations equals the clip length.
                        val dur = ((i + 1) * duration / count - i * duration / count).toInt()
                        frames += WebpMuxer.Frame(bytes, max(dur, 8))
                    }
                } finally {
                    canvasBmp.recycle()
                }
            } catch (_: TooBig) {
                tooBig = true
            } catch (e: Exception) {
                lastError = e
                break
            }
            if (tooBig) continue
            if (frames.isEmpty()) throw IllegalStateException("animation produced no frames")
            if (frames.size == 1) frames += WebpMuxer.Frame(frames[0].webp, frames[0].durationMs)
            val out = WebpMuxer.mux(frames, SIZE, SIZE)
            if (out.size <= budget) return out
        }
        throw IllegalStateException("animated sticker could not fit in 500 KB", lastError)
    }

    private class TooBig : RuntimeException()

    private class LottieSource(file: File) : FrameSource {
        private val composition: LottieComposition = GZIPInputStream(FileInputStream(file)).use { input ->
            val result = LottieCompositionFactory.fromJsonInputStreamSync(input, null)
            result.value ?: throw IllegalArgumentException("bad TGS animation", result.exception)
        }

        override val durationMs: Long = composition.duration.toLong()

        override fun render(fps: Int, onFrame: (frame: Bitmap) -> Unit) {
            val w = max(1, composition.bounds.width())
            val h = max(1, composition.bounds.height())
            val drawable = LottieDrawable()
            drawable.setAsyncUpdates(AsyncUpdates.DISABLED)
            drawable.setComposition(composition)
            drawable.setBounds(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val count = max(2, (min(durationMs, MAX_DURATION_MS) * fps / 1000.0).roundToInt())
            val span = min(1f, MAX_DURATION_MS.toFloat() / max(1L, durationMs))
            try {
                for (i in 0 until count) {
                    drawable.setProgress(i.toFloat() / count * span)
                    bmp.eraseColor(Color.TRANSPARENT)
                    drawable.draw(canvas)
                    onFrame(bmp)
                }
            } finally {
                bmp.recycle()
                drawable.clearComposition()
            }
        }
    }

    private class WebmSource(file: File) : FrameSource {
        private val video = WebmDemuxer.parse(file.readBytes())
        override val durationMs: Long = video.durationMs

        override fun render(fps: Int, onFrame: (frame: Bitmap) -> Unit) {
            val total = min(durationMs, MAX_DURATION_MS)
            val count = max(2, (total * fps / 1000.0).roundToInt())
            // For output slot j (time j*total/count) use the newest source frame at or before it.
            val times = video.frames.map { it.timeMs }
            val slotsFor = HashMap<Int, Int>() // source index -> number of slots it fills
            var src = 0
            for (j in 0 until count) {
                val t = j * total / count
                while (src + 1 < times.size && times[src + 1] <= t) src++
                slotsFor[src] = (slotsFor[src] ?: 0) + 1
            }
            Vp9AlphaDecoder(video).decode { index, _, bitmap ->
                repeat(slotsFor[index] ?: 0) { onFrame(bitmap) }
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun drawFit(canvas: Canvas, src: Bitmap) {
        val scale = min(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
        val w = (src.width * scale).roundToInt().coerceIn(1, SIZE)
        val h = (src.height * scale).roundToInt().coerceIn(1, SIZE)
        val left = (SIZE - w) / 2
        val top = (SIZE - h) / 2
        canvas.drawBitmap(src, null, Rect(left, top, left + w, top + h), paint)
    }

    private fun webp(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        check(bitmap.compress(format, quality, out)) { "WebP encode failed" }
        return out.toByteArray()
    }
}
