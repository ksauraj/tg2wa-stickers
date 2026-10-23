package dev.ksauraj.tg2wa.store

import java.io.File

/** One converted sticker on disk, ready to be placed in a WhatsApp pack. */
data class ConvertedSticker(val file: File, val animated: Boolean, val emoji: String)

data class PlannedPack(
    val id: String,
    val name: String,
    val animated: Boolean,
    val stickers: List<ConvertedSticker>,
)

/**
 * Maps one Telegram sticker set onto WhatsApp's rules:
 *  - a pack holds 3..30 stickers,
 *  - a pack is either all-animated or all-static,
 *  - identifiers only use [A-Za-z0-9_ -.,'].
 * Big sets are split evenly ("Cats 1/3"), tiny sets are padded by repeating stickers.
 */
object PackPlanner {
    const val MAX_STICKERS = 30
    const val MIN_STICKERS = 3
    private const val MAX_NAME = 128
    const val FALLBACK_EMOJI = "\uD83D\uDE42" // slightly smiling face

    fun plan(setId: Long, title: String, stickers: List<ConvertedSticker>): List<PlannedPack> {
        if (stickers.isEmpty()) return emptyList()
        val groups = listOf(true, false)
            .map { animated -> animated to stickers.filter { it.animated == animated } }
            .filter { it.second.isNotEmpty() }
        val chunks = groups.flatMap { (animated, list) -> split(list).map { animated to it } }
        val baseTitle = title.trim().ifEmpty { "Telegram stickers" }
        return chunks.mapIndexed { index, (animated, list) ->
            val suffix = if (chunks.size > 1) " ${index + 1}/${chunks.size}" else ""
            PlannedPack(
                id = identifier(setId, animated, index),
                name = baseTitle.take(MAX_NAME - suffix.length) + suffix,
                animated = animated,
                stickers = pad(list).map { it.copy(emoji = normalizeEmoji(it.emoji)) },
            )
        }
    }

    fun identifier(setId: Long, animated: Boolean, index: Int): String =
        "tg_" + java.lang.Long.toUnsignedString(setId) + "_" + (if (animated) "a" else "s") + index

    /** Even split so no chunk ends up below the minimum (e.g. 31 -> 16 + 15, not 30 + 1). */
    internal fun <T> split(list: List<T>): List<List<T>> {
        if (list.size <= MAX_STICKERS) return listOf(list)
        val parts = (list.size + MAX_STICKERS - 1) / MAX_STICKERS
        val base = list.size / parts
        val extra = list.size % parts
        val out = ArrayList<List<T>>(parts)
        var from = 0
        for (p in 0 until parts) {
            val size = base + if (p < extra) 1 else 0
            out += list.subList(from, from + size)
            from += size
        }
        return out
    }

    internal fun <T> pad(list: List<T>): List<T> {
        if (list.size >= MIN_STICKERS) return list
        return List(MIN_STICKERS) { list[it % list.size] }
    }

    fun normalizeEmoji(emoji: String): String = emoji.trim().ifEmpty { FALLBACK_EMOJI }
}
