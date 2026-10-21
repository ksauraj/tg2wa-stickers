package dev.ksauraj.tg2wa.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.ksauraj.tg2wa.App
import dev.ksauraj.tg2wa.store.StoredPack
import dev.ksauraj.tg2wa.sync.SyncWorker
import dev.ksauraj.tg2wa.telegram.AuthUi
import dev.ksauraj.tg2wa.whatsapp.WhatsApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SetRow(
    val id: Long,
    val title: String,
    val count: Int,
    val packs: Int,
    val packsAdded: Int,
)

data class PackRow(val pack: StoredPack, val added: Boolean)

sealed interface SyncUi {
    data object Idle : SyncUi
    data class Running(val title: String, val setIndex: Int, val setCount: Int, val sticker: Int, val stickers: Int) : SyncUi
    data class Done(val packs: Int, val failedStickers: Int, val failedSets: List<String>) : SyncUi
    data class Failed(val message: String) : SyncUi
}

data class AddRequest(val intent: Intent, val packName: String, val remaining: Int)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val wm = WorkManager.getInstance(application)

    val auth: StateFlow<AuthUi> = app.telegram.auth

    private val local = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = MutableSharedFlow<String>(extraBufferCapacity = 16).also { out ->
        viewModelScope.launch { merge(app.telegram.errors, local).collect { out.emit(it) } }
    }

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _sets = MutableStateFlow<List<SetRow>>(emptyList())
    val sets = _sets.asStateFlow()

    private val _packs = MutableStateFlow<List<PackRow>>(emptyList())
    val packs = _packs.asStateFlow()

    private val _sync = MutableStateFlow<SyncUi>(SyncUi.Idle)
    val sync = _sync.asStateFlow()

    private val _add = MutableStateFlow<AddRequest?>(null)
    val add = _add.asStateFlow()

    private val _whatsAppInstalled = MutableStateFlow(true)
    val whatsAppInstalled = _whatsAppInstalled.asStateFlow()

    private var addQueue = ArrayDeque<StoredPack>()
    private var addAfterSync = false
    private var addErrors = 0

    init {
        viewModelScope.launch {
            auth.collect { if (it is AuthUi.Ready) refreshAll() }
        }
        viewModelScope.launch {
            wm.getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE).collect { infos -> onWork(infos.lastOrNull()) }
        }
    }

    // ---- login --------------------------------------------------------------------------

    fun saveCredentials(apiId: String, apiHash: String) {
        val id = apiId.trim().toIntOrNull()
        val hash = apiHash.trim()
        if (id == null || id <= 0) return say("api_id is a number, e.g. 1234567")
        if (!hash.matches(Regex("[0-9a-fA-F]{32}"))) return say("api_hash is 32 hex characters")
        app.telegram.saveCredentials(id, hash)
    }

    fun submitPhone(v: String) = step { app.telegram.submitPhone(v) }
    fun submitCode(v: String) = step { app.telegram.submitCode(v) }
    fun submitPassword(v: String) = step { app.telegram.submitPassword(v) }
    fun submitEmail(v: String) = step { app.telegram.submitEmail(v) }
    fun submitEmailCode(v: String) = step { app.telegram.submitEmailCode(v) }
    fun resendCode() = step { app.telegram.resendCode() }
    fun resetCredentials() = step { app.telegram.resetCredentials(); true }

    fun logOut() = step {
        SyncWorker.cancel(app)
        app.telegram.logOut()
        _sets.value = emptyList()
        true
    }

    fun deleteConverted() = viewModelScope.launch {
        SyncWorker.cancel(app)
        withContext(Dispatchers.IO) { app.packs.clear() }
        refreshPacks()
        say("Deleted converted stickers. Packs already in WhatsApp may disappear from it.")
    }

    private fun step(block: suspend () -> Boolean) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try { block() } finally { _busy.value = false }
        }
    }

    // ---- data ---------------------------------------------------------------------------

    fun refreshAll() = viewModelScope.launch {
        refreshPacks()
        try {
            val sets = withContext(Dispatchers.IO) { app.telegram.installedStickerSets() }
            val byset = _packs.value.groupBy { it.pack.setId }
            _sets.value = sets.map { s ->
                val p = byset[s.id].orEmpty()
                SetRow(s.id, s.title, s.size, p.size, p.count { it.added })
            }
        } catch (e: Exception) {
            say("Could not load sticker sets: ${e.message}")
        }
    }

    private suspend fun refreshPacks() {
        val rows = withContext(Dispatchers.IO) {
            _whatsAppInstalled.value = WhatsApp.installed(app).isNotEmpty()
            app.packs.load().map { PackRow(it, WhatsApp.isAdded(app, it.id)) }
        }
        _packs.value = rows
        val byset = rows.groupBy { it.pack.setId }
        _sets.value = _sets.value.map { s ->
            val p = byset[s.id].orEmpty()
            s.copy(packs = p.size, packsAdded = p.count { it.added })
        }
    }

    // ---- sync ---------------------------------------------------------------------------

    /** The one-tap path: convert everything, then walk through WhatsApp's add dialogs. */
    fun syncAll() {
        addAfterSync = true
        SyncWorker.enqueue(app)
    }

    fun syncSet(id: Long) {
        addAfterSync = true
        SyncWorker.enqueue(app, longArrayOf(id))
    }

    fun cancelSync() {
        addAfterSync = false
        SyncWorker.cancel(app)
    }

    private fun onWork(info: WorkInfo?) {
        if (info == null) return
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> _sync.value = SyncUi.Running("Starting", 0, 0, 0, 0)
            WorkInfo.State.RUNNING -> {
                val p = info.progress
                _sync.value = SyncUi.Running(
                    p.getString(SyncWorker.P_SET_TITLE) ?: "Connecting to Telegram",
                    p.getInt(SyncWorker.P_SET_INDEX, 0), p.getInt(SyncWorker.P_SET_COUNT, 0),
                    p.getInt(SyncWorker.P_STICKER_INDEX, 0), p.getInt(SyncWorker.P_STICKER_COUNT, 0),
                )
            }
            WorkInfo.State.SUCCEEDED -> {
                val o = info.outputData
                _sync.value = SyncUi.Done(
                    o.getInt(SyncWorker.OUT_PACKS, 0),
                    o.getInt(SyncWorker.OUT_FAILED_STICKERS, 0),
                    o.getString(SyncWorker.OUT_FAILED_SETS).orEmpty().split("\n").filter { it.isNotBlank() },
                )
                viewModelScope.launch {
                    refreshAll().join()
                    if (addAfterSync) { addAfterSync = false; addAll() }
                }
            }
            WorkInfo.State.FAILED -> {
                addAfterSync = false
                _sync.value = SyncUi.Failed(info.outputData.getString(SyncWorker.OUT_ERROR) ?: "Sync failed")
            }
            WorkInfo.State.CANCELLED -> {
                addAfterSync = false
                _sync.value = SyncUi.Idle
                viewModelScope.launch { refreshPacks() }
            }
        }
    }

    // ---- add to WhatsApp ----------------------------------------------------------------

    fun addAll() = startAdding(_packs.value.filter { !it.added }.map { it.pack })

    fun addSet(setId: Long) = startAdding(_packs.value.filter { !it.added && it.pack.setId == setId }.map { it.pack })

    private fun startAdding(list: List<StoredPack>) {
        if (WhatsApp.installed(app).isEmpty()) return say("WhatsApp is not installed.")
        if (list.isEmpty()) return say("Everything is already in WhatsApp.")
        addQueue = ArrayDeque(list)
        addErrors = 0
        next()
    }

    private fun next() {
        val pack = addQueue.removeFirstOrNull()
        if (pack == null) {
            _add.value = null
            viewModelScope.launch { refreshPacks() }
            say(if (addErrors == 0) "Done. Your stickers are in WhatsApp." else "Finished with $addErrors pack(s) rejected by WhatsApp.")
            return
        }
        val pkg = WhatsApp.installed(app).firstOrNull()
        _add.value = AddRequest(WhatsApp.addIntent(pack.id, pack.name, pkg), pack.name, addQueue.size)
    }

    /** Called with the result of WhatsApp's "Add sticker pack" dialog. */
    fun onAddResult(ok: Boolean, validationError: String?) {
        _add.value = null
        when {
            ok -> next()
            validationError != null -> {
                addErrors++
                say("WhatsApp rejected a pack: $validationError")
                next()
            }
            else -> {
                val left = addQueue.size + 1
                addQueue.clear()
                viewModelScope.launch { refreshPacks() }
                say("Stopped. $left pack(s) not added yet; tap 'Add to WhatsApp' to continue.")
            }
        }
    }

    fun onAddLaunchFailed() {
        _add.value = null
        addQueue.clear()
        say("Could not open WhatsApp. Is it installed and up to date?")
    }

    private fun say(msg: String) {
        local.tryEmit(msg)
    }
}
