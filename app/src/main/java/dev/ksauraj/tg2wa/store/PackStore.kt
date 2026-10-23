package dev.ksauraj.tg2wa.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StoredSticker(val file: String, val emoji: String)

data class StoredPack(
    val id: String,
    val name: String,
    val publisher: String,
    val website: String,
    val animated: Boolean,
    val version: Int,
    val setId: Long,
    val setKey: String,
    val tray: String,
    val stickers: List<StoredSticker>,
)

/**
 * Converted packs live in filesDir/packs/<id>/ with a JSON index next to them.
 * Shared by the UI, the sync worker and the ContentProvider WhatsApp reads from.
 */
class PackStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "packs")
    private val index = File(root, "index.json")

    fun dir(id: String) = File(root, id)

    @Synchronized
    fun load(): List<StoredPack> {
        if (!index.exists()) return emptyList()
        return try {
            val arr = JSONObject(index.readText()).getJSONArray("packs")
            (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun find(id: String): StoredPack? = load().firstOrNull { it.id == id }

    fun packsForSet(setId: Long): List<StoredPack> = load().filter { it.setId == setId }

    /**
     * Resolves a file that WhatsApp asked for. Only names listed in the index are served,
     * so a crafted URI can't walk out of the pack directory.
     */
    fun resolveAsset(id: String, fileName: String): File? {
        val pack = find(id) ?: return null
        val known = fileName == pack.tray || pack.stickers.any { it.file == fileName }
        if (!known) return null
        val f = File(dir(id), fileName)
        return f.takeIf { it.isFile && it.parentFile?.canonicalPath == dir(id).canonicalPath }
    }

    /** Atomically replaces every pack that came from [setId]. */
    @Synchronized
    fun replaceSet(setId: Long, packs: List<StoredPack>) {
        val others = load().filter { it.setId != setId }
        val stale = load().filter { it.setId == setId }.map { it.id }.toSet() - packs.map { it.id }.toSet()
        write(others + packs)
        stale.forEach { dir(it).deleteRecursively() }
    }

    @Synchronized
    fun clear() {
        root.deleteRecursively()
    }

    private fun write(packs: List<StoredPack>) {
        root.mkdirs()
        val arr = JSONArray()
        packs.forEach { arr.put(toJson(it)) }
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(JSONObject().put("packs", arr).toString())
        if (!tmp.renameTo(index)) {
            index.delete()
            check(tmp.renameTo(index)) { "could not write pack index" }
        }
    }

    private fun toJson(p: StoredPack) = JSONObject()
        .put("id", p.id).put("name", p.name).put("publisher", p.publisher).put("website", p.website)
        .put("animated", p.animated).put("version", p.version).put("setId", p.setId)
        .put("setKey", p.setKey).put("tray", p.tray)
        .put("stickers", JSONArray().apply {
            p.stickers.forEach { put(JSONObject().put("file", it.file).put("emoji", it.emoji)) }
        })

    private fun parse(o: JSONObject): StoredPack {
        val s = o.getJSONArray("stickers")
        return StoredPack(
            id = o.getString("id"),
            name = o.getString("name"),
            publisher = o.getString("publisher"),
            website = o.optString("website", ""),
            animated = o.getBoolean("animated"),
            version = o.getInt("version"),
            setId = o.getLong("setId"),
            setKey = o.getString("setKey"),
            tray = o.getString("tray"),
            stickers = (0 until s.length()).map {
                val j = s.getJSONObject(it)
                StoredSticker(j.getString("file"), j.getString("emoji"))
            },
        )
    }
}
