package dev.ksauraj.tg2wa

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ksauraj.tg2wa.store.PackStore
import dev.ksauraj.tg2wa.store.StoredPack
import dev.ksauraj.tg2wa.store.StoredSticker
import dev.ksauraj.tg2wa.whatsapp.StickerContentProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Checks the provider speaks WhatsApp's sticker contract. */
@RunWith(AndroidJUnit4::class)
class ProviderInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PackStore(ctx)
    private val base = "content://${StickerContentProvider.AUTHORITY}"

    @After
    fun cleanup() = store.clear()

    private fun seed(): StoredPack {
        val pack = StoredPack(
            id = "tg_1_s0", name = "Test pack", publisher = "Telegram @test", website = "https://t.me/addstickers/test",
            animated = false, version = 3, setId = 1, setKey = "k", tray = "tray.png",
            stickers = List(3) { StoredSticker("%02d.webp".format(it + 1), "\uD83D\uDE00") },
        )
        val dir = store.dir(pack.id).apply { mkdirs() }
        File(dir, "tray.png").writeBytes(ByteArray(10) { 1 })
        pack.stickers.forEach { File(dir, it.file).writeBytes(ByteArray(20) { 2 }) }
        File(dir, "secret.txt").writeText("not listed")
        store.replaceSet(1, listOf(pack))
        return pack
    }

    @Test
    fun servesMetadataStickersAndFiles() {
        seed()
        val cr = ctx.contentResolver
        cr.query(Uri.parse("$base/metadata"), null, null, null, null)!!.use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("tg_1_s0", c.getString(c.getColumnIndexOrThrow("sticker_pack_identifier")))
            assertEquals("Test pack", c.getString(c.getColumnIndexOrThrow("sticker_pack_name")))
            assertEquals("tray.png", c.getString(c.getColumnIndexOrThrow("sticker_pack_icon")))
            assertEquals("3", c.getString(c.getColumnIndexOrThrow("image_data_version")))
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("animated_sticker_pack")))
        }
        cr.query(Uri.parse("$base/metadata/tg_1_s0"), null, null, null, null)!!.use { assertEquals(1, it.count) }
        cr.query(Uri.parse("$base/metadata/nope"), null, null, null, null)!!.use { assertEquals(0, it.count) }
        cr.query(Uri.parse("$base/stickers/tg_1_s0"), null, null, null, null)!!.use { c ->
            assertEquals(3, c.count)
            c.moveToFirst()
            assertEquals("01.webp", c.getString(c.getColumnIndexOrThrow("sticker_file_name")))
        }
        cr.openAssetFileDescriptor(Uri.parse("$base/stickers_asset/tg_1_s0/01.webp"), "r")!!.use { assertEquals(20L, it.length) }
        cr.openAssetFileDescriptor(Uri.parse("$base/stickers_asset/tg_1_s0/tray.png"), "r")!!.use { assertEquals(10L, it.length) }
        assertEquals("image/webp", cr.getType(Uri.parse("$base/stickers_asset/tg_1_s0/01.webp")))
    }

    @Test
    fun refusesUnlistedFiles() {
        seed()
        val r = try {
            ctx.contentResolver.openAssetFileDescriptor(Uri.parse("$base/stickers_asset/tg_1_s0/secret.txt"), "r")
        } catch (e: java.io.FileNotFoundException) {
            null
        }
        assertNull(r)
        val r2 = try {
            ctx.contentResolver.openAssetFileDescriptor(Uri.parse("$base/stickers_asset/tg_1_s0/..%2F..%2Fsecure.xml"), "r")
        } catch (e: java.io.FileNotFoundException) {
            null
        }
        assertNull(r2)
    }
}
