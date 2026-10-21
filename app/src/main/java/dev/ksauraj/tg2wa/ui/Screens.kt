package dev.ksauraj.tg2wa.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ksauraj.tg2wa.telegram.AuthUi

@Composable
fun AppRoot(vm: MainViewModel, onSecureChanged: (Boolean) -> Unit) {
    val auth by vm.auth.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val loggedIn = auth is AuthUi.Ready
    DisposableEffect(loggedIn) {
        onSecureChanged(!loggedIn)
        onDispose { }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when (val a = auth) {
                AuthUi.Starting -> Centered { CircularProgressIndicator() }
                AuthUi.NeedApiCredentials -> ApiCredentialsScreen(busy, vm::saveCredentials)
                AuthUi.WaitPhone -> StepScreen(
                    title = "Log in to Telegram",
                    body = "Enter your phone number with the country code. Telegram will send a login code to your Telegram app or by SMS.",
                    label = "Phone number", keyboard = KeyboardType.Phone, busy = busy, initial = "+",
                    onSubmit = vm::submitPhone,
                    footer = { TextButton(onClick = vm::resetCredentials) { Text("Change API credentials") } },
                )
                is AuthUi.WaitCode -> StepScreen(
                    title = "Enter the code",
                    body = "Telegram sent a code ${a.via} to ${a.phone}.\n\nNever share this code with anyone. This app uses it once, right here, to log in.",
                    label = if (a.length != null) "${a.length}-digit code" else "Code",
                    keyboard = KeyboardType.NumberPassword, busy = busy, onSubmit = vm::submitCode,
                    footer = { TextButton(onClick = vm::resendCode) { Text("Resend code") } },
                )
                is AuthUi.WaitPassword -> StepScreen(
                    title = "Two-step verification",
                    body = "Your account has a cloud password." + if (a.hint.isNotBlank()) " Hint: ${a.hint}" else "",
                    label = "Password", keyboard = KeyboardType.Password, password = true, busy = busy,
                    onSubmit = vm::submitPassword,
                )
                AuthUi.WaitEmail -> StepScreen(
                    title = "Login email",
                    body = "Telegram asks for an email address to protect this login.",
                    label = "Email", keyboard = KeyboardType.Email, busy = busy, onSubmit = vm::submitEmail,
                )
                is AuthUi.WaitEmailCode -> StepScreen(
                    title = "Email code",
                    body = "Enter the code sent to ${a.pattern}.",
                    label = "Code", keyboard = KeyboardType.NumberPassword, busy = busy, onSubmit = vm::submitEmailCode,
                )
                is AuthUi.Unsupported -> Centered {
                    Text(a.reason, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = vm::logOut) { Text("Start over") }
                }
                is AuthUi.Ready -> HomeScreen(vm, a.name)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun ApiCredentialsScreen(busy: Boolean, onSave: (String, String) -> Unit) {
    val context = LocalContext.current
    var apiId by rememberSaveable { mutableStateOf("") }
    var apiHash by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("TG2WA Stickers", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Copy your Telegram sticker sets into WhatsApp.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        InfoCard(
            "First, your own Telegram API key",
            "1. Open my.telegram.org and log in with your number.\n" +
                "2. Go to 'API development tools' and create an app (any name).\n" +
                "3. Copy the api_id and api_hash here.",
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org/apps")))
            } catch (_: ActivityNotFoundException) {
            }
        }) { Text("Open my.telegram.org") }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = apiId, onValueChange = { apiId = it.filter(Char::isDigit) }, label = { Text("api_id") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiHash, onValueChange = { apiHash = it.trim() }, label = { Text("api_hash") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSave(apiId, apiHash) }, enabled = !busy && apiId.isNotEmpty() && apiHash.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        Spacer(Modifier.height(24.dp))
        InfoCard(
            "How your account stays safe",
            "- You log in with Telegram's official library (TDLib), directly with Telegram's servers. There is no middle server.\n" +
                "- The session lives only on this phone, in an encrypted database. Its key is protected by the Android Keystore.\n" +
                "- The app never shows, exports or asks for session strings. Never paste one into any app.\n" +
                "- Backups are off, so the session can't leak through cloud backup.\n" +
                "- Log out any time here, or from Telegram > Settings > Devices.",
        )
    }
}

@Composable
private fun StepScreen(
    title: String,
    body: String,
    label: String,
    keyboard: KeyboardType,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    password: Boolean = false,
    initial: String = "",
    footer: @Composable () -> Unit = {},
) {
    var value by rememberSaveable(title) { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(32.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSubmit(value) }, enabled = !busy && value.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp) else Text("Next")
        }
        Spacer(Modifier.height(8.dp))
        footer()
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HomeScreen(vm: MainViewModel, name: String) {
    val context = LocalContext.current
    val sets by vm.sets.collectAsStateWithLifecycle()
    val packs by vm.packs.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val add by vm.add.collectAsStateWithLifecycle()
    val waInstalled by vm.whatsAppInstalled.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }

    val addLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        vm.onAddResult(r.resultCode == Activity.RESULT_OK, r.data?.getStringExtra("validation_error"))
    }
    LaunchedEffect(add) {
        val req = add ?: return@LaunchedEffect
        try {
            addLauncher.launch(req.intent)
        } catch (_: ActivityNotFoundException) {
            vm.onAddLaunchFailed()
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.syncAll() }
    val startSync = {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.syncAll()
    }

    val running = sync is SyncUi.Running
    val notAdded = packs.count { !it.added }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TG2WA Stickers", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Logged in as $name", style = MaterialTheme.typography.bodySmall)
                }
                Box {
                    TextButton(onClick = { menu = true }) { Text("Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Refresh") }, onClick = { menu = false; vm.refreshAll() })
                        DropdownMenuItem(text = { Text("Delete converted stickers") }, onClick = { menu = false; vm.deleteConverted() })
                        DropdownMenuItem(text = { Text("Log out of Telegram") }, onClick = { menu = false; vm.logOut() })
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("${sets.size} sticker sets on Telegram", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${packs.size} WhatsApp packs ready, ${packs.size - notAdded} added",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!waInstalled) {
                        Spacer(Modifier.height(6.dp))
                        Text("WhatsApp is not installed on this phone.", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(12.dp))
                    when (val s = sync) {
                        is SyncUi.Running -> {
                            val frac = if (s.stickers > 0) s.sticker.toFloat() / s.stickers else 0f
                            Text(
                                if (s.setCount > 0) "Set ${s.setIndex + 1} of ${s.setCount}: ${s.title}" else s.title,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            if (s.stickers > 0) {
                                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                                Text("Sticker ${s.sticker + 1} of ${s.stickers}", style = MaterialTheme.typography.bodySmall)
                            } else {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = vm::cancelSync) { Text("Cancel") }
                        }
                        is SyncUi.Done -> {
                            val extra = buildString {
                                if (s.failedStickers > 0) append(" ${s.failedStickers} sticker(s) could not be converted.")
                                if (s.failedSets.isNotEmpty()) append(" Failed sets: ${s.failedSets.joinToString()}.")
                            }
                            Text("Last sync: ${s.packs} packs.$extra", style = MaterialTheme.typography.bodySmall)
                        }
                        is SyncUi.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                        SyncUi.Idle -> Unit
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = startSync, enabled = !running && sets.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text("Sync all to WhatsApp")
                    }
                    if (notAdded > 0 && !running) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = vm::addAll, modifier = Modifier.fillMaxWidth()) {
                            Text("Add to WhatsApp ($notAdded left)")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "WhatsApp asks you to confirm each pack. Packs hold at most 30 stickers, so big sets are split.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        items(sets, key = { it.id }) { s ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val status = when {
                            s.packs == 0 -> "${s.count} stickers, not synced"
                            s.packsAdded == s.packs -> "${s.count} stickers, in WhatsApp (${s.packs} pack${if (s.packs > 1) "s" else ""})"
                            else -> "${s.count} stickers, ${s.packsAdded}/${s.packs} packs added"
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }
                    when {
                        s.packs == 0 -> TextButton(onClick = { vm.syncSet(s.id) }, enabled = !running) { Text("Sync") }
                        s.packsAdded < s.packs -> TextButton(onClick = { vm.addSet(s.id) }, enabled = !running) { Text("Add") }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
    }
}
