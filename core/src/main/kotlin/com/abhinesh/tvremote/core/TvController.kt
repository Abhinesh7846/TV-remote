package com.abhinesh.tvremote.core

import com.abhinesh.tvremote.core.tls.ClientIdentity
import com.abhinesh.tvremote.core.tls.publicKeySha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class TvEndpoint(val host: String, val port: Int = RemoteSession.DEFAULT_PORT, val pinnedKeySha256: ByteArray? = null)

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val host: String, val attempt: Int) : ConnectionState
    data class Connected(val host: String) : ConnectionState
    data class Retrying(val host: String, val attempt: Int, val retryInMs: Long, val reason: String) : ConnectionState
    data class PairingNeeded(val reason: String) : ConnectionState
    data class IdentityChanged(val reason: String) : ConnectionState
}

/**
 * Keeps one [RemoteSession] alive: connects, reconnects with backoff when the link drops,
 * re-discovers the TV's address after repeated failures, and stops (without retrying)
 * when the TV says pairing is needed or the TV's identity changed.
 */
class TvController(
    private val identity: suspend () -> ClientIdentity,
    private val features: () -> Int = { RemoteSession.DEFAULT_FEATURES },
    private val onPinLearned: (ByteArray) -> Unit = {},
    private val onHostChanged: (String) -> Unit = {},
    private val rediscover: suspend (currentHost: String) -> String? = { null },
    /** Returns a human-readable reason when the phone has no usable local network, else null. */
    private val networkProblem: () -> String? = { null },
    private val maxBackoffMs: Long = 15_000,
    private val log: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val _session = MutableStateFlow<RemoteSession?>(null)

    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    val session: StateFlow<RemoteSession?> = _session.asStateFlow()

    @Synchronized
    fun start(endpoint: TvEndpoint) {
        stop()
        loop = scope.launch { runLoop(endpoint) }
    }

    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
        _session.value?.close(ConnectionClosedException("Disconnected by the app"))
        _session.value = null
        _state.value = ConnectionState.Idle
    }

    /** Skip the current backoff wait. */
    fun retryNow() {
        kick.trySend(Unit)
    }

    private suspend fun CoroutineScope.runLoop(initial: TvEndpoint) {
        var ep = initial
        var attempt = 0
        var definiteRejections = 0
        var handshakeFailures = 0
        while (isActive) {
            attempt++
            val problem = networkProblem()
            if (problem != null) {
                waitRetry(ep, attempt, problem)
                continue
            }

            _state.value = ConnectionState.Connecting(ep.host, attempt)
            val s = RemoteSession(ep.host, identity(), ep.pinnedKeySha256, features(), ep.port, log = log)
            try {
                s.connect()
            } catch (e: CancellationException) {
                s.close()
                throw e
            } catch (e: PairingRequiredException) {
                s.close(e)
                if (e.definite) definiteRejections++
                handshakeFailures++
                // Needs a definite TLS rejection plus one more failed attempt (or four handshakes
                // in a row cut off after TCP succeeded), so one glitch never forces re-pairing.
                if ((definiteRejections >= 1 && handshakeFailures >= 2) || handshakeFailures >= 4) {
                    _state.value = ConnectionState.PairingNeeded(e.message ?: "Pairing needed")
                    return
                }
                waitRetry(ep, attempt, e.message ?: "TLS rejected", 800)
                continue
            } catch (e: TvIdentityChangedException) {
                s.close(e)
                _state.value = ConnectionState.IdentityChanged(e.message ?: "TV identity changed")
                return
            } catch (e: Exception) {
                s.close(e)
                if (attempt % 3 == 0) {
                    val found = runCatching { rediscover(ep.host) }.getOrNull()
                    if (found != null && found != ep.host) {
                        log("controller: TV moved from ${ep.host} to $found")
                        ep = ep.copy(host = found)
                        onHostChanged(found)
                    }
                }
                waitRetry(ep, attempt, e.message ?: e.javaClass.simpleName)
                continue
            }

            attempt = 0
            definiteRejections = 0
            handshakeFailures = 0
            if (ep.pinnedKeySha256 == null) {
                s.serverCertificate?.publicKeySha256()?.let { pin ->
                    ep = ep.copy(pinnedKeySha256 = pin)
                    onPinLearned(pin)
                }
            }
            _session.value = s
            _state.value = ConnectionState.Connected(ep.host)
            val reason = try {
                s.closed.await()
            } catch (e: CancellationException) {
                s.close()
                throw e
            }
            _session.value = null
            log("controller: disconnected: ${reason.message}")
            waitRetry(ep, 1, reason.message ?: "Disconnected", 300)
        }
    }

    private suspend fun waitRetry(ep: TvEndpoint, attempt: Int, reason: String, fixedDelayMs: Long? = null) {
        val d = fixedDelayMs ?: minOf(maxBackoffMs, 1_000L shl (attempt - 1).coerceIn(0, 4))
        _state.value = ConnectionState.Retrying(ep.host, attempt, d, reason)
        withTimeoutOrNull(d) { kick.receive() }
    }
}
