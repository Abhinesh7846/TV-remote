package com.abhinesh.tvremote.ui

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinesh.tvremote.TvRemoteApp
import com.abhinesh.tvremote.voice.SpeechLanguages
import com.abhinesh.tvremote.voice.SpeechPrefs
import com.abhinesh.tvremote.voice.SpeechUi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Runs recognised phone speech through the interpreter, whichever tab started it. Lives at the app root. */
@Composable
fun VoiceEffects(app: TvRemoteApp) {
    val speech = app.phoneSpeech
    val state by speech.state.collectAsStateWithLifecycle()
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val autoWorks by speech.autoDetectWorks.collectAsStateWithLifecycle()
    // Google's voice popup, opened when the in-app recognisers can't serve this phone.
    val popup = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        speech.popupResult(r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS))
    }
    LaunchedEffect(state) {
        when (val s = state) {
            is SpeechUi.Heard -> {
                app.repo.handleSpeech(s.alternatives, s.engine, s.detectedLanguage ?: s.language)
                speech.clear()
            }
            is SpeechUi.Popup -> runCatching { popup.launch(speech.popupIntent(s.language)) }
                .onFailure { speech.popupResult(null); app.repo.notify("Couldn't open Google voice input on this phone.") }
            else -> {}
        }
    }
    LaunchedEffect(autoWorks) {
        if (autoWorks != settings.autoDetectWorks) app.store.updateSettings { it.copy(autoDetectWorks = autoWorks) }
        if (autoWorks == false && settings.speechLanguage == SpeechLanguages.AUTO) {
            app.store.updateSettings { it.copy(speechLanguage = "en-IN") }
            app.repo.notify("Your phone's recogniser didn't report the language it detected, so auto-detect isn't offered. Choose a language.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceLanguageChips(app: TvRemoteApp) {
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val autoWorks by app.phoneSpeech.autoDetectWorks.collectAsStateWithLifecycle()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
        SpeechLanguages.ALL.forEach { (tag, name) ->
            FilterChip(selected = settings.speechLanguage == tag, onClick = { app.store.updateSettings { it.copy(speechLanguage = tag) } }, label = { Text(name) })
        }
        if (app.phoneSpeech.autoDetectAvailable()) {
            FilterChip(
                selected = settings.speechLanguage == SpeechLanguages.AUTO,
                onClick = { app.store.updateSettings { it.copy(speechLanguage = SpeechLanguages.AUTO) } },
                label = { Text(if (autoWorks == true) "Auto" else "Auto (untested)") },
            )
        }
    }
}

/**
 * Tap to talk: tap once and speak; recognition stops by itself when you stop talking.
 * Tap again while listening to finish now; the ✕ cancels. Never listens in the background.
 */
@Composable
fun VoiceMicButton(app: TvRemoteApp, size: Dp, onPermissionDenied: () -> Unit = {}) {
    val context = LocalContext.current
    val speech = app.phoneSpeech
    val state by speech.state.collectAsStateWithLifecycle()
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val shortcuts by app.store.shortcuts.collectAsStateWithLifecycle()
    val haptic = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) { onPermissionDenied(); app.repo.notify("Microphone permission is off, so voice can't be used.") }
    }
    val listening = state is SpeechUi.Listening
    val busy = state is SpeechUi.Preparing || state is SpeechUi.Processing
    val pulse = listeningPulse(listening, 1.1f, 600)
    LaunchedEffect(listening) { if (listening) haptic(true) }

    Box(contentAlignment = Alignment.TopEnd) {
        Box(
            Modifier.size(size).scale(if (listening) pulse + ((state as? SpeechUi.Listening)?.level ?: 0f) * 0.12f else 1f)
                .clip(CircleShape)
                .background(when { listening -> Ui.danger; busy -> Ui.warning; else -> Ui.accent })
                .semantics { contentDescription = if (listening) "Listening. Tap to finish" else "Tap and speak a command" }
                .clickable {
                    when (state) {
                        is SpeechUi.Listening, is SpeechUi.Preparing -> speech.stop()
                        is SpeechUi.Processing -> {}
                        else -> {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                permission.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                haptic(false)
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    speech.start(SpeechPrefs(settings.speechLanguage, settings.speechEngine, settings.onDeviceOnly, voiceBiasing(shortcuts.map { it.label })))
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Mic, null, Modifier.size(size * 0.45f), tint = if (listening || busy) Color.White else Ui.onAccent) }
        if (listening || busy) {
            Box(
                Modifier.size(size * 0.3f).clip(CircleShape).background(Ui.keyPressed).clickable { speech.cancel() }
                    .semantics { contentDescription = "Cancel" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, null, Modifier.size(size * 0.18f), tint = Ui.text) }
        }
    }
}

/**
 * Shared speak gesture for the copper buttons. Holding listens until release; a quick tap
 * listens until you stop talking; tapping while listening finishes; sliding off cancels.
 */
@Composable
fun rememberSpeakGesture(app: TvRemoteApp, onPermissionDenied: () -> Unit = {}): Modifier {
    val context = LocalContext.current
    val speech = app.phoneSpeech
    val haptic = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val state by speech.state.collectAsStateWithLifecycle()
    val currentState by androidx.compose.runtime.rememberUpdatedState(state)
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val shortcuts by app.store.shortcuts.collectAsStateWithLifecycle()
    val prefs by androidx.compose.runtime.rememberUpdatedState(
        SpeechPrefs(settings.speechLanguage, settings.speechEngine, settings.onDeviceOnly, voiceBiasing(shortcuts.map { it.label })),
    )
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) { onPermissionDenied(); app.repo.notify("Microphone permission is off, so voice can't be used.") }
    }
    return Modifier
        .semantics { contentDescription = "Hold to speak a command" }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                val s = currentState
                // Tap while listening: finish now (or cancel if nothing was said yet).
                if (s is SpeechUi.Listening || s is SpeechUi.Preparing) { speech.stop(); waitForUpOrCancellation(); return@awaitEachGesture }
                if (s is SpeechUi.Processing || s is SpeechUi.Popup) { waitForUpOrCancellation(); return@awaitEachGesture }
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    waitForUpOrCancellation()
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                    return@awaitEachGesture
                }
                haptic(false)
                scope.launch(start = CoroutineStart.UNDISPATCHED) { speech.start(prefs) }
                // Hold: lifting your finger after speaking finishes. Tap or early release: it keeps
                // listening until you stop talking. Only a slide off the button (or a scroll) cancels.
                if (waitForUpOrCancellation() != null) speech.release() else speech.cancel()
            }
        }
}

/** One short line for the mic state, then the latest command result. */
@Composable
fun VoiceStatus(app: TvRemoteApp, showAll: Boolean) {
    val state by app.phoneSpeech.state.collectAsStateWithLifecycle()
    val log by app.repo.voiceLog.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val s = state) {
            is SpeechUi.Preparing -> Text("Starting microphone…", color = Ui.warning, fontWeight = FontWeight.Medium)
            is SpeechUi.Listening -> Text(if (s.partial.isBlank()) "Listening… speak now" else "\"${s.partial}\"", color = Ui.danger, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            is SpeechUi.Processing -> Text("Got it…", color = Ui.textDim)
            is SpeechUi.Error -> Text(s.message, color = Ui.warning, textAlign = TextAlign.Center)
            else -> if (log.isEmpty()) Text("Tap the mic and speak", color = Ui.textDim)
        }
        (if (showAll) log else log.take(1)).forEach { e -> EntryView(e) { action -> app.repo.chooseOption(e.id, action) } }
    }
}

/** Gentle pulse while the microphone is listening; no animation runs otherwise (saves battery). */
@Composable
fun listeningPulse(active: Boolean, max: Float, millis: Int): Float {
    if (!active) return 1f
    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(1f, max, infiniteRepeatable(tween(millis), RepeatMode.Reverse), label = "pulse")
    return pulse
}

/** Hints for on-device recognisers (Android 13+): app names and command words in all three languages. */
fun voiceBiasing(appLabels: List<String>) = appLabels + listOf(
    "Netflix", "YouTube", "JioHotstar", "Hotstar", "Prime Video", "open", "close", "play", "search", "volume up", "volume down", "mute", "unmute",
    "యూట్యూబ్", "నెట్‌ఫ్లిక్స్", "హాట్‌స్టార్", "ప్రైమ్ వీడియో", "ఓపెన్", "క్లోజ్", "సౌండ్", "పెంచు", "తగ్గించు",
    "यूट्यूब", "नेटफ्लिक्स", "हॉटस्टार", "प्राइम वीडियो", "खोलो", "बंद करो", "आवाज़", "बढ़ाओ", "कम करो",
)
