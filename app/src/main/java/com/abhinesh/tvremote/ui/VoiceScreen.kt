package com.abhinesh.tvremote.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinesh.tvremote.TvRemoteApp
import com.abhinesh.tvremote.VoiceEntry
import com.abhinesh.tvremote.core.proto.RemoteMessages.Feature
import com.abhinesh.tvremote.core.voice.ExecResult.Outcome
import com.abhinesh.tvremote.voice.LanguageSupport
import com.abhinesh.tvremote.voice.SpeechLanguages
import com.abhinesh.tvremote.voice.SpeechPrefs
import com.abhinesh.tvremote.voice.SpeechUi
import com.abhinesh.tvremote.voice.TvVoiceUi
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp

@Composable
fun VoiceScreen(app: TvRemoteApp) {
    val context = LocalContext.current
    var permDenied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> permDenied = !granted }
    fun ensureMic(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        permission.launch(Manifest.permission.RECORD_AUDIO)
        return false
    }
    // Leaving the screen stops any microphone use at once.
    DisposableEffect(Unit) { onDispose { app.phoneSpeech.cancel(); app.tvVoice.cancel() } }

    val settings by app.store.settings.collectAsStateWithLifecycle()
    val log by app.repo.voiceLog.collectAsStateWithLifecycle()
    val state by app.phoneSpeech.state.collectAsStateWithLifecycle()
    var more by remember { mutableStateOf(false) }
    var tryIndex by remember { mutableStateOf(0) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenTitle("Voice")
        if (permDenied) {
            Column(Modifier.fillMaxWidth().background(Ui.danger.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text("Microphone permission is off, so voice can't be used.", color = Ui.danger)
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text("Open app permissions", color = Ui.accentText) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("en-IN" to "English", "te-IN" to "తెలుగు", "hi-IN" to "हिन्दी").forEach { (tag, name) ->
                Pill(name, settings.speechLanguage == tag, { app.store.updateSettings { it.copy(speechLanguage = tag) } }, Modifier.weight(1f))
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            BigSpeakButton(app, state, onPermissionDenied = { permDenied = true })
        }
        FeedbackCard(app, log.firstOrNull(), state)
        val tries = examples(settings.speechLanguage)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ui.surface).border(1.dp, Ui.border, RoundedCornerShape(20.dp))
                .clickable { tryIndex = (tryIndex + 1) % tries.size }.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Ui.accentText, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            ScriptText("Try: ${tries[tryIndex % tries.size]}", color = Ui.text, style = MaterialTheme.typography.bodyLarge)
        }
        TextButton(onClick = { more = !more }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (more) "Hide voice options" else "More voice options", color = Ui.accentText)
        }
        if (more) {
            if (app.phoneSpeech.autoDetectAvailable()) VoiceLanguageChips(app)
            log.drop(1).forEach { e -> FeedbackCard(app, e, null) }
            AssistantCard(app)
            SpeechEngineCard(app)
        }
    }
}

/** Large circular copper microphone: hold to speak, or tap and speak. */
@Composable
private fun BigSpeakButton(app: TvRemoteApp, state: SpeechUi, onPermissionDenied: () -> Unit) {
    val listening = state is SpeechUi.Listening
    val busy = state is SpeechUi.Preparing || state is SpeechUi.Processing
    val pulse = listeningPulse(listening, 1.06f, 700)
    Box(
        Modifier.size(212.dp).scale(if (listening) pulse else 1f).clip(CircleShape).background(Ui.accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.size(184.dp).clip(CircleShape)
                .background(if (listening) Brush.linearGradient(listOf(Ui.danger, Ui.accentDark)) else Ui.copper)
                .then(rememberSpeakGesture(app, onPermissionDenied)),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.Mic, null, Modifier.size(64.dp), tint = Ui.onAccent)
            Spacer(Modifier.height(8.dp))
            Text(
                when { listening -> "Listening…"; state is SpeechUi.Preparing -> "Starting…"; state is SpeechUi.Popup -> "Opening…"; busy -> "Got it…"; else -> "Hold to speak" },
                color = Ui.onAccent, style = MaterialTheme.typography.titleLarge, fontSize = 21.sp,
            )
        }
    }
}

/** "You said" card: the words heard, then the command's result as reported by the TV. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackCard(app: TvRemoteApp, e: VoiceEntry?, state: SpeechUi?) {
    GCard(padding = 20.dp) {
        Text("You said", color = Ui.textDim, style = MaterialTheme.typography.bodyLarge)
        val heard = when {
            state is SpeechUi.Listening -> state.partial.ifBlank { "…" }
            e != null -> e.heard
            else -> null
        }
        if (heard != null) ScriptText("“$heard”", style = MaterialTheme.typography.headlineSmall, color = Ui.text, fontSize = 24.sp)
        else Text("Nothing yet", style = MaterialTheme.typography.titleLarge, color = Ui.textMuted)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ui.border))
        when {
            state is SpeechUi.Error -> ResultLine(Icons.Rounded.ErrorOutline, Ui.warning, state.message)
            e == null -> Text("Press the button and say a command, like \"volume up\" or \"open Netflix\".", color = Ui.textDim)
            e.running -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Ui.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp)); ScriptText(e.understood, color = Ui.textDim)
            }
            else -> {
                val (icon, color) = when (e.outcome) {
                    Outcome.CONFIRMED -> Icons.Rounded.CheckCircle to Ui.success
                    Outcome.SENT -> Icons.AutoMirrored.Rounded.Send to Ui.text
                    Outcome.NOTHING_TO_DO -> Icons.Rounded.Info to Ui.textDim
                    Outcome.NEEDS_YOU -> Icons.AutoMirrored.Rounded.HelpOutline to Ui.warning
                    else -> Icons.Rounded.ErrorOutline to Ui.danger
                }
                ResultLine(icon, color, e.message.ifEmpty { e.understood }, if (e.options.isEmpty()) e.understood else null)
                if (e.options.isNotEmpty()) {
                    ScriptText(e.understood, color = Ui.warning, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        e.options.forEach { opt -> Pill(opt.summary, false, { app.repo.chooseOption(e.id, opt) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultLine(icon: ImageVector, color: Color, text: String, detail: String? = null) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            ScriptText(text, color = Ui.text, style = MaterialTheme.typography.bodyLarge)
            detail?.let { ScriptText(it, color = Ui.textMuted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Compact result card used by the Remote tab. */
@Composable
fun EntryView(e: VoiceEntry, onChoose: (com.abhinesh.tvremote.core.voice.VoiceAction) -> Unit) {
    val (icon, color) = when (e.outcome) {
        null -> Icons.Rounded.Info to Ui.textDim
        Outcome.CONFIRMED -> Icons.Rounded.CheckCircle to Ui.success
        Outcome.SENT -> Icons.AutoMirrored.Rounded.Send to Ui.text
        Outcome.NOTHING_TO_DO -> Icons.Rounded.Info to Ui.textDim
        Outcome.NEEDS_YOU -> Icons.AutoMirrored.Rounded.HelpOutline to Ui.warning
        else -> Icons.Rounded.ErrorOutline to Ui.danger
    }
    GCard(padding = 14.dp) {
        ScriptText("“${e.heard}”", fontWeight = FontWeight.SemiBold)
        ResultLine(icon, color, if (e.running) "Running ${e.understood}…" else e.message.ifEmpty { e.understood })
        if (e.options.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            e.options.take(3).forEach { opt -> Pill(opt.summary, false, { onChoose(opt) }) }
        }
    }
}

/** Google Assistant on the TV: opened with the Search key, which works on the MiTV-AXFR2. */
@Composable
private fun AssistantCard(app: TvRemoteApp) {
    Card {
        Text("Google Assistant on the TV", style = MaterialTheme.typography.titleMedium)
        Text(
            "Opens the TV's own Assistant. Talk to it with the TV remote's mic. You can also say \"open Assistant\" here. " +
                "Your TV ignores voice sent from phone apps (tested), so use the button above for TV commands.",
            color = Ui.textDim, style = MaterialTheme.typography.bodySmall,
        )
        OutlineCopperButton("Open Assistant on TV", { app.repo.openAssistant() }, icon = Icons.Rounded.AutoAwesome)
    }
}

@Composable
private fun TvVoiceCard(app: TvRemoteApp, ensureMic: () -> Boolean) {
    val voice = app.tvVoice
    val state by voice.state.collectAsStateWithLifecycle()
    val status by app.repo.status.collectAsStateWithLifecycle()
    val settings by app.store.settings.collectAsStateWithLifecycle()
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.RecordVoiceOver, null, tint = Ui.accent)
            Text("  TV voice search", style = MaterialTheme.typography.titleMedium)
        }
        Text("Your voice is streamed to the TV's Google Assistant over the remote connection. The TV does the recognition, in the TV's Assistant language.", color = Ui.textDim, style = MaterialTheme.typography.bodySmall)
        val support = when {
            status == null -> "Connect to the TV to check support." to Ui.textDim
            status!!.has(Feature.VOICE) -> "Your TV accepted voice input during connection setup." to Ui.success
            !settings.tvVoiceEnabled -> "TV voice is turned off in Settings." to Ui.warning
            else -> "Your TV didn't offer voice input to remote apps. Use phone commands instead." to Ui.warning
        }
        Text(support.first, color = support.second, style = MaterialTheme.typography.bodySmall)
        Text("Not yet confirmed on your TV: Assistant opens and receives the audio, but whether it understands it hasn't been verified.", color = Ui.textDim, style = MaterialTheme.typography.bodySmall)
        PushToTalk(
            listening = state is TvVoiceUi.Streaming,
            preparing = state is TvVoiceUi.Starting,
            level = (state as? TvVoiceUi.Streaming)?.level ?: 0f,
            label = "Hold and speak to the TV",
            onStart = { if (ensureMic()) voice.start() },
            onStop = { voice.stop() },
            onCancel = { voice.cancel() },
        )
        when (val s = state) {
            TvVoiceUi.Starting -> Text("Asking the TV to start listening…", color = Ui.warning)
            is TvVoiceUi.Streaming -> Text("● Microphone on, streaming to TV ${"%.1f".format(s.seconds)} s. Release to finish.", color = Ui.danger)
            is TvVoiceUi.Done -> Text("Sent ${"%.1f".format(s.seconds)} s of audio. Microphone off. Check the TV for the result.")
            is TvVoiceUi.Error -> Text(s.message, color = Ui.warning)
            TvVoiceUi.Idle -> {}
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeechEngineCard(app: TvRemoteApp) {
    val speech = app.phoneSpeech
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val services = remember { runCatching { speech.services() }.getOrDefault(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    val support by produceState<Map<String, LanguageSupport?>?>(null, refresh) {
        value = SpeechLanguages.ALL.associate { (tag, _) -> tag to speech.languageSupport(tag) }
    }
    Card {
        Text("Speech engine and languages", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val choices = buildList {
                add("auto" to "Automatic")
                if (speech.onDeviceAvailable()) add("ondevice" to "On-device")
                add("default" to "Phone default")
                if (speech.popupAvailable()) add("popup" to "Google voice popup")
                services.forEach { (c, label) -> add(c.flattenToString() to label) }
            }
            choices.forEach { (key, label) ->
                FilterChip(selected = settings.speechEngine == key, onClick = { app.store.updateSettings { it.copy(speechEngine = key) } }, label = { Text(label) })
            }
        }
        Text(
            "Automatic uses the on-device recogniser for a language it has installed, otherwise your phone's default speech service. " +
                "On-device recognition stays on the phone; the phone's speech service (usually Google) may send audio online.",
            color = Ui.textDim, style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("On-device only")
                Text(if (speech.onDeviceAvailable()) "Never send audio online." else "No on-device recogniser on this phone.", color = Ui.textDim, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = settings.onDeviceOnly, onCheckedChange = { v -> app.store.updateSettings { it.copy(onDeviceOnly = v) } })
        }
        SpeechLanguages.ALL.forEach { (tag, name) ->
            val s = support?.get(tag)
            val text = when {
                support == null -> "checking…"
                !speech.onDeviceAvailable() -> "no on-device recogniser; uses the phone's speech service"
                s == null -> "on-device support unknown (Android 12 can't report it); tried on-device first, then the speech service"
                s.installed -> "installed on-device ✓"
                s.pending -> "download in progress"
                s.supportedOnDevice -> "can be downloaded for offline use"
                else -> "not available on-device; uses the phone's speech service" + if (s.online) " (online)" else ""
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$name: $text", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (s?.supportedOnDevice == true && !s.installed && !s.pending) {
                    OutlinedButton(onClick = {
                        if (speech.downloadLanguage(tag)) app.repo.notify("Download requested. Android shows its progress; check back here afterwards.")
                        else app.repo.notify("This phone can't download speech languages from here.")
                        refresh++
                    }) { Text("Download") }
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT < 34) Text("Auto-detect needs Android 14 or newer, so choose the language you'll speak.", color = Ui.textDim, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { refresh++ }) { Text("Check again") }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ui.surface, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

/** Hold to record; release to send; slide off the button to cancel. */
@Composable
private fun PushToTalk(listening: Boolean, preparing: Boolean, level: Float, label: String, onStart: () -> Unit, onStop: () -> Unit, onCancel: () -> Unit) {
    val haptic = LocalHaptics.current
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val cancel by rememberUpdatedState(onCancel)
    val pulse = listeningPulse(listening, 1.12f, 600)
    LaunchedEffect(listening) { if (listening) haptic(true) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(96.dp)
                    .scale(if (listening) pulse + level * 0.15f else 1f)
                    .background(when { listening -> Ui.danger; preparing -> Ui.warning; else -> Ui.accent }, CircleShape)
                    .semantics { contentDescription = if (listening) "Microphone on. Speak now, release to finish" else label }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            haptic(false)
                            start()
                            var ended = false
                            try {
                                val up = waitForUpOrCancellation()
                                ended = true
                                if (up != null) stop() else cancel()
                            } finally {
                                if (!ended) cancel()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Mic, null, Modifier.size(44.dp), tint = if (listening || preparing) Color.White else Ui.onAccent) }
            Text(
                when { listening -> "Speak now · release to send · slide off to cancel"; preparing -> "Wait…"; else -> label },
                color = if (listening) Ui.danger else Ui.textDim, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun examples(lang: String) = when (lang) {
    "te-IN" -> listOf("యూట్యూబ్ ఓపెన్ చెయ్యి", "సౌండ్ పెంచు", "యూట్యూబ్‌లో పుష్ప సాంగ్స్ పెట్టు", "ఈ యాప్ క్లోజ్ చేయి")
    "hi-IN" -> listOf("यूट्यूब खोलो", "आवाज़ बढ़ाओ", "नेटफ्लिक्स पर स्ट्रेंजर थिंग्स दिखाओ", "ये ऐप बंद करो")
    else -> listOf("Open Netflix", "Volume up", "Play Jawan on JioHotstar", "Close this app", "Go home")
}