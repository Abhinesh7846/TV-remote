package com.abhinesh.tvremote.core

import com.abhinesh.tvremote.core.proto.Framing
import com.abhinesh.tvremote.core.proto.RemoteMessages
import com.abhinesh.tvremote.core.proto.RemoteMessages.Direction
import com.abhinesh.tvremote.core.proto.RemoteMessages.Feature
import com.abhinesh.tvremote.core.proto.RemoteMessages.Inbound
import com.abhinesh.tvremote.core.tls.ClientIdentity
import com.abhinesh.tvremote.core.tls.Tls
import com.abhinesh.tvremote.core.tls.TlsConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException

/** What the TV has told us over the current connection. Null fields = not reported yet. */
data class TvStatus(
    val features: Int = 0,
    val model: String = "",
    val vendor: String = "",
    val appVersion: String = "",
    val isOn: Boolean? = null,
    val currentApp: String? = null,
    val volumeLevel: Int? = null,
    val volumeMax: Int? = null,
    val muted: Boolean? = null,
    val imeFieldValue: String? = null,
    val imeShownAt: Long = 0,
    val lastTvError: String? = null,
    /** Last app link the TV refused (the TV then closes the connection). */
    val rejectedAppLink: String? = null,
) {
    fun has(feature: Int) = features and feature != 0
}

/**
 * One connection to the TV's remote port (6466). Handles the configure / set-active
 * handshake and pings, tracks power, volume and foreground app, and sends keys, text,
 * app links and voice audio. When the connection ends [closed] completes with the reason;
 * reconnecting is the job of [TvController].
 */
class RemoteSession(
    val host: String,
    private val identity: ClientIdentity,
    private val pinnedKeySha256: ByteArray?,
    private val requestedFeatures: Int = DEFAULT_FEATURES,
    val port: Int = DEFAULT_PORT,
    private val idleTimeoutMs: Long = 16_000,
    private val log: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val started = CompletableDeferred<Unit>()
    private val _status = MutableStateFlow(TvStatus())
    @Volatile private var conn: TlsConnection? = null
    @Volatile private var lastInboundAt = 0L
    @Volatile private var receivedAny = false
    @Volatile private var imeCounter = 0
    @Volatile private var imeFieldCounter = 0
    @Volatile private var pendingVoice: CompletableDeferred<Int>? = null
    @Volatile private var voiceSessionId: Int? = null

    /** Completes once, with why the session ended. */
    val closed = CompletableDeferred<Throwable>()
    val status: StateFlow<TvStatus> = _status.asStateFlow()
    val serverCertificate: X509Certificate? get() = conn?.serverCertificate
    val isOpen: Boolean get() = started.isCompleted && !closed.isCompleted

    /**
     * Connects and waits until the TV reports it is ready (remote_start).
     * @throws CannotConnectException, PairingRequiredException, TvIdentityChangedException,
     *         ConnectionClosedException
     */
    suspend fun connect(readyTimeoutMs: Long = 10_000) {
        val c = withContext(Dispatchers.IO) { Tls.connect(host, port, identity, pinnedKeySha256) }
        conn = c
        c.socket.soTimeout = 0 // reads block; the idle watchdog detects a silent TV
        lastInboundAt = System.currentTimeMillis()
        scope.launch { readLoop(c) }
        scope.launch { writeLoop(c) }
        scope.launch { idleWatchdog() }
        val ready = withTimeoutOrNull(readyTimeoutMs) {
            select<Boolean> {
                started.onAwait { true }
                closed.onAwait { false }
            }
        }
        when (ready) {
            true -> log("remote: ready (features: ${Feature.describe(_status.value.features)})")
            false -> throw closed.await()
            null -> {
                val e = ConnectionClosedException("The TV accepted the connection but didn't finish remote setup within ${readyTimeoutMs / 1000} s.")
                close(e)
                throw e
            }
        }
    }

    // ------------------------------------------------------------------ commands

    fun sendKey(keyCode: Int, direction: Direction = Direction.SHORT): Boolean =
        send(RemoteMessages.keyInject(keyCode, direction))

    /** Replaces the TV's focused text field content with [text] (needs the IME feature). */
    fun sendText(text: String): Boolean {
        if (text.isEmpty()) return false
        return send(RemoteMessages.imeBatchEdit(imeCounter, imeFieldCounter, text))
    }

    fun launchAppLink(appLink: String): Boolean = send(RemoteMessages.appLinkLaunch(appLink))

    /**
     * Starts a TV voice session: sends KEYCODE_SEARCH and waits for remote_voice_begin.
     * @return the session id for [sendVoiceAudio] / [endVoice].
     * @throws VoiceUnavailableException if voice wasn't negotiated or the TV doesn't respond.
     */
    suspend fun startVoice(timeoutMs: Long = VOICE_BEGIN_TIMEOUT_MS): Int {
        if (!isOpen) throw ConnectionClosedException("Not connected to the TV.")
        if (!_status.value.has(Feature.VOICE)) {
            throw VoiceUnavailableException("This TV did not enable voice input for remote apps during connection setup.")
        }
        if (pendingVoice != null || voiceSessionId != null) throw VoiceUnavailableException("A voice session is already running.")
        val waiter = CompletableDeferred<Int>()
        pendingVoice = waiter
        try {
            sendKey(KeyCodes.SEARCH)
            val id = withTimeoutOrNull(timeoutMs) {
                select<Int?> {
                    waiter.onAwait { it }
                    closed.onAwait { null }
                }
            } ?: throw VoiceUnavailableException(
                if (closed.isCompleted) "Connection to the TV was lost." else
                    "The TV didn't start a voice session within ${timeoutMs / 1000.0} s. It may not accept phone microphone audio."
            )
            send(RemoteMessages.voiceBegin(id))
            voiceSessionId = id
            log("voice: session $id started")
            return id
        } finally {
            pendingVoice = null
        }
    }

    /** 16-bit PCM, mono, 8 kHz. Split to <= 20 KB messages and padded to >= 8 KB, like androidtvremote2. */
    fun sendVoiceAudio(sessionId: Int, pcm: ByteArray): Boolean {
        var ok = true
        var i = 0
        while (i < pcm.size) {
            var chunk = pcm.copyOfRange(i, minOf(pcm.size, i + VOICE_CHUNK_MAX))
            if (chunk.size < VOICE_CHUNK_MIN) chunk = chunk.copyOf(VOICE_CHUNK_MIN)
            ok = ok && send(RemoteMessages.voicePayload(sessionId, chunk))
            i += VOICE_CHUNK_MAX
        }
        return ok
    }

    fun endVoice(sessionId: Int): Boolean {
        if (voiceSessionId == sessionId) voiceSessionId = null
        log("voice: session $sessionId ended")
        return send(RemoteMessages.voiceEnd(sessionId))
    }

    val activeVoiceSession: Int? get() = voiceSessionId

    fun close(reason: Throwable = ConnectionClosedException("Disconnected")) {
        if (closed.complete(reason)) log("remote: closed (${reason.message})")
        conn?.close()
        outbox.close()
        voiceSessionId = null
        scope.cancel()
    }

    private fun send(bytes: ByteArray): Boolean {
        if (closed.isCompleted || conn == null) return false
        return outbox.trySend(bytes).isSuccess
    }

    // ------------------------------------------------------------------ io loops

    private suspend fun writeLoop(c: TlsConnection) {
        try {
            for (bytes in outbox) Framing.write(c.output, bytes)
        } catch (e: Exception) {
            if (!closed.isCompleted) close(ConnectionClosedException("Sending to the TV failed: ${e.message}", e))
        }
    }

    private fun readLoop(c: TlsConnection) {
        try {
            while (scope.isActive) {
                val raw = Framing.read(c.input)
                lastInboundAt = System.currentTimeMillis()
                receivedAny = true
                handle(RemoteMessages.parse(raw))
            }
        } catch (e: Exception) {
            if (closed.isCompleted) return
            val reason = when {
                // With TLS 1.3 a rejected client certificate only surfaces on the first read.
                e is SSLException && !started.isCompleted -> PairingRequiredException("The TV rejected this phone's certificate.", e)
                e is java.net.SocketException && !receivedAny -> PairingRequiredException("The TV cut off the connection during setup.", e, definite = false)
                e is EOFException && !receivedAny -> PairingRequiredException("The TV closed the connection immediately.", e, definite = false)
                e is EOFException -> ConnectionClosedException("The TV closed the connection.", e)
                else -> ConnectionClosedException("Connection lost: ${e.message ?: e.javaClass.simpleName}", e)
            }
            close(reason)
        }
    }

    private suspend fun idleWatchdog() {
        while (scope.isActive) {
            delay(2_000)
            if (System.currentTimeMillis() - lastInboundAt > idleTimeoutMs) {
                close(ConnectionClosedException("The TV stopped responding (no message for ${idleTimeoutMs / 1000} s)."))
                return
            }
        }
    }

    private fun handle(msg: Inbound) {
        when (msg) {
            is Inbound.Configure -> {
                val active = requestedFeatures and msg.features
                log("remote: TV offers ${Feature.describe(msg.features)} (${msg.vendor} ${msg.model}); using ${Feature.describe(active)}")
                _status.update { it.copy(features = active, model = msg.model, vendor = msg.vendor, appVersion = msg.appVersion) }
                send(RemoteMessages.configureResponse(active))
            }
            is Inbound.SetActive -> send(RemoteMessages.setActive(_status.value.features))
            is Inbound.PingRequest -> send(RemoteMessages.pingResponse(msg.val1))
            is Inbound.Start -> {
                _status.update { it.copy(isOn = msg.started) }
                started.complete(Unit)
            }
            is Inbound.Volume -> _status.update { it.copy(volumeLevel = msg.level, volumeMax = msg.max, muted = msg.muted) }
            is Inbound.ImeKeyInject -> {
                log("remote: foreground app ${msg.appPackage}")
                _status.update { it.copy(currentApp = msg.appPackage.ifEmpty { it.currentApp }, imeFieldValue = msg.fieldValue ?: it.imeFieldValue) }
            }
            is Inbound.ImeBatchEdit -> {
                imeCounter = msg.imeCounter
                imeFieldCounter = msg.fieldCounter
                _status.update { it.copy(imeFieldValue = msg.value ?: it.imeFieldValue) }
            }
            is Inbound.ImeShowRequest -> _status.update { it.copy(imeFieldValue = msg.value, imeShownAt = System.currentTimeMillis()) }
            is Inbound.VoiceBegin -> {
                val waiter = pendingVoice
                if (waiter != null) waiter.complete(msg.sessionId) else log("remote: ignoring unsolicited voice_begin ${msg.sessionId}")
            }
            is Inbound.Error -> {
                log("remote: TV reported an error: ${msg.detail}")
                _status.update { it.copy(lastTvError = msg.detail.ifEmpty { "unspecified" }, rejectedAppLink = msg.rejectedAppLink ?: it.rejectedAppLink) }
            }
            is Inbound.Unknown -> log("remote: unhandled message fields ${msg.fieldNumbers}")
        }
    }

    companion object {
        const val DEFAULT_PORT = 6466
        const val DEFAULT_FEATURES = Feature.PING or Feature.KEY or Feature.POWER or Feature.VOLUME or
            Feature.APP_LINK or Feature.IME or Feature.VOICE
        const val VOICE_BEGIN_TIMEOUT_MS = 3_000L
        const val VOICE_CHUNK_MAX = 20 * 1024
        const val VOICE_CHUNK_MIN = 8 * 1024
    }
}
