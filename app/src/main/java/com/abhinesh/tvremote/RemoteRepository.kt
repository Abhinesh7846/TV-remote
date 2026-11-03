package com.abhinesh.tvremote

import android.os.Build
import android.util.Log
import com.abhinesh.tvremote.core.AppLauncher
import com.abhinesh.tvremote.core.AppShortcut
import com.abhinesh.tvremote.core.ConnectionState
import com.abhinesh.tvremote.core.KeyCodes
import com.abhinesh.tvremote.core.LaunchResult
import com.abhinesh.tvremote.core.PairingException
import com.abhinesh.tvremote.core.PairingSession
import com.abhinesh.tvremote.core.PowerControl
import com.abhinesh.tvremote.core.PowerResult
import com.abhinesh.tvremote.core.RemoteSession
import com.abhinesh.tvremote.core.TvController
import com.abhinesh.tvremote.core.TvEndpoint
import com.abhinesh.tvremote.core.TvStatus
import com.abhinesh.tvremote.core.proto.RemoteMessages.Direction
import com.abhinesh.tvremote.core.proto.RemoteMessages.Feature
import com.abhinesh.tvremote.core.tls.TvCertificateInfo
import com.abhinesh.tvremote.core.voice.ExecResult
import com.abhinesh.tvremote.core.voice.Interpretation
import com.abhinesh.tvremote.core.voice.VoiceAction
import com.abhinesh.tvremote.core.voice.VoiceContext
import com.abhinesh.tvremote.core.voice.VoiceExecutor
import com.abhinesh.tvremote.core.voice.VoiceInterpreter
import com.abhinesh.tvremote.data.IdentityStore
import com.abhinesh.tvremote.data.SavedTv
import com.abhinesh.tvremote.data.Store
import com.abhinesh.tvremote.net.Discovery
import com.abhinesh.tvremote.net.WakeOnLan
import com.abhinesh.tvremote.ui.appName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface PairingUi {
    data object Idle : PairingUi
    data class Connecting(val host: String) : PairingUi
    data class EnterCode(val host: String, val name: String, val error: String? = null, val busy: Boolean = false) : PairingUi
    data class Failed(val host: String, val name: String, val message: String) : PairingUi
}

/** One spoken command, as shown on the Voice screen: what was heard, understood, and what happened. */
data class VoiceEntry(
    val id: Long,
    val heard: String,
    val engine: String,
    val language: String,
    val understood: String,
    val outcome: ExecResult.Outcome? = null,
    val message: String = "",
    val running: Boolean = false,
    val options: List<VoiceAction> = emptyList(),
)

/** App-wide owner of the TV connection: reconnect loop, pairing, and every remote action. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteRepository(
    private val store: Store,
    private val identityStore: IdentityStore,
    val discovery: Discovery,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val _pairing = MutableStateFlow<PairingUi>(PairingUi.Idle)
    private val _launching = MutableStateFlow<String?>(null)
    private val _power = MutableStateFlow<String?>(null)
    private val _voiceLog = MutableStateFlow<List<VoiceEntry>>(emptyList())
    private var pairingSession: PairingSession? = null
    private var launchJob: Job? = null
    private val heldKeys = HashMap<Int, Job>()
    private val voiceContext = VoiceContext()
    @Volatile private var foreground = false

    private val controller = TvController(
        identity = { identityStore.get() },
        features = {
            val s = store.settings.value
            var f = RemoteSession.DEFAULT_FEATURES
            if (!s.imeEnabled) f = f and Feature.IME.inv()
            if (!s.tvVoiceEnabled) f = f and Feature.VOICE.inv()
            f
        },
        onPinLearned = { pin -> store.updateTv { it.copy(pinSha256 = pin.toHex()) } },
        onHostChanged = { host -> store.updateTv { it.copy(host = host) } },
        rediscover = { _ -> store.tv.value?.let { discovery.findHost(it.btMac, it.name) } },
        networkProblem = { discovery.networkProblem() },
        // Short ceiling so the app notices quickly when the TV is switched back on.
        maxBackoffMs = 5_000,
        log = { Log.d("TvRemote", it) },
    )

    val connection: StateFlow<ConnectionState> = controller.state
    val status: StateFlow<TvStatus?> = controller.session
        .flatMapLatest { it?.status ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val events: SharedFlow<String> = _events.asSharedFlow()
    val pairing: StateFlow<PairingUi> = _pairing.asStateFlow()
    /** Id of the shortcut currently being launched, for a progress indicator. */
    val launching: StateFlow<String?> = _launching.asStateFlow()
    /** Non-null while a power action is being verified. */
    val powerBusy: StateFlow<String?> = _power.asStateFlow()
    val voiceLog: StateFlow<List<VoiceEntry>> = _voiceLog.asStateFlow()
    val session: RemoteSession? get() = controller.session.value?.takeIf { it.isOpen }

    private val executor = VoiceExecutor(
        session = { session },
        launch = { launchAndWait(it) },
        power = { power(it) },
        appName = { appName(it, store.shortcuts.value) },
    )

    init {
        // Wi-Fi back (or switched networks): try the TV straight away instead of waiting for the next retry.
        discovery.watchNetwork { scope.launch { if (foreground && store.tv.value != null && _pairing.value == PairingUi.Idle) reconnectNow() } }
    }

    fun notify(message: String) { _events.tryEmit(message) }

    // ------------------------------------------------------------------ lifecycle

    fun onForeground() {
        foreground = true
        if (_pairing.value == PairingUi.Idle) connectSaved()
    }

    fun onBackground() {
        foreground = false
        releaseHeldKeys()
        controller.stop()
    }

    fun connectSaved() {
        val tv = store.tv.value ?: return
        controller.start(TvEndpoint(tv.host, tv.port, tv.pinSha256?.fromHex()))
    }

    fun reconnectNow() {
        if (connection.value is ConnectionState.Retrying) controller.retryNow() else connectSaved()
    }

    /** Waits for a connected session that has reported its power state. */
    suspend fun awaitSession(ms: Long): RemoteSession? = withTimeoutOrNull(ms) {
        controller.session.first { it != null && it.isOpen && it.status.value.isOn != null }
    }

    // ------------------------------------------------------------------ keys

    /** One complete press. Returns false (and tells the user) when not connected. */
    fun key(code: Int, direction: Direction = Direction.SHORT): Boolean {
        val s = session
        if (s == null) {
            notify("Not connected to the TV.")
            return false
        }
        return s.sendKey(code, direction)
    }

    /** Long press start. The matching [keyUp] is guaranteed by a 10 s safety release. */
    fun keyDown(code: Int) {
        if (!key(code, Direction.START_LONG)) return
        heldKeys.remove(code)?.cancel()
        heldKeys[code] = scope.launch {
            delay(10_000)
            keyUp(code)
        }
    }

    fun keyUp(code: Int) {
        val job = heldKeys.remove(code) ?: return
        job.cancel()
        session?.sendKey(code, Direction.END_LONG)
    }

    fun releaseHeldKeys() = heldKeys.keys.toList().forEach { keyUp(it) }

    // ------------------------------------------------------------------ text

    fun sendText(text: String): Boolean {
        val s = session ?: run { notify("Not connected to the TV."); return false }
        if (!s.status.value.has(Feature.IME)) {
            notify("This TV didn't enable keyboard input. Use the key-press mode instead.")
            return false
        }
        return s.sendText(text)
    }

    /** Types via individual key events; returns characters that have no key. */
    fun typeWithKeys(text: String): String {
        scope.launch {
            for (c in text) {
                val code = KeyCodes.forChar(c) ?: continue
                if (!key(code)) return@launch
                delay(60)
            }
        }
        return text.filter { KeyCodes.forChar(it) == null }
    }

    // ------------------------------------------------------------------ apps

    /** Opening another app replaces a launch still waiting for confirmation (app switching). */
    fun launch(shortcut: AppShortcut) {
        if (session == null) { notify("Not connected to the TV."); return }
        launchJob?.cancel()
        _launching.value = shortcut.id
        launchJob = scope.launch {
            try {
                val result = launchAndWait(shortcut)
                notify(if (result.success) "${shortcut.label}: ${result.message}" else result.message)
            } finally {
                if (_launching.value == shortcut.id) _launching.value = null
            }
        }
    }

    suspend fun launchAndWait(shortcut: AppShortcut): LaunchResult =
        AppLauncher.launch(shortcut) { controller.session.value }

    // ------------------------------------------------------------------ power

    /** Verified standby / wake. Never reports success unless the TV reports the new state. */
    suspend fun power(on: Boolean): PowerResult {
        _power.value = if (on) "Turning on…" else "Turning off…"
        return try {
            if (!on) PowerControl.turnOff(session)
            else PowerControl.turnOn(session = { session }, wake = wakeHook(), awaitSession = ::awaitSession)
        } finally {
            _power.value = null
        }
    }

    fun powerAndReport(on: Boolean) {
        scope.launch { notify(power(on).message) }
    }

    private fun wakeHook(): (suspend () -> Boolean)? {
        val tv = store.tv.value ?: return null
        val mac = tv.wolMac ?: return null
        if (!store.settings.value.useWakeOnLan) return null
        return {
            val sent = WakeOnLan.send(mac, tv.host).isSuccess
            if (sent) scope.launch {
                // Keep knocking while the TV might be booting its network.
                repeat(25) { if (session == null) { reconnectNow(); delay(2_000) } }
            }
            sent
        }
    }

    // ------------------------------------------------------------------ voice (phone commands)

    /** Interprets recognised speech and, when it's unambiguous, runs it on the TV. */
    fun handleSpeech(alternatives: List<String>, engine: String, language: String) {
        val interp = VoiceInterpreter.interpret(alternatives, store.shortcuts.value, voiceContext)
        voiceContext.remember(interp)
        val heard = alternatives.firstOrNull().orEmpty()
        val id = System.nanoTime()
        Log.d("TvRemoteVoice", "interpretation=${interp::class.simpleName}")
        when (interp) {
            is Interpretation.Command -> {
                add(VoiceEntry(id, heard, engine, language, interp.action.summary, running = true))
                run(id, interp.action)
            }
            is Interpretation.Clarify -> add(VoiceEntry(id, heard, engine, language, interp.question, ExecResult.Outcome.NEEDS_YOU, "Not executed. Choose below or speak again.", options = interp.options))
            is Interpretation.Cancelled -> add(VoiceEntry(id, heard, engine, language, "Cancelled", ExecResult.Outcome.NOTHING_TO_DO, "Nothing was sent."))
            is Interpretation.NotUnderstood -> add(VoiceEntry(id, heard, engine, language, "No matching command", ExecResult.Outcome.FAILED,
                "Nothing was sent. Try \"open YouTube\", \"సౌండ్ పెంచు\" or \"आवाज़ बढ़ाओ\"."))
        }
    }

    /** User tapped one of the options offered for an unclear command. */
    fun chooseOption(entryId: Long, action: VoiceAction) {
        voiceContext.clearPending()
        voiceContext.remember(Interpretation.Command(action, ""))
        _voiceLog.update { log -> log.map { if (it.id == entryId) it.copy(options = emptyList(), understood = action.summary, outcome = null, running = true, message = "") else it } }
        run(entryId, action)
    }

    private fun add(e: VoiceEntry) = _voiceLog.update { (listOf(e) + it).take(8) }

    private fun run(id: Long, action: VoiceAction) {
        scope.launch {
            val r = executor.execute(action)
            Log.d("TvRemoteVoice", "action=${action::class.simpleName} outcome=${r.outcome}")
            _voiceLog.update { log -> log.map { if (it.id == id) it.copy(outcome = r.outcome, message = r.message, running = false) else it } }
        }
    }

    // ------------------------------------------------------------------ pairing

    fun startPairing(host: String, name: String) {
        cancelPairing()
        controller.stop()
        _pairing.value = PairingUi.Connecting(host)
        scope.launch {
            val identity = identityStore.get()
            val p = PairingSession(host.trim(), identity, "TV Remote on ${Build.MANUFACTURER} ${Build.MODEL}")
            pairingSession = p
            try {
                p.start()
                val model = p.serverCertificate?.let { TvCertificateInfo.from(it).model }
                _pairing.value = PairingUi.EnterCode(host.trim(), name.ifBlank { model ?: "Android TV" })
            } catch (e: PairingException) {
                p.close()
                _pairing.value = PairingUi.Failed(host, name, e.message ?: "Pairing failed.")
            }
        }
    }

    fun submitCode(code: String) {
        val st = _pairing.value as? PairingUi.EnterCode ?: return
        val p = pairingSession ?: return
        _pairing.value = st.copy(busy = true, error = null)
        scope.launch {
            try {
                p.submitCode(code)
                val info = p.serverCertificate?.let { TvCertificateInfo.from(it) }
                p.close()
                pairingSession = null
                val old = store.tv.value
                store.saveTv(SavedTv(name = st.name, host = st.host, model = info?.model, btMac = info?.macAddress, wolMac = old?.wolMac))
                _pairing.value = PairingUi.Idle
                notify("Paired with ${st.name}.")
                connectSaved()
            } catch (e: PairingException) {
                if (e.reason == PairingException.Reason.CODE_MISTYPED) {
                    _pairing.value = st.copy(busy = false, error = e.message)
                } else {
                    p.close()
                    pairingSession = null
                    _pairing.value = PairingUi.Failed(st.host, st.name, e.message ?: "Pairing failed.")
                }
            }
        }
    }

    fun cancelPairing() {
        pairingSession?.close()
        pairingSession = null
        _pairing.value = PairingUi.Idle
        if (foreground && store.tv.value != null) connectSaved()
    }

    fun forgetTv() {
        controller.stop()
        store.saveTv(null)
        notify("TV forgotten. To also remove this phone from the TV, clear the storage of \"Android TV Remote Service\" in the TV's app settings.")
    }

    suspend fun resetIdentity() {
        controller.stop()
        identityStore.reset()
        store.saveTv(null)
        notify("Phone identity reset. Pair the TV again.")
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
