package dev.ksauraj.tg2wa.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.ksauraj.tg2wa.BuildConfig
import dev.ksauraj.tg2wa.store.PackStore
import dev.ksauraj.tg2wa.store.StoredPack

/**
 * The contract WhatsApp uses to read third-party sticker packs
 * (github.com/WhatsApp/stickers). Read-only, guarded by com.whatsapp.sticker.READ.
 *
 *   content://<authority>/metadata                  all packs
 *   content://<authority>/metadata/<id>             one pack
 *   content://<authority>/stickers/<id>             stickers of a pack
 *   content://<authority>/stickers_asset/<id>/<f>   image bytes
 */
class StickerContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = BuildConfig.CONTENT_PROVIDER_AUTHORITY
        const val METADATA = "metadata"
        const val STICKERS = "stickers"
        const val STICKERS_ASSET = "stickers_asset"

        val PACK_COLUMNS = arrayOf(
            "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher", "sticker_pack_icon",
            "android_play_store_link", "ios_app_download_link", "sticker_pack_publisher_email",
            "sticker_pack_publisher_website", "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website", "image_data_version",
            "whatsapp_will_not_cache_stickers", "animated_sticker_pack",
        )
        val STICKER_COLUMNS = arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text")
    }

    private lateinit var store: PackStore

    override fun onCreate(): Boolean {
        store = PackStore(context!!)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        checkAuthority(uri)
        val seg = uri.pathSegments
        val cursor = when {
            seg.size == 1 && seg[0] == METADATA -> packCursor(store.load())
            seg.size == 2 && seg[0] == METADATA -> packCursor(listOfNotNull(store.find(seg[1])))
            seg.size == 2 && seg[0] == STICKERS -> stickerCursor(store.find(seg[1]))
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        checkAuthority(uri)
        require(mode == "r") { "read-only provider" }
        val seg = uri.pathSegments
        if (seg.size != 3 || seg[0] != STICKERS_ASSET) return null
        val file = store.resolveAsset(seg[1], seg[2]) ?: return null
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    override fun getType(uri: Uri): String {
        val seg = uri.pathSegments
        return when {
            seg.size == 1 && seg[0] == METADATA -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$METADATA"
            seg.size == 2 && seg[0] == METADATA -> "vnd.android.cursor.item/vnd.$AUTHORITY.$METADATA"
            seg.size == 2 && seg[0] == STICKERS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$STICKERS"
            seg.size == 3 && seg[0] == STICKERS_ASSET && seg[2].endsWith(".png") -> "image/png"
            seg.size == 3 && seg[0] == STICKERS_ASSET -> "image/webp"
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    private fun packCursor(packs: List<StoredPack>): MatrixCursor {
        val c = MatrixCursor(PACK_COLUMNS)
        for (p in packs) {
            c.addRow(arrayOf<Any?>(
                p.id, p.name, p.publisher, p.tray,
                "", "", "", p.website, "", "",
                p.version.toString(), 0, if (p.animated) 1 else 0,
            ))
        }
        return c
    }

    private fun stickerCursor(pack: StoredPack?): MatrixCursor {
        val c = MatrixCursor(STICKER_COLUMNS)
        pack?.stickers?.forEach { c.addRow(arrayOf<Any?>(it.file, it.emoji, null)) }
        return c
    }

    private fun checkAuthority(uri: Uri) {
        require(uri.authority == AUTHORITY) { "wrong authority" }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
