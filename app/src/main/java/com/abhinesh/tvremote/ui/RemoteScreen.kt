package com.abhinesh.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.rounded.AutoAwesome
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
fun RemoteScreen(app: TvRemoteApp, onOpenSettings: () -> Unit = {}) {
    val repo = app.repo
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val shortcuts by app.store.shortcuts.collectAsStateWithLifecycle()
    val launching by repo.launching.collectAsStateWithLifecycle()
    var keyboard by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusHeader(app, onOpenSettings)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (settings.swipePad) SwipePad(onDirection = { repo.key(it) }, onTap = { repo.key(KeyCodes.DPAD_CENTER) })
                else SegmentedDPad(app)
            }
            VolumeRocker(app)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteKey("Back", { repo.key(KeyCodes.BACK) }, Modifier.weight(1f).height(66.dp), hold = Hold.LONG,
                onLongStart = { repo.keyDown(KeyCodes.BACK) }, onLongEnd = { repo.keyUp(KeyCodes.BACK) }, shape = RoundedCornerShape(18.dp)) {
                StackedIcon(Icons.AutoMirrored.Rounded.Undo, "Back")
            }
            RemoteKey("Home", { repo.key(KeyCodes.HOME) }, Modifier.weight(1f).height(66.dp), hold = Hold.LONG,
                onLongStart = { repo.keyDown(KeyCodes.HOME) }, onLongEnd = { repo.keyUp(KeyCodes.HOME) }, shape = RoundedCornerShape(18.dp)) {
                StackedIcon(Icons.Outlined.Home, "Home")
            }
            RemoteKey("Play or pause", { repo.key(KeyCodes.MEDIA_PLAY_PAUSE) }, Modifier.weight(1f).height(66.dp), shape = RoundedCornerShape(18.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(24.dp)); Icon(Icons.Rounded.Pause, null, Modifier.size(20.dp))
                    }
                    Text("Play / Pause", style = MaterialTheme.typography.labelLarge, color = Ui.text)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { HoldToSpeakBar(app) }
            AssistantKey(app)
        }
        RemoteVoiceLine(app)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            shortcuts.take(4).forEach { s -> OttTile(s, launching == s.id, Modifier.weight(1f), compact = true) { repo.launch(s) } }
            repeat(4 - shortcuts.take(4).size) { Spacer(Modifier.weight(1f)) }
        }

        TextButton(onClick = { more = !more }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (more) "Fewer controls" else "More controls", color = Ui.accentText)
        }
        if (more) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconKey("Keyboard", Icons.Outlined.Keyboard, Modifier.weight(1f)) { keyboard = true }
                IconKey("Input source", Icons.AutoMirrored.Rounded.Input, Modifier.weight(1f)) { repo.key(KeyCodes.TV_INPUT) }
                IconKey("TV settings", Icons.Outlined.Settings, Modifier.weight(1f)) { repo.key(KeyCodes.SETTINGS) }
                IconKey(if (settings.swipePad) "Switch to D-pad" else "Switch to swipe pad",
                    if (settings.swipePad) Icons.Rounded.Gamepad else Icons.Rounded.TouchApp, Modifier.weight(1f)) {
                    app.store.updateSettings { it.copy(swipePad = !it.swipePad) }
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ui.key), horizontalArrangement = Arrangement.SpaceEvenly) {
                MediaKey("Previous", Icons.Rounded.SkipPrevious) { repo.key(KeyCodes.MEDIA_PREVIOUS) }
                MediaKey("Rewind", Icons.Rounded.FastRewind) { repo.key(KeyCodes.MEDIA_REWIND) }
                MediaKey("Fast forward", Icons.Rounded.FastForward) { repo.key(KeyCodes.MEDIA_FAST_FORWARD) }
                MediaKey("Next", Icons.Rounded.SkipNext) { repo.key(KeyCodes.MEDIA_NEXT) }
            }
        }
        Spacer(Modifier.height(4.dp))
    }

    if (keyboard) KeyboardSheet(app) { keyboard = false }
}

@Composable
private fun StackedIcon(icon: ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(26.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Ui.text)
    }
}

/** Opens Google Assistant on the TV itself. */
@Composable
private fun AssistantKey(app: TvRemoteApp) {
    RemoteKey(
        "Open Google Assistant on the TV", { app.repo.openAssistant() }, Modifier.size(width = 84.dp, height = 76.dp),
        shape = RoundedCornerShape(22.dp), color = Ui.key, contentColor = Ui.accentText,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(28.dp))
            Text("Assistant", style = MaterialTheme.typography.labelMedium, color = Ui.text, fontSize = 11.sp)
        }
    }
}

/** Copper bar on the Remote tab: hold to speak, or tap once to talk until you stop. */
@Composable
private fun HoldToSpeakBar(app: TvRemoteApp) {
    val state by app.phoneSpeech.state.collectAsStateWithLifecycle()
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val listening = state is com.abhinesh.tvremote.voice.SpeechUi.Listening
    val title = when (state) {
        is com.abhinesh.tvremote.voice.SpeechUi.Preparing -> "Starting mic…"
        is com.abhinesh.tvremote.voice.SpeechUi.Listening -> "Speak now…"
        is com.abhinesh.tvremote.voice.SpeechUi.Processing -> "Got it…"
        is com.abhinesh.tvremote.voice.SpeechUi.Popup -> "Opening voice…"
        else -> "Hold to speak"
    }
    Row(
        Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(22.dp))
            .background(if (listening) androidx.compose.ui.graphics.Brush.linearGradient(listOf(Ui.danger, Ui.accentDark)) else Ui.copper)
            .then(rememberSpeakGesture(app)).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Mic, null, Modifier.size(32.dp), tint = Ui.onAccent)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Ui.onAccent, style = MaterialTheme.typography.titleLarge, fontSize = 20.sp, maxLines = 1)
            Row {
                com.abhinesh.tvremote.voice.SpeechLanguages.ALL.forEachIndexed { i, (tag, _) ->
                    val label = when (tag) { "te-IN" -> "తెలుగు"; "hi-IN" -> "हिन्दी"; else -> "English" }
                    if (i > 0) Text(" · ", color = Ui.onAccent.copy(alpha = 0.8f))
                    ScriptText(label, color = Ui.onAccent.copy(alpha = if (settings.speechLanguage == tag) 1f else 0.7f),
                        fontWeight = if (settings.speechLanguage == tag) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Large direction pad made of four rounded segments around a raised OK button. */
@Composable
private fun SegmentedDPad(app: TvRemoteApp) {
    val repo = app.repo
    val seg = Ui.key
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        val s = maxWidth
        val long = s * 0.46f
        val thick = s * 0.29f
        val r = RoundedCornerShape(s * 0.12f)
        RemoteKey("Up", { repo.key(KeyCodes.DPAD_UP) }, Modifier.align(Alignment.TopCenter).size(long, thick), Hold.REPEAT, shape = r, color = seg) {
            Icon(Icons.Rounded.KeyboardArrowUp, null, Modifier.size(s * 0.15f))
        }
        RemoteKey("Down", { repo.key(KeyCodes.DPAD_DOWN) }, Modifier.align(Alignment.BottomCenter).size(long, thick), Hold.REPEAT, shape = r, color = seg) {
            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(s * 0.15f))
        }
        RemoteKey("Left", { repo.key(KeyCodes.DPAD_LEFT) }, Modifier.align(Alignment.CenterStart).size(thick, long), Hold.REPEAT, shape = r, color = seg) {
            Icon(Icons.Rounded.ChevronLeft, null, Modifier.size(s * 0.15f))
        }
        RemoteKey("Right", { repo.key(KeyCodes.DPAD_RIGHT) }, Modifier.align(Alignment.CenterEnd).size(thick, long), Hold.REPEAT, shape = r, color = seg) {
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(s * 0.15f))
        }
        Box(Modifier.size(s * 0.36f).clip(RoundedCornerShape(s * 0.1f)).background(Ui.bg), contentAlignment = Alignment.Center) {
            RemoteKey(
                "OK", { repo.key(KeyCodes.DPAD_CENTER) }, Modifier.size(s * 0.3f), Hold.LONG,
                onLongStart = { repo.keyDown(KeyCodes.DPAD_CENTER) }, onLongEnd = { repo.keyUp(KeyCodes.DPAD_CENTER) },
                shape = RoundedCornerShape(s * 0.08f), color = Ui.keyPressed,
            ) { Text("OK", fontSize = (s.value * 0.1f).sp, fontWeight = FontWeight.Bold, color = Ui.text) }
        }
    }
}

/** Vertical rocker: volume up, mute, volume down. */
@Composable
private fun VolumeRocker(app: TvRemoteApp) {
    val repo = app.repo
    val status by repo.status.collectAsStateWithLifecycle()
    Column(Modifier.width(72.dp).clip(RoundedCornerShape(24.dp)).background(Ui.key), horizontalAlignment = Alignment.CenterHorizontally) {
        RemoteKey("Volume up", { repo.key(KeyCodes.VOLUME_UP) }, Modifier.fillMaxWidth().height(78.dp), Hold.REPEAT, shape = RoundedCornerShape(24.dp), color = Ui.key) {
            Icon(Icons.Rounded.Add, null, Modifier.size(30.dp))
        }
        RemoteKey(if (status?.muted == true) "Unmute" else "Mute", { repo.key(KeyCodes.VOLUME_MUTE) }, Modifier.fillMaxWidth().height(66.dp), shape = RoundedCornerShape(16.dp), color = Ui.key) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (status?.muted == true) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, null, Modifier.size(24.dp),
                    tint = if (status?.muted == true) Ui.warning else Ui.text)
                status?.volumeLevel?.let { Text("$it", fontSize = 11.sp, color = Ui.textDim) }
            }
        }
        RemoteKey("Volume down", { repo.key(KeyCodes.VOLUME_DOWN) }, Modifier.fillMaxWidth().height(78.dp), Hold.REPEAT, shape = RoundedCornerShape(24.dp), color = Ui.key) {
            Icon(Icons.Rounded.Remove, null, Modifier.size(30.dp))
        }
    }
}

/** Voice result on the Remote tab: shown while listening and for 15 s after a command. */
@Composable
private fun RemoteVoiceLine(app: TvRemoteApp) {
    val state by app.phoneSpeech.state.collectAsStateWithLifecycle()
    val log by app.repo.voiceLog.collectAsStateWithLifecycle()
    var recent by remember { mutableStateOf(false) }
    val latest = log.firstOrNull()
    LaunchedEffect(latest?.id, latest?.running) {
        if (latest == null) return@LaunchedEffect
        recent = true
        if (!latest.running && latest.options.isEmpty()) { delay(15_000); recent = false }
    }
    if (state !is com.abhinesh.tvremote.voice.SpeechUi.Idle || recent) VoiceStatus(app, showAll = false)
}

@Composable
fun StatusHeader(app: TvRemoteApp, onOpenSettings: () -> Unit = {}) {
    val tv by app.store.tv.collectAsStateWithLifecycle()
    val conn by app.repo.connection.collectAsStateWithLifecycle()
    val status by app.repo.status.collectAsStateWithLifecycle()
    val shortcuts by app.store.shortcuts.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tv?.name ?: "TV", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val (label, color) = chip(conn, status)
            StatusDot(label, color)
            subtitle(tv?.model, conn, status, shortcuts).takeIf { it.isNotEmpty() }?.let {
                Text(it, color = Ui.textMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings", tint = Ui.textDim) }
        PowerKey(app, Modifier.size(58.dp))
    }
    when (val c = conn) {
        is ConnectionState.Retrying, is ConnectionState.Idle -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(friendlyReason(c), color = Ui.warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { app.repo.reconnectNow() }) { Text("Retry now", color = Ui.accentText) }
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
    "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.google.android.leanbacklauncher", "com.mitv.tvhome" -> "Home screen"
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
        RemoteKey(
            "Power options", { open = true },
            Modifier.fillMaxSize().border(1.5.dp, Ui.accent, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp), color = Ui.accentDark.copy(alpha = 0.22f), contentColor = Ui.accentText,
        ) {
            if (busy != null) CircularProgressIndicator(Modifier.size(22.dp), color = Ui.accentText, strokeWidth = 2.5.dp)
            else Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(28.dp))
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
