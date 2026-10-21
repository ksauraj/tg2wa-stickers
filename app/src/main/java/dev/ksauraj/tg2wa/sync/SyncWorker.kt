package dev.ksauraj.tg2wa.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.g000sha256.tdl.dto.StickerFormatTgs
import dev.g000sha256.tdl.dto.StickerFormatWebm
import dev.g000sha256.tdl.dto.StickerFormatWebp
import dev.g000sha256.tdl.dto.StickerSet
import dev.ksauraj.tg2wa.App
import dev.ksauraj.tg2wa.convert.SourceFormat
import dev.ksauraj.tg2wa.convert.StickerConverter
import dev.ksauraj.tg2wa.store.ConvertedSticker
import dev.ksauraj.tg2wa.store.PackPlanner
import dev.ksauraj.tg2wa.store.StoredPack
import dev.ksauraj.tg2wa.store.StoredSticker
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.security.MessageDigest

/**
 * Downloads every installed Telegram sticker set, converts it and writes WhatsApp packs.
 * Runs as a foreground job so a few hundred stickers can finish with the screen off.
 * Sets that haven't changed since the last run are skipped.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val UNIQUE = "sticker-sync"
        const val KEY_SET_IDS = "set_ids"
        const val P_SET_TITLE = "set_title"
        const val P_SET_INDEX = "set_index"
        const val P_SET_COUNT = "set_count"
        const val P_STICKER_INDEX = "sticker_index"
        const val P_STICKER_COUNT = "sticker_count"
        const val OUT_PACKS = "packs"
        const val OUT_FAILED_STICKERS = "failed_stickers"
        const val OUT_FAILED_SETS = "failed_sets"
        const val OUT_ERROR = "error"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "SyncWorker"
        private const val DOWNLOADS = 6

        /** [setIds] null = every installed set. */
        fun enqueue(context: Context, setIds: LongArray? = null) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(if (setIds != null) workDataOf(KEY_SET_IDS to setIds) else workDataOf())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
    }

    private val app = context.applicationContext as App
    private val converter = StickerConverter()
    private val failedStickers = AtomicInteger(0)
    private val downloads = Semaphore(DOWNLOADS)
    private val convertDispatcher = Dispatchers.Default.limitedParallelism(
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4),
    )
    private val progressLock = Mutex()
    private var lastProgressAt = 0L

    override suspend fun doWork(): Result {
        foreground("Connecting to Telegram", 0, 0)
        if (!app.telegram.awaitReady()) {
            return Result.failure(workDataOf(OUT_ERROR to "Not logged in to Telegram. Open the app and log in."))
        }
        val only = inputData.getLongArray(KEY_SET_IDS)?.toSet()
        val sets = try {
            app.telegram.installedStickerSets().filter { only == null || it.id in only }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(workDataOf(OUT_ERROR to "Could not list sticker sets: ${e.message}"))
        }
        val failedSets = ArrayList<String>()
        var packCount = 0
        for ((i, info) in sets.withIndex()) {
            if (isStopped) break
            progress(info.title, i, sets.size, 0, info.size)
            try {
                packCount += syncSet(app.telegram.stickerSet(info.id), i, sets.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "set ${info.name} failed", e)
                failedSets += info.title
            }
        }
        return Result.success(
            workDataOf(
                OUT_PACKS to packCount,
                OUT_FAILED_STICKERS to failedStickers.get(),
                OUT_FAILED_SETS to failedSets.joinToString("\n"),
            ),
        )
    }

    /** Returns the number of WhatsApp packs now available for this set. */
    private suspend fun syncSet(set: StickerSet, setIndex: Int, setCount: Int): Int {
        val key = fingerprint(set)
        val existing = app.packs.packsForSet(set.id)
        val upToDate = existing.isNotEmpty() && existing.all { p ->
            p.setKey == key && p.stickers.all { File(app.packs.dir(p.id), it.file).isFile }
        }
        if (upToDate) return existing.size

        val work = File(applicationContext.cacheDir, "convert/${java.lang.Long.toUnsignedString(set.id)}")
        work.deleteRecursively()
        work.mkdirs()
        try {
            // Pipeline: up to DOWNLOADS files in flight from Telegram, and conversions on
            // a few cores at once. Each sticker is independent, results keep their order.
            val done = AtomicInteger(0)
            val total = set.stickers.size
            val converted = coroutineScope {
                set.stickers.mapIndexed { n, sticker ->
                    async {
                        val format = when (sticker.format) {
                            is StickerFormatWebp -> SourceFormat.WEBP
                            is StickerFormatTgs -> SourceFormat.TGS
                            is StickerFormatWebm -> SourceFormat.WEBM
                        }
                        val item = try {
                            val src = downloads.withPermit { app.telegram.download(sticker.sticker) }
                            val result = withContext(convertDispatcher) { StickerConverter().convert(src, format) }
                            val out = File(work, "%03d.webp".format(n))
                            out.writeBytes(result.bytes)
                            ConvertedSticker(out, result.animated, sticker.emoji)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failedStickers.incrementAndGet()
                            Log.w(TAG, "sticker $n of ${set.name} ($format) failed", e)
                            null
                        }
                        progress(set.title, setIndex, setCount, done.incrementAndGet(), total)
                        item
                    }
                }.awaitAll().filterNotNull()
            }
            val planned = PackPlanner.plan(set.id, set.title, converted)
            val oldVersions = existing.associate { it.id to it.version }
            val stored = planned.map { plan ->
                val dir = app.packs.dir(plan.id)
                dir.deleteRecursively()
                dir.mkdirs()
                val stickers = plan.stickers.mapIndexed { idx, s ->
                    val name = "%02d.webp".format(idx + 1)
                    s.file.copyTo(File(dir, name), overwrite = true)
                    StoredSticker(name, s.emoji)
                }
                File(dir, "tray.png").writeBytes(converter.trayIcon(plan.stickers.first().file.readBytes()))
                StoredPack(
                    id = plan.id,
                    name = plan.name,
                    publisher = "Telegram @${set.name}".take(128),
                    website = "https://t.me/addstickers/${set.name}",
                    animated = plan.animated,
                    version = (oldVersions[plan.id] ?: 0) + 1,
                    setId = set.id,
                    setKey = key,
                    tray = "tray.png",
                    stickers = stickers,
                )
            }
            app.packs.replaceSet(set.id, stored)
            return stored.size
        } finally {
            work.deleteRecursively()
        }
    }

    private fun fingerprint(set: StickerSet): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("v1".toByteArray())
        set.stickers.forEach { md.update("${it.id}:${it.emoji};".toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    private suspend fun progress(title: String, setIndex: Int, setCount: Int, sticker: Int, stickers: Int) {
        // Called from many coroutines at once: serialize and throttle.
        progressLock.withLock {
            val now = SystemClock.elapsedRealtime()
            if (sticker in 1 until stickers && now - lastProgressAt < 400) return
            lastProgressAt = now
            publish(title, setIndex, setCount, sticker, stickers)
        }
    }

    private suspend fun publish(title: String, setIndex: Int, setCount: Int, sticker: Int, stickers: Int) {
        setProgress(
            workDataOf(
                P_SET_TITLE to title, P_SET_INDEX to setIndex, P_SET_COUNT to setCount,
                P_STICKER_INDEX to sticker, P_STICKER_COUNT to stickers,
            ),
        )
        foreground("Set ${setIndex + 1}/$setCount: $title", sticker, stickers)
    }

    private suspend fun foreground(text: String, done: Int, total: Int) {
        try {
            setForeground(foregroundInfo(text, done, total))
        } catch (e: Exception) {
            // Background-start restrictions: keep going as a regular job.
            Log.w(TAG, "foreground not allowed: ${e.message}")
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Syncing stickers", 0, 0)

    private fun foregroundInfo(text: String, done: Int, total: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, App.SYNC_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Syncing Telegram stickers")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, done, total == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }
}
