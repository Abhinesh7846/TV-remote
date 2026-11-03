package com.abhinesh.tvremote.core.voice

import com.abhinesh.tvremote.core.AppShortcut
import com.abhinesh.tvremote.core.KeyCodes
import com.abhinesh.tvremote.core.LaunchResult
import com.abhinesh.tvremote.core.PowerResult
import com.abhinesh.tvremote.core.RemoteSession
import com.abhinesh.tvremote.core.proto.RemoteMessages.Feature
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class ExecResult(val outcome: Outcome, val message: String) {
    enum class Outcome {
        /** The TV reported the expected result (app in front, Home, volume, power state). */
        CONFIRMED,
        /** Sent; the TV doesn't report an outcome for this action, or didn't confirm it. */
        SENT,
        NOTHING_TO_DO,
        /** Needs a step on the TV, e.g. answering an "Open with" chooser. */
        NEEDS_YOU,
        FAILED,
        UNSUPPORTED,
    }
}

/** Runs a [VoiceAction] on the TV and verifies it from the TV's own reports where possible. */
class VoiceExecutor(
    private val session: () -> RemoteSession?,
    private val launch: suspend (AppShortcut) -> LaunchResult,
    private val power: suspend (Boolean) -> PowerResult,
    private val appName: (String) -> String = { it },
    private val confirmMs: Long = 3_000,
    private val keyGapMs: Long = 130,
) {
    suspend fun execute(action: VoiceAction): ExecResult {
        when (action) {
            is VoiceAction.OpenApp -> return fromLaunch(launch(action.app))
            VoiceAction.PowerOff -> return fromPower(power(false))
            VoiceAction.PowerOn -> return fromPower(power(true))
            else -> {}
        }
        val s = session()?.takeIf { it.isOpen } ?: return ExecResult(ExecResult.Outcome.FAILED, "Not connected to the TV.")
        return when (action) {
            is VoiceAction.CloseApp -> close(s, action.app)
            is VoiceAction.Volume -> volume(s, action.up, action.steps)
            VoiceAction.Mute -> mute(s, true)
            VoiceAction.Unmute -> mute(s, false)
            is VoiceAction.Key -> {
                repeat(action.times) { i -> if (i > 0) delay(keyGapMs); s.sendKey(action.keyCode) }
                ExecResult(ExecResult.Outcome.SENT, "Sent ${action.summary}.")
            }
            is VoiceAction.Type -> {
                if (!s.status.value.has(Feature.IME)) ExecResult(ExecResult.Outcome.UNSUPPORTED, "This TV didn't enable keyboard input.")
                else if (s.sendText(action.text)) ExecResult(ExecResult.Outcome.SENT, "Typed into the TV's selected text field.")
                else ExecResult(ExecResult.Outcome.FAILED, "Couldn't send the text.")
            }
            else -> ExecResult(ExecResult.Outcome.FAILED, "Unsupported action.")
        }
    }

    private suspend fun close(s: RemoteSession, target: AppShortcut?): ExecResult {
        val cur = s.status.value.currentApp
        if (cur != null && isHome(cur)) return ExecResult(ExecResult.Outcome.NOTHING_TO_DO, "Already on the Home screen.")
        if (target != null && cur != null && cur != target.packageName) {
            return ExecResult(ExecResult.Outcome.NOTHING_TO_DO, "${target.label} isn't open. The TV is showing ${appName(cur)}, so nothing was closed.")
        }
        s.sendKey(KeyCodes.HOME)
        val home = withTimeoutOrNull(confirmMs) { s.status.first { it.currentApp?.let(::isHome) == true } } != null
        val left = target?.label ?: cur?.let(appName) ?: "the app"
        return when {
            home -> ExecResult(ExecResult.Outcome.CONFIRMED, "Left $left; the TV is on the Home screen. (The app may keep running in the background.)")
            cur == null -> ExecResult(ExecResult.Outcome.SENT, "Home pressed. The TV doesn't report which app is open, so this can't be confirmed.")
            else -> ExecResult(ExecResult.Outcome.SENT, "Home pressed, but the TV didn't confirm the Home screen.")
        }
    }

    private suspend fun volume(s: RemoteSession, up: Boolean, steps: Int): ExecResult {
        val st = s.status.value
        val before = st.volumeLevel
        val max = st.volumeMax
        if (before != null && ((up && max != null && before >= max) || (!up && before <= 0))) {
            return ExecResult(ExecResult.Outcome.NOTHING_TO_DO, if (up) "Volume is already at maximum ($before)." else "Volume is already at 0.")
        }
        repeat(steps) { i -> if (i > 0) delay(keyGapMs); s.sendKey(if (up) KeyCodes.VOLUME_UP else KeyCodes.VOLUME_DOWN) }
        if (before == null) return ExecResult(ExecResult.Outcome.SENT, "Sent volume ${if (up) "up" else "down"} ×$steps. The TV doesn't report its volume.")
        val after = withTimeoutOrNull(confirmMs) {
            s.status.first { v -> v.volumeLevel != null && if (up) v.volumeLevel > before else v.volumeLevel < before }
        }?.volumeLevel
        // Give the remaining presses a moment to be reported too.
        if (after != null) delay(keyGapMs * 2)
        val final = s.status.value.volumeLevel
        return if (after != null) ExecResult(ExecResult.Outcome.CONFIRMED, "Volume $before → $final.")
        else ExecResult(ExecResult.Outcome.SENT, "Sent volume ${if (up) "up" else "down"} ×$steps, but the TV still reports $before (sound may be going to an external speaker).")
    }

    private suspend fun mute(s: RemoteSession, wantMuted: Boolean): ExecResult {
        val now = s.status.value.muted
        if (now == wantMuted) return ExecResult(ExecResult.Outcome.NOTHING_TO_DO, if (wantMuted) "The TV is already muted." else "The TV isn't muted.")
        s.sendKey(KeyCodes.VOLUME_MUTE)
        val got = withTimeoutOrNull(confirmMs) { s.status.first { it.muted == wantMuted } } != null
        return when {
            got -> ExecResult(ExecResult.Outcome.CONFIRMED, if (wantMuted) "The TV is muted." else "The TV is unmuted.")
            now == null -> ExecResult(ExecResult.Outcome.SENT, "Mute key sent. The TV doesn't report its mute state.")
            else -> ExecResult(ExecResult.Outcome.SENT, "Mute key sent, but the TV didn't report the change.")
        }
    }

    private fun fromLaunch(r: LaunchResult): ExecResult = when (r) {
        is LaunchResult.Confirmed, is LaunchResult.AlreadyOpen -> ExecResult(ExecResult.Outcome.CONFIRMED, r.message)
        is LaunchResult.ChooserShown, is LaunchResult.PlayStoreOpened -> ExecResult(ExecResult.Outcome.NEEDS_YOU, r.message)
        is LaunchResult.SentUnverified -> ExecResult(ExecResult.Outcome.SENT, r.message)
        is LaunchResult.Unsupported -> ExecResult(ExecResult.Outcome.UNSUPPORTED, r.message)
        is LaunchResult.Rejected, is LaunchResult.NotConfirmed, LaunchResult.NotConnected -> ExecResult(ExecResult.Outcome.FAILED, r.message)
    }

    private fun fromPower(r: PowerResult): ExecResult = when (r) {
        is PowerResult.Done -> ExecResult(ExecResult.Outcome.CONFIRMED, r.message)
        is PowerResult.AlreadyInState -> ExecResult(ExecResult.Outcome.NOTHING_TO_DO, r.message)
        is PowerResult.NotConfirmed -> ExecResult(ExecResult.Outcome.SENT, r.message)
        is PowerResult.Unreachable -> ExecResult(ExecResult.Outcome.FAILED, r.message)
    }

    companion object {
        private val HOME_PACKAGES = setOf(
            "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher", "com.mitv.tvhome",
        )

        fun isHome(pkg: String) = pkg in HOME_PACKAGES || pkg.endsWith(".launcher") || pkg.contains("launcherx")
    }
}
