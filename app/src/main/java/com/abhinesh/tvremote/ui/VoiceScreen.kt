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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Voice", style = MaterialTheme.typography.titleLarge)
        if (permDenied) {
            Column(Modifier.fillMaxWidth().background(Ui.danger.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text("Microphone permission is off, so voice can't be used. All other remote features still work.", color = Ui.danger)
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text("Open app permissions") }
            }
        }
        PhoneCommandsCard(app, ::ensureMic)
        TvVoiceCard(app, ::ensureMic)
        SpeechEngineCard(app)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneCommandsCard(app: TvRemoteApp, ensureMic: () -> Boolean) {
    val speech = app.phoneSpeech
    val state by speech.state.collectAsStateWithLifecycle()
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val shortcuts by app.store.shortcuts.collectAsStateWithLifecycle()
    val log by app.repo.voiceLog.collectAsStateWithLifecycle()
    val autoWorks by speech.autoDetectWorks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(autoWorks) {
        if (autoWorks != settings.autoDetectWorks) app.store.updateSettings { it.copy(autoDetectWorks = autoWorks) }
        if (autoWorks == false && settings.speechLanguage == SpeechLanguages.AUTO) {
            app.store.updateSettings { it.copy(speechLanguage = "en-IN") }
            app.repo.notify("Your phone's recogniser didn't report the language it detected, so auto-detect isn't offered. Choose a language.")
        }
    }
    LaunchedEffect(state) {
        val heard = state as? SpeechUi.Heard ?: return@LaunchedEffect
        app.repo.handleSpeech(heard.alternatives, heard.engine, heard.detectedLanguage ?: heard.language)
        speech.clear()
    }

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Mic, null, tint = Ui.accent)
            Text("  Phone commands", style = MaterialTheme.typography.titleMedium)
        }
        Text("Your phone recognises the words, this app works out the command, and the TV gets normal remote buttons.", color = Ui.textDim, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SpeechLanguages.ALL.forEach { (tag, name) ->
                FilterChip(selected = settings.speechLanguage == tag, onClick = { app.store.updateSettings { it.copy(speechLanguage = tag) } }, label = { Text(name) })
            }
            if (speech.autoDetectAvailable()) {
                FilterChip(
                    selected = settings.speechLanguage == SpeechLanguages.AUTO,
                    onClick = { app.store.updateSettings { it.copy(speechLanguage = SpeechLanguages.AUTO) } },
                    label = { Text(if (autoWorks == true) "Auto-detect" else "Auto-detect (untested)") },
                )
            }
        }
        Text(examples(settings.speechLanguage), color = Ui.textDim, style = MaterialTheme.typography.bodySmall)
        PushToTalk(
            listening = state is SpeechUi.Listening,
            preparing = state is SpeechUi.Preparing,
            level = (state as? SpeechUi.Listening)?.level ?: 0f,
            label = "Hold, wait for \"Speak now\", then speak",
            onStart = {
                // UNDISPATCHED: the "holding" flag is set before a quick release can call stop().
                if (ensureMic()) scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    speech.start(SpeechPrefs(settings.speechLanguage, settings.speechEngine, settings.onDeviceOnly, biasing(shortcuts.map { it.label })))
                }
            },
            onStop = { speech.stop() },
            onCancel = { speech.cancel() },
        )
        when (val s = state) {
            is SpeechUi.Preparing -> Text("Starting the microphone… wait for \"Speak now\".", color = Ui.warning)
            is SpeechUi.Listening -> Text("● Speak now. Recognised ${s.engine}. ${s.partial}", color = Ui.danger, fontWeight = FontWeight.Medium)
            is SpeechUi.Processing -> Text("Microphone off. Recognising ${s.engine}…", color = Ui.textDim)
            is SpeechUi.Error -> Text(s.message, color = Ui.warning)
            else -> {}
        }
        if (speech.lastEngineNote.isNotEmpty() && state !is SpeechUi.Idle) Text(speech.lastEngineNote, color = Ui.textDim, style = MaterialTheme.typography.bodySmall)
        log.forEach { e -> EntryView(e) { action -> app.repo.chooseOption(e.id, action) } }
    }
}

private fun examples(lang: String) = when (lang) {
    "te-IN" -> "\"యూట్యూబ్ ఓపెన్ చెయ్యి\" · \"సౌండ్ పెంచు\" · \"సౌండ్ తగ్గించు\" · \"మ్యూట్\" · \"ఈ యాప్ క్లోజ్ చేయి\" · \"హోమ్\" · \"వెనక్కి\" · \"పాజ్\""
    "hi-IN" -> "\"यूट्यूब खोलो\" · \"आवाज़ बढ़ाओ\" · \"आवाज़ कम करो\" · \"म्यूट करो\" · \"ये ऐप बंद करो\" · \"होम\" · \"वापस जाओ\" · \"रोको\""
    else -> "\"Open Netflix\" · \"increase volume\" · \"decrease volume\" · \"mute\" · \"unmute\" · \"close this app\" · \"go home\" · \"back\" · \"pause\""
}

/** Hints for on-device recognisers (Android 13+): app names and command words in all three languages. */
private fun biasing(appLabels: List<String>) = appLabels + listOf(
    "Netflix", "YouTube", "JioHotstar", "Hotstar", "Prime Video", "open", "close", "volume up", "volume down", "mute", "unmute",
    "యూట్యూబ్", "నెట్‌ఫ్లిక్స్", "హాట్‌స్టార్", "ప్రైమ్ వీడియో", "ఓపెన్", "క్లోజ్", "సౌండ్", "పెంచు", "తగ్గించు",
    "यूट्यूब", "नेटफ्लिक्स", "हॉटस्टार", "प्राइम वीडियो", "खोलो", "बंद करो", "आवाज़", "बढ़ाओ", "कम करो",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryView(e: VoiceEntry, onChoose: (com.abhinesh.tvremote.core.voice.VoiceAction) -> Unit) {
    val (label, color) = when (e.outcome) {
        null -> "Running…" to Ui.textDim
        Outcome.CONFIRMED -> "✓ Confirmed by the TV" to Ui.success
        Outcome.SENT -> "➜ Sent" to Ui.text
        Outcome.NOTHING_TO_DO -> "• Nothing to do" to Ui.textDim
        Outcome.NEEDS_YOU -> "? Needs your answer" to Ui.warning
        Outcome.FAILED -> "✗ Failed" to Ui.danger
        Outcome.UNSUPPORTED -> "✗ Not supported" to Ui.danger
    }
    Column(Modifier.fillMaxWidth().background(Ui.key, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("You said: \"${e.heard}\"", fontWeight = FontWeight.Medium)
        Text("${SpeechLanguages.name(e.language)} · recognised ${e.engine}", color = Ui.textDim, style = MaterialTheme.typography.bodySmall)
        Text("Understood: ${e.understood}")
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (e.running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(if (e.running) "  Running…" else label, color = color, fontWeight = FontWeight.Medium)
        }
        if (e.message.isNotEmpty()) Text(e.message, style = MaterialTheme.typography.bodySmall)
        if (e.options.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            e.options.forEach { opt -> AssistChip(onClick = { onChoose(opt) }, label = { Text(opt.summary) }) }
        }
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
    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(1f, 1.12f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
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
