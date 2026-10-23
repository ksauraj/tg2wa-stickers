package dev.ksauraj.tg2wa

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ksauraj.tg2wa.convert.SourceFormat
import dev.ksauraj.tg2wa.convert.StickerConverter
import dev.ksauraj.tg2wa.convert.Vp9AlphaDecoder
import dev.ksauraj.tg2wa.convert.WebmDemuxer
import dev.ksauraj.tg2wa.convert.WebpMuxer
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Not a pass/fail test: times each stage on realistic stickers and logs to
 * logcat tag TG2WA_BENCH so CI can print it.
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkInstrumentedTest {
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val appCtx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(name: String): File {
        val out = File(appCtx.cacheDir, "bench-$name")
        testCtx.assets.open("fixtures/$name").use { i -> out.outputStream().use { i.copyTo(it) } }
        return out
    }

    private fun log(msg: String) = Log.i("TG2WA_BENCH", msg)

    private inline fun <T> timed(label: String, block: () -> T): T {
        val t0 = SystemClock.elapsedRealtime()
        val r = block()
        log("$label: ${SystemClock.elapsedRealtime() - t0} ms")
        return r
    }

    @Test
    fun benchmark() {
        log("device ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} cores=${Runtime.getRuntime().availableProcessors()}")
        val webm = fixture("real.webm")
        val video = timed("webm demux") { WebmDemuxer.parse(webm.readBytes()) }
        var frames = 0
        timed("webm decode only (${video.frames.size} src frames)") {
            Vp9AlphaDecoder(video).decode { _, _, _ -> frames++ }
        }
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        Vp9AlphaDecoder(video).decode { i, _, b -> if (i == 10) android.graphics.Canvas(bmp).drawBitmap(b, 0f, 0f, null) }
        for (q in intArrayOf(80, 50)) {
            val out = ByteArrayOutputStream()
            val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            val t0 = SystemClock.elapsedRealtime()
            repeat(10) { out.reset(); bmp.compress(fmt, q, out) }
            log("webp encode 1 frame q=$q: ${(SystemClock.elapsedRealtime() - t0) / 10.0} ms, ${out.size()} bytes")
        }
        val conv = StickerConverter()
        for ((name, fmt) in listOf("real.webm" to SourceFormat.WEBM, "real.tgs" to SourceFormat.TGS, "static.webp" to SourceFormat.WEBP)) {
            val f = fixture(name)
            val r = timed("convert $name") { conv.convert(f, fmt) }
            log("  -> ${r.bytes.size} bytes, ${WebpMuxer.frameDurations(r.bytes).size} frames")
        }
    }
}
