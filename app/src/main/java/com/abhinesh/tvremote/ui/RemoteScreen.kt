package com.abhinesh.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Input
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinesh.tvremote.TvRemoteApp
import com.abhinesh.tvremote.core.AppShortcut
import com.abhinesh.tvremote.core.ConnectionState
import com.abhinesh.tvremote.core.KeyCodes
import com.abhinesh.tvremote.core.TvStatus
import com.abhinesh.tvremote.net.WakeOnLan
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RemoteScreen(app: TvRemoteApp) {
    val repo = app.repo
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val shortcuts by app.store.shortcuts.collectAsStateWithLifecycle()
    val launching by repo.launching.collectAsStateWithLifecycle()
    var keyboard by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusHeader(app)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shortcuts.take(4).forEach { s ->
                ShortcutTile(s, launching == s.id, Modifier.weight(1f)) { repo.launch(s) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PowerKey(app, Modifier.weight(1f))
            IconKey("Input source", Icons.AutoMirrored.Rounded.Input, Modifier.weight(1f)) { repo.key(KeyCodes.TV_INPUT) }
            IconKey("Keyboard", Icons.Rounded.Keyboard, Modifier.weight(1f)) { keyboard = true }
            IconKey("TV settings", Icons.Rounded.Settings, Modifier.weight(1f)) { repo.key(KeyCodes.SETTINGS) }
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (settings.swipePad) {
                SwipePad(onDirection = { repo.key(it) }, onTap = { repo.key(KeyCodes.DPAD_CENTER) })
            } else {
                DPad(app)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteKey("Back", { repo.key(KeyCodes.BACK) }, Modifier.weight(1f).height(60.dp), hold = Hold.LONG,
                onLongStart = { repo.keyDown(KeyCodes.BACK) }, onLongEnd = { repo.keyUp(KeyCodes.BACK) }) {
                LabeledIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
            RemoteKey("Home", { repo.key(KeyCodes.HOME) }, Modifier.weight(1f).height(60.dp), hold = Hold.LONG,
                onLongStart = { repo.keyDown(KeyCodes.HOME) }, onLongEnd = { repo.keyUp(KeyCodes.HOME) }) {
                LabeledIcon(Icons.Rounded.Home, "Home")
            }
            RemoteKey(
                if (settings.swipePad) "Switch to D-pad" else "Switch to swipe pad",
                { app.store.updateSettings { it.copy(swipePad = !it.swipePad) } }, Modifier.weight(1f).height(60.dp),
            ) {
                if (settings.swipePad) LabeledIcon(Icons.Rounded.Gamepad, "D-pad") else LabeledIcon(Icons.Rounded.TouchApp, "Swipe")
            }
        }

        VolumeRow(app)

        Row(Modifier.fillMaxWidth().background(Ui.key, RoundedCornerShape(18.dp)), horizontalArrangement = Arrangement.SpaceEvenly) {
            MediaKey("Previous", Icons.Rounded.SkipPrevious) { repo.key(KeyCodes.MEDIA_PREVIOUS) }
            MediaKey("Rewind", Icons.Rounded.FastRewind) { repo.key(KeyCodes.MEDIA_REWIND) }
            MediaKey("Play or pause", Icons.Rounded.PlayArrow) { repo.key(KeyCodes.MEDIA_PLAY_PAUSE) }
            MediaKey("Fast forward", Icons.Rounded.FastForward) { repo.key(KeyCodes.MEDIA_FAST_FORWARD) }
            MediaKey("Next", Icons.Rounded.SkipNext) { repo.key(KeyCodes.MEDIA_NEXT) }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (keyboard) KeyboardSheet(app) { keyboard = false }
}

@Composable
fun StatusHeader(app: TvRemoteApp) {
    val tv by app.store.tv.collectAsStateWithLifecycle()
    val conn by app.repo.connection.collectAsStateWithLifecycle()
    val status by app.repo.status.collectAsStateWithLifecycle()
    val shortcuts by app.store.shortcuts.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tv?.name ?: "TV", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle(tv?.model, conn, status, shortcuts), color = Ui.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        val (label, color) = chip(conn, status)
        Text(
            label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
    when (val c = conn) {
        is ConnectionState.Retrying, is ConnectionState.Idle -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(friendlyReason(c), color = Ui.warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { app.repo.reconnectNow() }) { Text("Retry now") }
        }
        is ConnectionState.PairingNeeded, is ConnectionState.IdentityChanged -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (c is ConnectionState.IdentityChanged) "This TV's identity changed since pairing. Pair again to continue."
                else "The TV no longer accepts this phone. Pair again (a new code will appear on the TV).",
                color = Ui.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
            )
            Button(onClick = { tv?.let { app.repo.startPairing(it.host, it.name) } }) { Text("Pair again") }
        }
        else -> {}
    }
}

private fun chip(conn: ConnectionState, status: TvStatus?): Pair<String, Color> = when (conn) {
    is ConnectionState.Connected -> (if (status?.isOn == false) "Connected · standby" else "Connected") to Ui.success
    is ConnectionState.Connecting -> (if (conn.attempt > 1) "Reconnecting (${conn.attempt})…" else "Connecting…") to Ui.warning
    is ConnectionState.Retrying -> "Retrying in ${(conn.retryInMs + 999) / 1000}s" to Ui.warning
    is ConnectionState.PairingNeeded, is ConnectionState.IdentityChanged -> "Pairing needed" to Ui.danger
    ConnectionState.Idle -> "Offline" to Ui.textDim
}

private fun subtitle(model: String?, conn: ConnectionState, status: TvStatus?, shortcuts: List<AppShortcut>): String {
    val parts = mutableListOf<String>()
    model?.let { parts += it }
    if (conn is ConnectionState.Connected) {
        status?.currentApp?.let { parts += appName(it, shortcuts) }
        if (status?.volumeLevel != null) parts += "Vol ${status.volumeLevel}${status.volumeMax?.let { "/$it" } ?: ""}${if (status.muted == true) " (muted)" else ""}"
    }
    return parts.joinToString(" · ")
}

fun appName(pkg: String, shortcuts: List<AppShortcut>): String = shortcuts.firstOrNull { it.packageName == pkg }?.label ?: when (pkg) {
    "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.google.android.leanbacklauncher" -> "Home screen"
    "com.google.android.katniss" -> "Google Assistant"
    "com.google.android.apps.tv.dreamx" -> "Screensaver"
    "com.android.tv.settings" -> "TV settings"
    "com.android.vending" -> "Play Store"
    "android" -> "System dialog"
    else -> pkg
}

fun friendlyReason(c: ConnectionState): String = when (c) {
    is ConnectionState.Retrying -> when {
        c.reason.contains("Wi-Fi") || c.reason.contains("network") && c.reason.contains("phone") -> c.reason
        c.reason.startsWith("Couldn't reach") -> "TV isn't on the network. In standby it switches Wi-Fi off; turn it on with the TV remote and this app reconnects by itself."
        c.reason.contains("stopped responding") -> "The TV stopped responding. Reconnecting."
        else -> c.reason
    }
    else -> "Not connected."
}

@Composable
private fun ShortcutTile(s: AppShortcut, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    RemoteKey("Open ${s.label} on the TV", onClick, modifier.height(64.dp), color = Color(s.colorArgb), contentColor = Color(s.onColorArgb), shape = RoundedCornerShape(16.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color(s.onColorArgb), strokeWidth = 2.5.dp)
        else Text(s.label, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun IconKey(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    RemoteKey(label, onClick, modifier.height(56.dp)) { Icon(icon, null, Modifier.size(26.dp)) }
}

@Composable
private fun MediaKey(label: String, icon: ImageVector, onClick: () -> Unit) {
    RemoteKey(label, onClick, Modifier.size(width = 60.dp, height = 56.dp), color = Color.Transparent) { Icon(icon, null, Modifier.size(30.dp)) }
}

@Composable
private fun LabeledIcon(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DPad(app: TvRemoteApp) {
    val repo = app.repo
    Box(Modifier.size(270.dp).background(Ui.key, CircleShape)) {
        val arrow = Modifier.size(width = 104.dp, height = 84.dp)
        val side = Modifier.size(width = 84.dp, height = 104.dp)
        RemoteKey("Up", { repo.key(KeyCodes.DPAD_UP) }, arrow.align(Alignment.TopCenter), Hold.REPEAT, color = Color.Transparent, shape = RoundedCornerShape(40.dp)) { Icon(Icons.Rounded.ExpandLess, null, Modifier.size(44.dp)) }
        RemoteKey("Down", { repo.key(KeyCodes.DPAD_DOWN) }, arrow.align(Alignment.BottomCenter), Hold.REPEAT, color = Color.Transparent, shape = RoundedCornerShape(40.dp)) { Icon(Icons.Rounded.ExpandMore, null, Modifier.size(44.dp)) }
        RemoteKey("Left", { repo.key(KeyCodes.DPAD_LEFT) }, side.align(Alignment.CenterStart), Hold.REPEAT, color = Color.Transparent, shape = RoundedCornerShape(40.dp)) { Icon(Icons.Rounded.ChevronLeft, null, Modifier.size(44.dp)) }
        RemoteKey("Right", { repo.key(KeyCodes.DPAD_RIGHT) }, side.align(Alignment.CenterEnd), Hold.REPEAT, color = Color.Transparent, shape = RoundedCornerShape(40.dp)) { Icon(Icons.Rounded.ChevronRight, null, Modifier.size(44.dp)) }
        RemoteKey(
            "OK", { repo.key(KeyCodes.DPAD_CENTER) }, Modifier.size(104.dp).align(Alignment.Center), Hold.LONG,
            onLongStart = { repo.keyDown(KeyCodes.DPAD_CENTER) }, onLongEnd = { repo.keyUp(KeyCodes.DPAD_CENTER) },
            shape = CircleShape, color = Ui.accent, contentColor = Ui.onAccent,
        ) { Text("OK", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun VolumeRow(app: TvRemoteApp) {
    val repo = app.repo
    val status by repo.status.collectAsStateWithLifecycle()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteKey("Volume down", { repo.key(KeyCodes.VOLUME_DOWN) }, Modifier.weight(1f).height(60.dp), Hold.REPEAT) {
            Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, Modifier.size(28.dp))
        }
        RemoteKey(if (status?.muted == true) "Unmute" else "Mute", { repo.key(KeyCodes.VOLUME_MUTE) }, Modifier.weight(1f).height(60.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.AutoMirrored.Rounded.VolumeOff, null, Modifier.size(22.dp), tint = if (status?.muted == true) Ui.warning else Ui.text)
                Text(status?.volumeLevel?.let { "$it" } ?: "Mute", fontSize = 12.sp, color = Ui.textDim)
            }
        }
        RemoteKey("Volume up", { repo.key(KeyCodes.VOLUME_UP) }, Modifier.weight(1f).height(60.dp), Hold.REPEAT) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, Modifier.size(28.dp))
        }
    }
}

@Composable
private fun PowerKey(app: TvRemoteApp, modifier: Modifier) {
    val repo = app.repo
    val conn by repo.connection.collectAsStateWithLifecycle()
    val status by repo.status.collectAsStateWithLifecycle()
    val busy by repo.powerBusy.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        RemoteKey("Power options", { open = true }, Modifier.fillMaxWidth().height(56.dp), contentColor = Ui.danger) {
            if (busy != null) CircularProgressIndicator(Modifier.size(22.dp), color = Ui.danger, strokeWidth = 2.5.dp)
            else Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(26.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val connected = conn is ConnectionState.Connected
            DropdownMenuItem(
                text = { Text(if (status?.isOn == false) "Turn off (already in standby)" else "Turn off (standby)") },
                enabled = connected && busy == null, onClick = { open = false; repo.powerAndReport(false) },
            )
            DropdownMenuItem(text = { Text("Turn on") }, enabled = busy == null, onClick = { open = false; repo.powerAndReport(true) })
            DropdownMenuItem(
                text = {
                    Text(
                        "Your TV switches Wi-Fi off in standby (tested), so \"Turn on\" only works while it's still reachable. " +
                            "Otherwise use the TV remote; this app reconnects by itself.",
                        style = MaterialTheme.typography.bodySmall, color = Ui.textDim,
                    )
                },
                enabled = false, onClick = {},
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyboardSheet(app: TvRemoteApp, onDismiss: () -> Unit) {
    val repo = app.repo
    val status by repo.status.collectAsStateWithLifecycle()
    val imeOk = status?.has(com.abhinesh.tvremote.core.proto.RemoteMessages.Feature.IME) == true
    var live by rememberSaveable { mutableStateOf(true) }
    var text by rememberSaveable { mutableStateOf("") }
    var lastSent by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Live mode mirrors the whole field to the TV shortly after each change.
    LaunchedEffect(text, live) {
        if (!live || !imeOk || text == lastSent) return@LaunchedEffect
        delay(150)
        if (text.isEmpty()) repeat(lastSent.length.coerceAtMost(40)) { repo.key(KeyCodes.DEL) } else repo.sendText(text)
        lastSent = text
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ui.surface) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Type on the TV", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = live, onClick = { live = true }, label = { Text("Live text") }, enabled = imeOk)
                FilterChip(selected = !live, onClick = { live = false }, label = { Text("Key presses") })
            }
            Text(
                when {
                    !imeOk -> "This TV didn't enable keyboard input, so text is sent as individual key presses (letters, digits, basic symbols)."
                    live -> "Select a text field on the TV first (for example a search box). What you type here replaces that field's text."
                    else -> "Fallback: sends each character as a key press. Use this if live text doesn't appear on the TV."
                },
                color = Ui.textDim, style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true, label = { Text("Text") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (!live || !imeOk) { val bad = repo.typeWithKeys(text); if (bad.isNotEmpty()) repo.notify("Can't type these with key presses: $bad"); text = ""; lastSent = "" }
                    else repo.key(KeyCodes.ENTER)
                }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!live || !imeOk) {
                    Button(onClick = { val bad = repo.typeWithKeys(text); if (bad.isNotEmpty()) repo.notify("Can't type these with key presses: $bad"); text = ""; lastSent = "" }) { Text("Send keys") }
                }
                OutlinedButton(onClick = { repo.key(KeyCodes.DEL) }) { Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete on TV") }
                OutlinedButton(onClick = { repo.key(KeyCodes.ENTER) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, "Enter on TV") }
                TextButton(onClick = { text = "" }) { Text("Clear") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}
