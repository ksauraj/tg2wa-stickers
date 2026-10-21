package dev.ksauraj.tg2wa.whatsapp

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dev.ksauraj.tg2wa.BuildConfig

/** Talking to WhatsApp: is it installed, is a pack already added, and the add intent. */
object WhatsApp {
    const val CONSUMER = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"
    private const val ACTION_ENABLE = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"

    fun installed(context: Context): List<String> = listOf(CONSUMER, BUSINESS).filter { isInstalled(context, it) }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** True when every installed WhatsApp already has this pack. */
    fun isAdded(context: Context, identifier: String): Boolean {
        val apps = installed(context)
        if (apps.isEmpty()) return false
        return apps.all { isAddedIn(context, it, identifier) }
    }

    private fun isAddedIn(context: Context, pkg: String, identifier: String): Boolean = try {
        val authority = "$pkg.provider.sticker_whitelist_check"
        if (context.packageManager.resolveContentProvider(authority, 0) == null) {
            false
        } else {
            val uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority)
                .appendPath("is_whitelisted")
                .appendQueryParameter("authority", BuildConfig.CONTENT_PROVIDER_AUTHORITY)
                .appendQueryParameter("identifier", identifier)
                .build()
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow("result")) == 1
            } ?: false
        }
    } catch (_: Exception) {
        false
    }

    fun addIntent(identifier: String, name: String, pkg: String?): Intent =
        Intent(ACTION_ENABLE).apply {
            putExtra("sticker_pack_id", identifier)
            putExtra("sticker_pack_authority", BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            putExtra("sticker_pack_name", name)
            if (pkg != null) setPackage(pkg)
        }
}
