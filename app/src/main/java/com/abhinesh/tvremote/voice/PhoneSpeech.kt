package com.abhinesh.tvremote.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Languages offered for phone commands. "auto" only appears where language detection exists. */
object SpeechLanguages {
    const val AUTO = "auto"
    val ALL = listOf("en-IN" to "English", "te-IN" to "తెలుగు Telugu", "hi-IN" to "हिन्दी Hindi")
    fun name(tag: String) = ALL.firstOrNull { it.first == tag }?.second ?: if (tag == AUTO) "Auto-detect" else tag
}

data class SpeechPrefs(
    val language: String,
    /** "auto" (on-device when it supports the language, else the phone's default service), "ondevice", "default", or a flattened ComponentName. */
    val engine: String,
    val onDeviceOnly: Boolean,
    val biasing: List<String> = emptyList(),
)

sealed interface SpeechUi {
    data object Idle : SpeechUi
    /** Recogniser starting; the microphone isn't listening yet, so speech now would be lost. */
    data class Preparing(val engine: String) : SpeechUi
    data class Listening(val partial: String, val level: Float, val engine: String) : SpeechUi
    data class Processing(val engine: String) : SpeechUi
    data class Heard(val alternatives: List<String>, val engine: String, val language: String, val detectedLanguage: String?) : SpeechUi
    data class Error(val message: String) : SpeechUi
    /** The in-app recognisers failed: the app root opens Google's own voice popup for [language]. */
    data class Popup(val language: String) : SpeechUi
}

/** Per-language support as reported by the on-device recogniser (Android 13+). */
data class LanguageSupport(val installed: Boolean, val supportedOnDevice: Boolean, val online: Boolean, val pending: Boolean)

/**
 * Phone-side speech recognition for spoken commands (push-to-talk). Chooses the engine per
 * language: the on-device recogniser when it has that language, otherwise the phone's default
 * speech service (which may process audio online). Microphone use ends on release, cancel,
 * error, or after 15 s. Must be used from the main thread. No audio or transcripts are logged.
 */
class PhoneSpeech(private val context: Context) {
    private val _state = MutableStateFlow<SpeechUi>(SpeechUi.Idle)
    val state: StateFlow<SpeechUi> = _state.asStateFlow()
    private val _autoDetect = MutableStateFlow<Boolean?>(null)
    /** null = untested, true = the recogniser reported a detected language, false = it never did. */
    val autoDetectWorks: StateFlow<Boolean?> = _autoDetect.asStateFlow()
    var lastEngineNote: String = ""
        private set

    private var recognizer: SpeechRecognizer? = null
    private var holding = false
    private var heardSpeech = false
    private var session = 0
    private var engineLabel = ""
    private var activePrefs: SpeechPrefs? = null
    private var usingOnDevice = false
    private var detected: String? = null
    private val supportCache = HashMap<String, LanguageSupport?>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun onDeviceAvailable(): Boolean = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun autoDetectAvailable(): Boolean = Build.VERSION.SDK_INT >= 34 && _autoDetect.value != false

    fun setAutoDetectResult(works: Boolean?) { _autoDetect.value = works }

    /** Installed speech recognition services (Google, vivo, ...), for the engine picker. */
    fun services(): List<Pair<ComponentName, String>> {
        val pm = context.packageManager
        return pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.GET_META_DATA).map {
            ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) to it.loadLabel(pm).toString()
        }
    }

    /** On-device support for [tag], or null when the phone can't tell (Android 12 and older). */
    suspend fun languageSupport(tag: String): LanguageSupport? {
        if (supportCache.containsKey(tag)) return supportCache[tag]
        if (Build.VERSION.SDK_INT < 33 || !onDeviceAvailable()) return null
        val result = CompletableDeferred<LanguageSupport?>()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            r.checkRecognitionSupport(intent(tag, emptyList(), onDevice = true), ContextCompat.getMainExecutor(context), object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    fun has(list: List<String>) = list.any { it.equals(tag, true) || it.substringBefore('-').equals(tag.substringBefore('-'), true) }
                    result.complete(LanguageSupport(has(s.installedOnDeviceLanguages), has(s.supportedOnDeviceLanguages), has(s.onlineLanguages), has(s.pendingOnDeviceLanguages)))
                }
                override fun onError(error: Int) { result.complete(null) }
            })
            val v = withTimeoutOrNull(3_000) { result.await() }
            supportCache[tag] = v
            return v
        } finally {
            r.destroy()
        }
    }

    /** Asks the on-device recogniser to download a language (Android 13+); the system shows progress. */
    fun downloadLanguage(tag: String): Boolean {
        if (Build.VERSION.SDK_INT < 33 || !onDeviceAvailable()) return false
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        return runCatching { r.triggerModelDownload(intent(tag, emptyList(), onDevice = true)); true }.getOrDefault(false).also {
            supportCache.remove(tag)
            main.postDelayed({ r.destroy() }, 2_000)
        }
    }

    /** One way of recognising speech: the on-device recogniser, or a speech service (null = the phone's default). */
    private sealed interface Engine {
        data object OnDevice : Engine
        data class Service(val component: ComponentName?, val label: String) : Engine
    }

    private var plan: List<Engine> = emptyList()
    private var planIndex = 0
    private var language = "en-IN"

    /**
     * Engines to try, best first. Google's speech service is preferred because phone makers'
     * own services (e.g. vivo's) often don't serve other apps; then the phone's default; then on-device.
     */
    private fun enginePlan(prefs: SpeechPrefs): List<Engine> {
        val installed = runCatching { services() }.getOrDefault(emptyList())
        val google = installed.sortedBy { (c, _) ->
            when (c.packageName) { "com.google.android.googlequicksearchbox" -> 0; "com.google.android.tts" -> 1; else -> 2 }
        }.filter { it.first.packageName.startsWith("com.google.") }.map { Engine.Service(it.first, it.second) }
        val default = if (SpeechRecognizer.isRecognitionAvailable(context)) listOf(Engine.Service(null, "the phone's speech service")) else emptyList()
        val onDevice = if (onDeviceAvailable()) listOf(Engine.OnDevice) else emptyList()
        val chosen = prefs.engine.takeIf { it.contains('/') }?.let { ComponentName.unflattenFromString(it) }
            ?.let { c -> listOf(Engine.Service(c, installed.firstOrNull { it.first == c }?.second ?: c.packageName)) } ?: emptyList()
        if (prefs.onDeviceOnly) return onDevice
        return when (prefs.engine) {
            "ondevice" -> onDevice + google + default
            "default" -> default + google + onDevice
            "auto" -> google + default + onDevice
            else -> chosen + google + default + onDevice
        }.distinct()
    }

    /** Button pressed: the microphone opens straight away with the first working engine. */
    @Suppress("RedundantSuspendModifier")
    suspend fun start(prefs: SpeechPrefs) {
        cancel()
        holding = true
        heardSpeech = false
        detected = null
        activePrefs = prefs
        language = if (prefs.language == SpeechLanguages.AUTO) "en-IN" else prefs.language
        lastEngineNote = ""
        plan = enginePlan(prefs)
        planIndex = 0
        if (plan.isEmpty()) {
            if (prefs.onDeviceOnly) {
                _state.value = SpeechUi.Error("This phone has no on-device speech recogniser. Turn off \"On-device only\" in Voice options.")
            } else if (popupAvailable()) {
                _state.value = SpeechUi.Popup(language)
            } else {
                _state.value = SpeechUi.Error("No speech recognition service is installed. Install or update the Google app, then try again.")
            }
            holding = false
            return
        }
        if (prefs.engine == "popup" && popupAvailable()) { holding = false; _state.value = SpeechUi.Popup(language); return }
        launch(plan[0])
    }

    /** The next engine in the plan, or Google's voice popup when every in-app engine failed. */
    private fun tryNext(lastError: String): Boolean {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        if (heardSpeech) return false // the words are gone; ask the user to speak again
        planIndex++
        if (planIndex < plan.size) { launch(plan[planIndex]); return true }
        val prefs = activePrefs
        if (prefs != null && !prefs.onDeviceOnly && popupAvailable()) {
            Log.d(TAG, "all engines failed ($lastError); opening the voice popup")
            holding = false
            _state.value = SpeechUi.Popup(language)
            return true
        }
        return false
    }

    private fun launch(engine: Engine) {
        val prefs = activePrefs ?: return
        val onDevice = engine is Engine.OnDevice
        val r = runCatching {
            when (engine) {
                Engine.OnDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                is Engine.Service -> if (engine.component != null) SpeechRecognizer.createSpeechRecognizer(context, engine.component)
                else SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.getOrNull()
        if (r == null) { if (!tryNext("create failed")) finish(SpeechUi.Error("Couldn't start speech recognition on this phone.")); return }
        usingOnDevice = onDevice
        engineLabel = when (engine) {
            Engine.OnDevice -> "on this phone (on-device)"
            is Engine.Service -> "by ${engine.label} (may use the internet)"
        }
        val id = ++session
        recognizer = r
        r.setRecognitionListener(listener(id, language))
        _state.value = SpeechUi.Preparing(engineLabel)
        Log.d(TAG, "start engine=${if (onDevice) "on-device" else (engine as Engine.Service).component?.packageName ?: "default"} lang=$language")
        runCatching { r.startListening(intent(if (prefs.language == SpeechLanguages.AUTO) SpeechLanguages.AUTO else language, prefs.biasing, onDevice)) }
            .onFailure { if (!tryNext("startListening threw")) finish(SpeechUi.Error("Couldn't start speech recognition on this phone.")); return }
        // Some speech services accept the request but never answer: move on if the mic isn't ready soon.
        main.postDelayed({ if (session == id && _state.value is SpeechUi.Preparing) { Log.d(TAG, "engine silent; trying the next"); tryNext("no response") || run { finish(SpeechUi.Error("The speech service didn't respond. Try again.")); true } } }, READY_TIMEOUT_MS)
        main.postDelayed({ if (session == id && (_state.value is SpeechUi.Listening)) stop() }, MAX_LISTEN_MS)
    }

    /** Google's voice popup (RecognizerIntent activity): works on almost every phone with the Google app. */
    fun popupIntent(language: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a TV command")
    }

    fun popupAvailable(): Boolean = popupIntent("en-IN").resolveActivity(context.packageManager) != null

    /** Result from the popup, delivered by the app root. */
    fun popupResult(alternatives: List<String>?) {
        val list = alternatives.orEmpty().filter { it.isNotBlank() }
        _state.value = if (list.isEmpty()) SpeechUi.Idle else SpeechUi.Heard(list, "by Google voice (may use the internet)", language, null)
    }

    private fun intent(language: String, biasing: List<String>, onDevice: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (language == SpeechLanguages.AUTO && Build.VERSION.SDK_INT >= 34) {
            val allowed = ArrayList(SpeechLanguages.ALL.map { it.first })
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, allowed)
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, allowed)
        } else {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
        }
        // Only the on-device recogniser is asked to stay offline; for a speech service this flag can
        // make it fail when the offline pack for the language isn't downloaded.
        if (onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (Build.VERSION.SDK_INT >= 33 && biasing.isNotEmpty()) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasing.take(50)))
    }

    /** Finish now: the microphone stops and the final result follows. Before any speech, it just cancels. */
    fun stop() {
        holding = false
        val r = recognizer ?: return
        val s = _state.value
        if (s is SpeechUi.Preparing || (s is SpeechUi.Listening && !heardSpeech)) { cancel(); return }
        if (s is SpeechUi.Listening) _state.value = SpeechUi.Processing(engineLabel)
        r.stopListening()
    }

    /**
     * Finger lifted. If you were speaking, recognition finishes now. If you let go before speaking
     * (or before the mic was ready), it keeps listening and ends by itself when you stop talking.
     */
    fun release() {
        holding = false
        if (_state.value is SpeechUi.Listening && heardSpeech) stop()
    }

    /** Cancelled: microphone off, recogniser destroyed, nothing executed. */
    fun cancel() {
        holding = false
        session++
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
        val s = _state.value
        if (s is SpeechUi.Listening || s is SpeechUi.Processing || s is SpeechUi.Preparing) _state.value = SpeechUi.Idle
    }

    fun clear() { if (recognizer == null) _state.value = SpeechUi.Idle }

    private fun finish(newState: SpeechUi) {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        holding = false
        _state.value = newState
    }

    private fun listener(id: Int, language: String) = object : RecognitionListener {
        private fun current() = id == session
        override fun onReadyForSpeech(params: Bundle?) { if (current()) _state.value = SpeechUi.Listening("", 0f, engineLabel) }
        override fun onBeginningOfSpeech() { if (current()) heardSpeech = true }
        override fun onRmsChanged(rmsdB: Float) {
            if (!current()) return
            (_state.value as? SpeechUi.Listening)?.let { _state.value = it.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (current() && _state.value is SpeechUi.Listening) _state.value = SpeechUi.Processing(engineLabel) }
        override fun onPartialResults(partialResults: Bundle?) {
            if (!current()) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) heardSpeech = true
            (_state.value as? SpeechUi.Listening)?.let { _state.value = it.copy(partial = text) }
        }
        override fun onLanguageDetection(results: Bundle) {
            if (!current()) return
            detected = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
            if (detected != null) _autoDetect.value = true
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onResults(results: Bundle?) {
            if (!current()) return
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
            val auto = activePrefs?.language == SpeechLanguages.AUTO
            if (auto && detected == null && list.isNotEmpty()) _autoDetect.value = false
            Log.d(TAG, "results n=${list.size} detected=$detected")
            finish(if (list.isEmpty()) SpeechUi.Error("Didn't catch that. Press the mic and speak clearly.")
            else SpeechUi.Heard(list, engineLabel, if (auto) SpeechLanguages.AUTO else language, detected))
        }
        override fun onError(error: Int) {
            if (!current()) return
            Log.d(TAG, "error $error onDevice=$usingOnDevice heard=$heardSpeech")
            // Nothing said / no permission: a real answer, not an engine problem.
            val userSide = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            // Any other error before speech: this engine can't serve us, so try the next one.
            if (!userSide && tryNext("error $error")) return
            finish(SpeechUi.Error(describe(error, language)))
        }
    }

    private fun describe(error: Int, language: String): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Press the mic and speak clearly."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off for this app."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "The speech service needs the internet for ${SpeechLanguages.name(language)} and couldn't reach it."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recogniser is busy. Try again in a moment."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone couldn't be opened (another app may be using it)."
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "The speech service had a problem. Try again."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "The speech service is rate-limiting requests. Wait a moment."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "${SpeechLanguages.name(language)} isn't available in this speech recogniser right now. Pick another engine in Voice settings, or turn off \"On-device only\"."
        SpeechRecognizer.ERROR_CLIENT -> "Recognition stopped. Try again."
        else -> "Speech recognition failed (code $error)."
    }

    private companion object {
        const val TAG = "TvRemoteVoice"
        const val MAX_LISTEN_MS = 15_000L
        const val READY_TIMEOUT_MS = 4_000L
    }
}
