package dev.ksauraj.tg2wa

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ksauraj.tg2wa.convert.SourceFormat
import dev.ksauraj.tg2wa.convert.StickerConverter
import dev.ksauraj.tg2wa.convert.WebpMuxer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the real conversion pipeline (Bitmap, Lottie, MediaCodec VP9) on an emulator. */
@RunWith(AndroidJUnit4::class)
class ConverterInstrumentedTest {
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
    private val converter = StickerConverter()

    private fun fixture(name: String): File {
        val out = File(appCtx.cacheDir, "fx-$name")
        testCtx.assets.open("fixtures/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun firstFrame(webp: ByteArray): Bitmap {
        val still = WebpMuxer.firstFrameAsStill(webp)
        return BitmapFactory.decodeByteArray(still, 0, still.size)!!
    }

    private fun assertWhatsAppAnimated(bytes: ByteArray) {
        assertTrue("size ${bytes.size}", bytes.size <= StickerConverter.ANIMATED_LIMIT)
        val durations = WebpMuxer.frameDurations(bytes)
        assertTrue("frames ${durations.size}", durations.size >= 2)
        assertTrue(durations.all { it >= 8 })
        assertTrue(durations.sum() <= 10_000)
        val vp8x = WebpMuxer.parseChunks(bytes).first().data
        val w = ((vp8x[4].toInt() and 0xFF) or ((vp8x[5].toInt() and 0xFF) shl 8) or ((vp8x[6].toInt() and 0xFF) shl 16)) + 1
        val h = ((vp8x[7].toInt() and 0xFF) or ((vp8x[8].toInt() and 0xFF) shl 8) or ((vp8x[9].toInt() and 0xFF) shl 16)) + 1
        assertEquals(512, w)
        assertEquals(512, h)
    }

    private fun assertColor(bmp: Bitmap, x: Int, y: Int, r: Int, g: Int, b: Int) {
        val p = bmp.getPixel(x, y)
        val msg = "pixel($x,$y)=#${Integer.toHexString(p)}"
        assertTrue(msg, Color.alpha(p) > 230)
        assertTrue(msg, kotlin.math.abs(Color.red(p) - r) < 50 && kotlin.math.abs(Color.green(p) - g) < 50 && kotlin.math.abs(Color.blue(p) - b) < 50)
    }

    private fun assertTransparent(bmp: Bitmap, x: Int, y: Int) {
        assertTrue("pixel($x,$y) alpha=${Color.alpha(bmp.getPixel(x, y))}", Color.alpha(bmp.getPixel(x, y)) < 25)
    }

    @Test
    fun staticWebpIsPaddedTo512() {
        val r = converter.convert(fixture("static.webp"), SourceFormat.WEBP)
        assertFalse(r.animated)
        assertTrue(r.bytes.size <= StickerConverter.STATIC_LIMIT)
        assertTrue(WebpMuxer.frameDurations(r.bytes).isEmpty())
        val bmp = BitmapFactory.decodeByteArray(r.bytes, 0, r.bytes.size)!!
        assertEquals(512, bmp.width)
        assertEquals(512, bmp.height)
        // source is 512x340: 86px transparent band at the top, green ellipse in the middle
        assertTransparent(bmp, 256, 40)
        assertColor(bmp, 256, 256, 0, 200, 0)
    }

    @Test
    fun tgsBecomesAnimatedWebp() {
        val r = converter.convert(fixture("anim.tgs"), SourceFormat.TGS)
        assertTrue(r.animated)
        assertWhatsAppAnimated(r.bytes)
        val total = WebpMuxer.frameDurations(r.bytes).sum()
        assertTrue("duration $total", total in 2900..3100)
        val f0 = firstFrame(r.bytes)
        assertColor(f0, 128, 256, 255, 0, 0) // circle starts at (128,256)
        assertTransparent(f0, 5, 5)
        assertTransparent(f0, 400, 256)
    }

    @Test
    fun webmAlphaIsPreserved() {
        val r = converter.convert(fixture("alpha.webm"), SourceFormat.WEBM)
        assertTrue(r.animated)
        assertWhatsAppAnimated(r.bytes)
        val f0 = firstFrame(r.bytes)
        assertColor(f0, 95, 255, 0, 0, 255) // blue box at x=20..170, y=180..330
        assertTransparent(f0, 5, 5)
        assertTransparent(f0, 400, 400)
    }

    @Test
    fun nonSquareWebmIsLetterboxed() {
        val r = converter.convert(fixture("wide.webm"), SourceFormat.WEBM)
        assertWhatsAppAnimated(r.bytes)
        val f0 = firstFrame(r.bytes)
        assertTransparent(f0, 256, 30) // 64px transparent band on top
        assertColor(f0, 95, 175 + 64, 255, 200, 0)
    }

    @Test
    fun trayIconIsSmallPng() {
        val r = converter.convert(fixture("anim.tgs"), SourceFormat.TGS)
        val tray = converter.trayIcon(r.bytes)
        assertTrue(tray.size <= StickerConverter.TRAY_LIMIT)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(tray, 0, tray.size, opts)
        assertEquals("image/png", opts.outMimeType)
        assertEquals(96, opts.outWidth)
    }
}
