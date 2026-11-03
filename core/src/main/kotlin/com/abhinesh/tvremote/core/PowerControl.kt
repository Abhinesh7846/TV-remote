package com.abhinesh.tvremote.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

sealed interface PowerResult {
    val message: String

    data class Done(override val message: String) : PowerResult
    data class AlreadyInState(override val message: String) : PowerResult
    data class NotConfirmed(override val message: String) : PowerResult
    data class Unreachable(override val message: String) : PowerResult
}

/**
 * Explicit power actions with verification: KEYCODE_SLEEP / KEYCODE_WAKEUP first, and the
 * POWER toggle only when the TV has reported its current state (so it can't toggle the wrong
 * way). Success is only reported when the TV itself reports the new state.
 */
object PowerControl {
    const val NOT_ON_NETWORK =
        "The TV isn't on the network. In standby this TV switches its Wi-Fi off, so no phone app can reach it. " +
            "Turn it on with the TV remote; this app reconnects by itself."

    suspend fun turnOff(s: RemoteSession?, verifyMs: Long = 6_000): PowerResult {
        if (s == null || !s.isOpen) return PowerResult.Unreachable("Not connected to the TV, so it can't be put in standby.")
        val before = s.status.value.isOn
        if (before == false) return PowerResult.AlreadyInState("The TV is already in standby.")
        s.sendKey(KeyCodes.SLEEP)
        offOrGone(s, verifyMs)?.let { return PowerResult.Done(it) }
        if (before == true && s.isOpen) {
            s.sendKey(KeyCodes.POWER)
            offOrGone(s, verifyMs)?.let { return PowerResult.Done("$it (Sleep was ignored, so the Power key was used.)") }
        }
        return PowerResult.NotConfirmed("Standby was requested, but the TV didn't report going to standby.")
    }

    /**
     * @param session the current session, if connected.
     * @param wake optional network wake (Wake-on-LAN); returns false if it couldn't be sent.
     * @param awaitSession waits up to the given time for a reconnected session.
     */
    suspend fun turnOn(
        session: () -> RemoteSession?,
        wake: (suspend () -> Boolean)?,
        awaitSession: suspend (Long) -> RemoteSession?,
        verifyMs: Long = 6_000,
        reconnectMs: Long = 45_000,
    ): PowerResult {
        val s = session()?.takeIf { it.isOpen }
        if (s != null) return wakeConnected(s, verifyMs)
        if (wake == null || !wake()) return PowerResult.Unreachable(NOT_ON_NETWORK)
        val back = awaitSession(reconnectMs) ?: return PowerResult.NotConfirmed(
            "A Wake-on-LAN packet was sent, but the TV didn't come back on the network within ${reconnectMs / 1000} s. " +
                "This TV may not support waking over Wi-Fi in its current standby mode. Use the TV remote.",
        )
        return when (val r = wakeConnected(back, verifyMs)) {
            is PowerResult.AlreadyInState -> PowerResult.Done("The TV woke up and the app reconnected.")
            else -> r
        }
    }

    private suspend fun wakeConnected(s: RemoteSession, verifyMs: Long): PowerResult {
        val before = s.status.value.isOn
        if (before == true) return PowerResult.AlreadyInState("The TV is already on.")
        s.sendKey(KeyCodes.WAKEUP)
        if (awaitOn(s, verifyMs)) return PowerResult.Done("The TV is on.")
        if (before == false && s.isOpen) {
            s.sendKey(KeyCodes.POWER)
            if (awaitOn(s, verifyMs)) return PowerResult.Done("The TV is on. (Wake was ignored, so the Power key was used.)")
        }
        return PowerResult.NotConfirmed("Wake was sent, but the TV didn't report turning on.")
    }

    private suspend fun awaitOn(s: RemoteSession, ms: Long): Boolean =
        withTimeoutOrNull(ms) { s.status.first { it.isOn == true } } != null

    /** Returns a description once the TV reports standby or drops the connection. */
    private suspend fun offOrGone(s: RemoteSession, ms: Long): String? = coroutineScope {
        val off = async { s.status.first { it.isOn == false } }
        try {
            withTimeoutOrNull(ms) {
                select<String> {
                    s.closed.onAwait { "The TV went to standby and left the network." }
                    off.onAwait { "The TV is in standby." }
                }
            }
        } finally {
            off.cancel()
        }
    }
}
