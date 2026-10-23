package dev.ksauraj.tg2wa.telegram

import android.content.Context
import android.os.Build
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateClosing
import dev.g000sha256.tdl.dto.AuthorizationStateLoggingOut
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailAddress
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitOtherDeviceConfirmation
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPremiumPurchase
import dev.g000sha256.tdl.dto.AuthorizationStateWaitRegistration
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.AuthenticationCodeType
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFlashCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFragment
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeMissedCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSms
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeTelegramMessage
import dev.g000sha256.tdl.dto.EmailAddressAuthenticationCode
import dev.g000sha256.tdl.dto.File as TdFile
import dev.g000sha256.tdl.dto.StickerSet
import dev.g000sha256.tdl.dto.StickerSetInfo
import dev.g000sha256.tdl.dto.StickerTypeRegular
import dev.ksauraj.tg2wa.BuildConfig
import dev.ksauraj.tg2wa.security.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/** What the UI should show for login. */
sealed interface AuthUi {
    data object Starting : AuthUi
    data object NeedApiCredentials : AuthUi
    data object WaitPhone : AuthUi
    data class WaitCode(val phone: String, val via: String, val length: Int?) : AuthUi
    data class WaitPassword(val hint: String) : AuthUi
    data object WaitEmail : AuthUi
    data class WaitEmailCode(val pattern: String) : AuthUi
    data class Unsupported(val reason: String) : AuthUi
    data class Ready(val name: String) : AuthUi
}

class TelegramException(val code: Int, message: String) : Exception(message)

fun <T> TdlResult<T>.orThrow(): T = when (this) {
    is TdlResult.Success -> result
    is TdlResult.Failure -> throw TelegramException(code, message)
}

/**
 * Owns the single TDLib client. TDLib is Telegram's official client library: the login
 * happens directly between this phone and Telegram (phone number + code + optional 2FA),
 * and the resulting session is stored in an encrypted TDLib database inside the app's
 * private storage. No session string is ever shown, exported or sent anywhere.
 */
class TelegramRepository(context: Context, private val secure: SecureStore) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tdDir = File(app.filesDir, "tdlib")

    private var client: TdlClient? = null
    private var updatesJob: Job? = null
    private var wipeOnClose = false

    private val _auth = MutableStateFlow<AuthUi>(
        if (secure.hasApiCredentials()) AuthUi.Starting else AuthUi.NeedApiCredentials,
    )
    val auth: StateFlow<AuthUi> = _auth.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    @Synchronized
    fun start() {
        if (client != null || !secure.hasApiCredentials()) return
        _auth.value = AuthUi.Starting
        val c = TdlClient.create()
        client = c
        // Subscribe before the first request so the initial state isn't missed.
        updatesJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            c.authorizationStateUpdates.collect { onState(c, it.authorizationState) }
        }
        scope.launch {
            c.setLogVerbosityLevel(newVerbosityLevel = 1) // errors only; never log message contents
            (c.getAuthorizationState() as? TdlResult.Success)?.let { onState(c, it.result) }
        }
    }

    fun saveCredentials(apiId: Int, apiHash: String) {
        secure.saveApiCredentials(apiId, apiHash.trim())
        start()
    }

    suspend fun submitPhone(phone: String) = call { it.setAuthenticationPhoneNumber(phoneNumber = phone.filter { ch -> ch.isDigit() || ch == '+' }) }
    suspend fun submitCode(code: String) = call { it.checkAuthenticationCode(code = code.trim()) }
    suspend fun submitPassword(password: String) = call { it.checkAuthenticationPassword(password = password) }
    suspend fun submitEmail(email: String) = call { it.setAuthenticationEmailAddress(emailAddress = email.trim()) }
    suspend fun submitEmailCode(code: String) = call {
        it.checkAuthenticationEmailCode(code = EmailAddressAuthenticationCode(code = code.trim()))
    }
    suspend fun resendCode() = call { it.resendAuthenticationCode() }

    /** Logs out on Telegram's side (kills the session) and deletes the local database. */
    suspend fun logOut() {
        val c = client
        wipeOnClose = true
        if (c == null) {
            wipeLocal(); return
        }
        if (c.logOut() is TdlResult.Failure) {
            c.close()
        }
    }

    /** Wrong api_id/api_hash: drop them and the half-made database, then ask again. */
    suspend fun resetCredentials() {
        secure.clearApiCredentials()
        wipeOnClose = true
        val c = client
        if (c == null) wipeLocal() else c.close()
        _auth.value = AuthUi.NeedApiCredentials
    }

    // ---- stickers -----------------------------------------------------------------------

    suspend fun awaitReady(timeoutMs: Long = 60_000): Boolean {
        start()
        return withTimeoutOrNull(timeoutMs) {
            auth.first { it is AuthUi.Ready || it is AuthUi.NeedApiCredentials || it is AuthUi.WaitPhone }
        } is AuthUi.Ready
    }

    suspend fun installedStickerSets(): List<StickerSetInfo> =
        requireClient().getInstalledStickerSets(stickerType = StickerTypeRegular()).orThrow().sets.toList()

    suspend fun stickerSet(id: Long): StickerSet = requireClient().getStickerSet(setId = id).orThrow()

    /** Downloads (or reuses the cached copy of) a file and returns its local path. */
    suspend fun download(file: TdFile): File {
        if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) {
            File(file.local.path).takeIf { it.isFile }?.let { return it }
        }
        val done = requireClient().downloadFile(
            fileId = file.id, priority = 16, offset = 0, limit = 0, synchronous = true,
        ).orThrow()
        check(done.local.isDownloadingCompleted && done.local.path.isNotEmpty()) { "download did not finish" }
        return File(done.local.path)
    }

    // ---- internals ----------------------------------------------------------------------

    private fun requireClient(): TdlClient = client ?: throw IllegalStateException("Telegram is not connected")

    private suspend fun call(block: suspend (TdlClient) -> TdlResult<*>): Boolean {
        val c = client ?: return false
        return when (val r = block(c)) {
            is TdlResult.Success -> true
            is TdlResult.Failure -> { report(r.code, r.message); false }
        }
    }

    private fun report(code: Int, message: String) {
        val friendly = when {
            message.contains("API_ID_INVALID") || message.contains("API_ID_PUBLISHED_FLOOD") ->
                "Telegram rejected the api_id / api_hash. Use 'Change API credentials' and re-enter them from my.telegram.org."
            message.contains("PHONE_NUMBER_INVALID") -> "That phone number doesn't look right. Include the country code, e.g. +91..."
            message.contains("PHONE_NUMBER_BANNED") -> "Telegram says this number is banned."
            message.contains("PHONE_CODE_INVALID") -> "Wrong code. Check the code Telegram sent you."
            message.contains("PHONE_CODE_EXPIRED") -> "That code expired. Tap 'Resend code'."
            message.contains("PASSWORD_HASH_INVALID") -> "Wrong 2-step verification password."
            message.contains("FLOOD_WAIT") || code == 429 -> "Telegram is rate-limiting this account. Wait a bit and try again. ($message)"
            else -> "Telegram error $code: $message"
        }
        _errors.tryEmit(friendly)
    }

    private suspend fun onState(c: TdlClient, state: AuthorizationState) {
        if (c !== client) return
        when (state) {
            is AuthorizationStateWaitTdlibParameters -> setParameters(c)
            is AuthorizationStateWaitPhoneNumber -> _auth.value = AuthUi.WaitPhone
            is AuthorizationStateWaitCode -> _auth.value = AuthUi.WaitCode(
                phone = state.codeInfo.phoneNumber,
                via = describe(state.codeInfo.type),
                length = codeLength(state.codeInfo.type),
            )
            is AuthorizationStateWaitPassword -> _auth.value = AuthUi.WaitPassword(state.passwordHint)
            is AuthorizationStateWaitEmailAddress -> _auth.value = AuthUi.WaitEmail
            is AuthorizationStateWaitEmailCode -> _auth.value = AuthUi.WaitEmailCode(state.codeInfo.emailAddressPattern)
            is AuthorizationStateWaitRegistration -> _auth.value = AuthUi.Unsupported(
                "This number has no Telegram account yet. Sign up in the Telegram app first, then come back.",
            )
            is AuthorizationStateWaitPremiumPurchase -> _auth.value = AuthUi.Unsupported(
                "Telegram requires a Premium purchase to log in with this number. Log in from the Telegram app first.",
            )
            is AuthorizationStateWaitOtherDeviceConfirmation -> _auth.value = AuthUi.WaitPhone
            is AuthorizationStateReady -> {
                val me = (c.getMe() as? TdlResult.Success)?.result
                val name = listOfNotNull(me?.firstName, me?.lastName).joinToString(" ").trim()
                _auth.value = AuthUi.Ready(name.ifEmpty { "Telegram user" })
            }
            is AuthorizationStateLoggingOut, is AuthorizationStateClosing -> _auth.value = AuthUi.Starting
            is AuthorizationStateClosed -> onClosed()
        }
    }

    private suspend fun setParameters(c: TdlClient) {
        val apiId = secure.apiId()
        val apiHash = secure.apiHash()
        if (apiId == null || apiHash == null) {
            _auth.value = AuthUi.NeedApiCredentials
            return
        }
        tdDir.mkdirs()
        val r = c.setTdlibParameters(
            useTestDc = false,
            databaseDirectory = File(tdDir, "db").path,
            filesDirectory = File(tdDir, "files").path,
            databaseEncryptionKey = secure.databaseKey(),
            useFileDatabase = true,
            useChatInfoDatabase = false,
            useMessageDatabase = false,
            useSecretChats = false,
            apiId = apiId,
            apiHash = apiHash,
            systemLanguageCode = Locale.getDefault().language.ifEmpty { "en" },
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            systemVersion = "Android ${Build.VERSION.RELEASE}",
            applicationVersion = BuildConfig.VERSION_NAME,
        )
        if (r is TdlResult.Failure) report(r.code, r.message)
    }

    @Synchronized
    private fun onClosed() {
        updatesJob?.cancel()
        updatesJob = null
        client = null
        if (wipeOnClose) {
            wipeOnClose = false
            wipeLocal()
        }
        if (secure.hasApiCredentials()) start() else _auth.value = AuthUi.NeedApiCredentials
    }

    private fun wipeLocal() {
        tdDir.deleteRecursively()
        secure.wipeDatabaseKey()
    }

    private fun describe(type: AuthenticationCodeType): String = when (type) {
        is AuthenticationCodeTypeTelegramMessage -> "in your Telegram app"
        is AuthenticationCodeTypeSms -> "by SMS"
        is AuthenticationCodeTypeCall -> "by phone call"
        is AuthenticationCodeTypeFlashCall -> "by flash call"
        is AuthenticationCodeTypeMissedCall -> "as a missed call (last digits of the number)"
        is AuthenticationCodeTypeFragment -> "on fragment.com"
        else -> "via Telegram"
    }

    private fun codeLength(type: AuthenticationCodeType): Int? = when (type) {
        is AuthenticationCodeTypeTelegramMessage -> type.length
        is AuthenticationCodeTypeSms -> type.length
        is AuthenticationCodeTypeCall -> type.length
        else -> null
    }
}
