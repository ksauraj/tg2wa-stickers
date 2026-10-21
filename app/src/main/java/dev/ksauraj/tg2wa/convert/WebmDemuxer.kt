package dev.ksauraj.tg2wa.convert

/**
 * Tiny Matroska/WebM demuxer, just enough for Telegram video stickers.
 *
 * Telegram video stickers are VP9 with transparency. The colour picture lives in the
 * Block, and the alpha plane is a *second* VP9 stream stored in BlockAdditional
 * (BlockAddID 1). Android's MediaExtractor drops BlockAdditions, so we parse the
 * container ourselves and feed both streams to MediaCodec.
 */
object WebmDemuxer {

    class Frame(val timeMs: Long, val data: ByteArray, val alpha: ByteArray?, val keyFrame: Boolean)

    class Video(
        val codecId: String,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val frames: List<Frame>,
    ) {
        val hasAlpha: Boolean get() = frames.any { it.alpha != null }
    }

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMECODE_SCALE = 0x2AD7B1L
    private const val ID_DURATION = 0x4489L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_DEFAULT_DURATION = 0x23E383L
    private const val ID_VIDEO = 0xE0L
    private const val ID_PIXEL_WIDTH = 0xB0L
    private const val ID_PIXEL_HEIGHT = 0xBAL
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_TIMECODE = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_ADDITIONS = 0x75A1L
    private const val ID_BLOCK_MORE = 0xA6L
    private const val ID_BLOCK_ADD_ID = 0xEEL
    private const val ID_BLOCK_ADDITIONAL = 0xA5L

    private class Track(var number: Long = -1, var type: Long = -1, var codec: String = "",
                        var width: Int = 0, var height: Int = 0, var defaultDurationNs: Long = 0)

    private class State(val b: ByteArray) {
        var timecodeScale = 1_000_000L
        var durationTicks = -1.0
        val tracks = ArrayList<Track>()
        var clusterTime = 0L
        val raw = ArrayList<RawBlock>()
    }

    private class RawBlock(val track: Long, val ticks: Long, val data: ByteArray, val alpha: ByteArray?, val key: Boolean)

    fun parse(bytes: ByteArray): Video {
        val s = State(bytes)
        walk(s, 0, bytes.size, depth = 0)
        val track = s.tracks.firstOrNull { it.type == 1L } ?: s.tracks.firstOrNull()
            ?: throw IllegalArgumentException("webm: no tracks")
        val blocks = s.raw.filter { it.track == track.number }
        require(blocks.isNotEmpty()) { "webm: no video frames" }
        val scaleMs = s.timecodeScale / 1_000_000.0
        val frames = blocks
            .map { Frame((it.ticks * scaleMs).toLong(), it.data, it.alpha, it.key) }
            .sortedBy { it.timeMs }
        val lastStep = when {
            track.defaultDurationNs > 0 -> track.defaultDurationNs / 1_000_000
            frames.size > 1 -> (frames.last().timeMs - frames.first().timeMs) / (frames.size - 1)
            else -> 33L
        }.coerceAtLeast(1)
        val fromFrames = frames.last().timeMs + lastStep
        val duration = if (s.durationTicks > 0) (s.durationTicks * scaleMs).toLong().coerceAtLeast(1) else fromFrames
        require(track.width > 0 && track.height > 0) { "webm: missing frame size" }
        return Video(track.codec, track.width, track.height, duration, frames)
    }

    private fun walk(s: State, start: Int, end: Int, depth: Int) {
        require(depth < 64) { "webm: nesting too deep" }
        var i = start
        while (i < end) {
            val (id, idLen) = readId(s.b, i) ?: return
            val (size, sizeLen, unknown) = readSize(s.b, i + idLen) ?: return
            val ds = i + idLen + sizeLen
            val de = if (unknown) end else minOf(end.toLong(), ds + size).toInt()
            if (ds > end) return
            when (id) {
                ID_SEGMENT, ID_INFO, ID_TRACKS, ID_CLUSTER -> walk(s, ds, de, depth + 1)
                ID_TRACK_ENTRY -> { s.tracks += Track(); walk(s, ds, de, depth + 1) }
                ID_VIDEO -> walk(s, ds, de, depth + 1)
                ID_TIMECODE_SCALE -> s.timecodeScale = uint(s.b, ds, de).takeIf { it > 0 } ?: 1_000_000L
                ID_DURATION -> s.durationTicks = float(s.b, ds, de)
                ID_TRACK_NUMBER -> s.tracks.lastOrNull()?.number = uint(s.b, ds, de)
                ID_TRACK_TYPE -> s.tracks.lastOrNull()?.type = uint(s.b, ds, de)
                ID_CODEC_ID -> s.tracks.lastOrNull()?.codec = String(s.b, ds, de - ds, Charsets.US_ASCII).trimEnd('\u0000')
                ID_DEFAULT_DURATION -> s.tracks.lastOrNull()?.defaultDurationNs = uint(s.b, ds, de)
                ID_PIXEL_WIDTH -> s.tracks.lastOrNull()?.width = uint(s.b, ds, de).toInt()
                ID_PIXEL_HEIGHT -> s.tracks.lastOrNull()?.height = uint(s.b, ds, de).toInt()
                ID_TIMECODE -> s.clusterTime = uint(s.b, ds, de)
                ID_SIMPLE_BLOCK -> block(s, ds, de, alpha = null, simple = true)
                ID_BLOCK_GROUP -> blockGroup(s, ds, de)
                ID_EBML -> Unit
                else -> Unit
            }
            i = de
        }
    }

    private fun blockGroup(s: State, start: Int, end: Int) {
        var blockRange: IntRange? = null
        var alpha: ByteArray? = null
        children(s.b, start, end) { id, ds, de ->
            when (id) {
                ID_BLOCK -> blockRange = ds until de
                ID_BLOCK_ADDITIONS -> children(s.b, ds, de) { id2, ds2, de2 ->
                    if (id2 == ID_BLOCK_MORE) {
                        var addId = 1L
                        var data: ByteArray? = null
                        children(s.b, ds2, de2) { id3, ds3, de3 ->
                            when (id3) {
                                ID_BLOCK_ADD_ID -> addId = uint(s.b, ds3, de3)
                                ID_BLOCK_ADDITIONAL -> data = s.b.copyOfRange(ds3, de3)
                            }
                        }
                        if (addId == 1L && data != null) alpha = data
                    }
                }
            }
        }
        blockRange?.let { block(s, it.first, it.last + 1, alpha, simple = false) }
    }

    private fun block(s: State, start: Int, end: Int, alpha: ByteArray?, simple: Boolean) {
        val (track, tLen, _) = readSize(s.b, start) ?: return
        val p = start + tLen
        if (p + 3 > end) return
        val rel = ((s.b[p].toInt() shl 8) or (s.b[p + 1].toInt() and 0xFF)).toShort().toLong()
        val flags = s.b[p + 2].toInt() and 0xFF
        require(flags and 0x06 == 0) { "webm: laced video blocks are not supported" }
        val key = simple && (flags and 0x80 != 0)
        s.raw += RawBlock(track, s.clusterTime + rel, s.b.copyOfRange(p + 3, end), alpha, key)
    }

    private inline fun children(b: ByteArray, start: Int, end: Int, f: (Long, Int, Int) -> Unit) {
        var i = start
        while (i < end) {
            val (id, idLen) = readId(b, i) ?: return
            val (size, sizeLen, unknown) = readSize(b, i + idLen) ?: return
            val ds = i + idLen + sizeLen
            val de = if (unknown) end else minOf(end.toLong(), ds + size).toInt()
            if (ds > end) return
            f(id, ds, de)
            i = de
        }
    }

    /** Element ID: VINT with the length marker kept. */
    private fun readId(b: ByteArray, at: Int): Pair<Long, Int>? {
        if (at >= b.size) return null
        val first = b[at].toInt() and 0xFF
        val len = vintLength(first) ?: return null
        if (len > 4 || at + len > b.size) return null
        var v = first.toLong()
        for (k in 1 until len) v = (v shl 8) or (b[at + k].toLong() and 0xFF)
        return v to len
    }

    /** Data size: VINT with the marker stripped; all-ones means "unknown size". */
    private fun readSize(b: ByteArray, at: Int): Triple<Long, Int, Boolean>? {
        if (at >= b.size) return null
        val first = b[at].toInt() and 0xFF
        val len = vintLength(first) ?: return null
        if (at + len > b.size) return null
        var v = (first and (0xFF shr len)).toLong()
        for (k in 1 until len) v = (v shl 8) or (b[at + k].toLong() and 0xFF)
        val unknown = v == (1L shl (7 * len)) - 1
        return Triple(v, len, unknown)
    }

    private fun vintLength(first: Int): Int? {
        if (first == 0) return null
        var mask = 0x80
        var len = 1
        while (first and mask == 0) { mask = mask shr 1; len++ }
        return len
    }

    private fun uint(b: ByteArray, ds: Int, de: Int): Long {
        var v = 0L
        for (k in ds until de) v = (v shl 8) or (b[k].toLong() and 0xFF)
        return v
    }

    private fun float(b: ByteArray, ds: Int, de: Int): Double = when (de - ds) {
        4 -> java.lang.Float.intBitsToFloat(uint(b, ds, de).toInt()).toDouble()
        8 -> java.lang.Double.longBitsToDouble(uint(b, ds, de))
        else -> -1.0
    }
}
