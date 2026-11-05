package com.abhinesh.tvremote.core

import com.abhinesh.tvremote.core.voice.ExecResult.Outcome
import com.abhinesh.tvremote.core.voice.VoiceAction
import com.abhinesh.tvremote.core.voice.VoiceExecutor
import com.abhinesh.tvremote.core.tls.ClientIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** End-to-end: voice action -> executor -> real protocol -> simulated TV, verified from TV reports. */
class VoiceExecutionTest {
    private val tv = FakeTv()
    private val phone = ClientIdentity.generate("test phone")
    private lateinit var controller: TvController
    @AfterTest fun tearDown() { controller.stop(); tv.close() }

    private fun app(id: String) = AppShortcut.DEFAULTS.first { it.id == id }

    private suspend fun connected(): VoiceExecutor {
        PairingSession("127.0.0.1", phone, "t", port = tv.pairingPort).use { it.start(); it.submitCode(tv.currentCode!!) }
        controller = TvController(identity = { phone }, maxBackoffMs = 300)
        controller.start(TvEndpoint("127.0.0.1", tv.remotePort))
        awaitSession(6_000)
        return VoiceExecutor(
            session = { controller.session.value },
            launch = { AppLauncher.launch(it, confirmTimeoutMs = 2_000) { controller.session.value } },
            power = { on ->
                if (on) PowerControl.turnOn({ controller.session.value }, wake = null, awaitSession = ::awaitSession, verifyMs = 2_000)
                else PowerControl.turnOff(controller.session.value, verifyMs = 2_000)
            },
        )
    }

    private suspend fun awaitSession(ms: Long): RemoteSession? = withTimeoutOrNull(ms) {
        controller.state.first { it is ConnectionState.Connected }
        controller.session.first { it != null && it.isOpen && it.status.value.isOn != null }
    }

    @Test fun switchesBetweenAppsAndClosesToHome() = runBlocking<Unit> {
        val ex = connected()
        tv.linkOutcome = { link ->
            FakeTv.LinkOutcome.Open(if (link.startsWith("netflix")) "com.netflix.ninja" else "com.google.android.youtube.tv")
        }
        assertEquals(Outcome.CONFIRMED, ex.execute(VoiceAction.OpenApp(app("netflix"))).outcome)
        assertEquals(Outcome.CONFIRMED, ex.execute(VoiceAction.OpenApp(app("youtube"))).outcome) // switch
        assertEquals("com.google.android.youtube.tv", tv.currentApp)

        // "close Netflix" while YouTube is in front: nothing is closed, nothing is opened.
        val wrong = ex.execute(VoiceAction.CloseApp(app("netflix")))
        assertEquals(Outcome.NOTHING_TO_DO, wrong.outcome)
        assertEquals("com.google.android.youtube.tv", tv.currentApp)
        assertTrue(tv.keys.none { it.first == KeyCodes.HOME })

        val closed = ex.execute(VoiceAction.CloseApp(null))
        assertEquals(Outcome.CONFIRMED, closed.outcome)
        assertEquals(FakeTv.HOME_APP, tv.currentApp)
        assertEquals(Outcome.NOTHING_TO_DO, ex.execute(VoiceAction.CloseApp(null)).outcome)
    }

    @Test fun searchUsesSearchLinkOrFallsBackToOpeningTheApp() = runBlocking<Unit> {
        val ex = connected()
        tv.linkOutcome = { FakeTv.LinkOutcome.Open("com.netflix.ninja") }
        val ok = ex.execute(VoiceAction.Search(app("netflix"), "stranger things"))
        assertEquals(Outcome.CONFIRMED, ok.outcome)
        assertEquals("https://www.netflix.com/search?q=stranger+things", tv.links.last())

        ex.execute(VoiceAction.CloseApp(null))
        tv.linkOutcome = { l -> if (l.contains("results?")) FakeTv.LinkOutcome.Reject else FakeTv.LinkOutcome.Open("com.google.android.youtube.tv") }
        val fallback = ex.execute(VoiceAction.Search(app("youtube"), "pushpa"))
        assertEquals(Outcome.NEEDS_YOU, fallback.outcome) // app opened, but you search yourself
        assertTrue(fallback.message.contains("didn't accept the search"))
    }

    @Test fun volumeAndMuteAreVerified() = runBlocking<Unit> {
        val ex = connected()
        val r = ex.execute(VoiceAction.Volume(true, 3))
        assertEquals(Outcome.CONFIRMED, r.outcome)
        assertEquals(20, tv.volume)
        assertEquals(Outcome.CONFIRMED, ex.execute(VoiceAction.Volume(false, 2)).outcome)
        assertEquals(18, tv.volume)

        assertEquals(Outcome.NOTHING_TO_DO, ex.execute(VoiceAction.Unmute).outcome) // not muted: no toggle sent
        assertEquals(Outcome.CONFIRMED, ex.execute(VoiceAction.Mute).outcome)
        assertTrue(tv.muted)
        assertEquals(Outcome.NOTHING_TO_DO, ex.execute(VoiceAction.Mute).outcome) // already muted: no toggle
        assertTrue(tv.muted)
        assertEquals(Outcome.CONFIRMED, ex.execute(VoiceAction.Unmute).outcome)
        assertTrue(!tv.muted)
    }

    @Test fun volumeWithoutTvReportsIsOnlySent() = runBlocking<Unit> {
        tv.reportsVolume = false
        val ex = connected()
        assertEquals(Outcome.SENT, ex.execute(VoiceAction.Volume(true, 2)).outcome)
    }

    @Test fun powerOffAndOnWhileTvStaysReachable() = runBlocking<Unit> {
        val ex = connected()
        assertEquals(Outcome.CONFIRMED, ex.execute(VoiceAction.PowerOff).outcome)
        assertTrue(!tv.isOn)
        assertTrue(tv.keys.any { it.first == KeyCodes.SLEEP } && tv.keys.none { it.first == KeyCodes.POWER })
        assertEquals(Outcome.NOTHING_TO_DO, ex.execute(VoiceAction.PowerOff).outcome)
        assertEquals(Outcome.CONFIRMED, ex.execute(VoiceAction.PowerOn).outcome)
        assertTrue(tv.isOn)
        assertTrue(tv.keys.any { it.first == KeyCodes.WAKEUP })
    }

    @Test fun powerKeyOnlyUsedWhenSleepIsIgnoredAndStateKnown() = runBlocking<Unit> {
        tv.ignoreSleepKey = true
        val ex = connected()
        assertEquals(Outcome.CONFIRMED, ex.execute(VoiceAction.PowerOff).outcome)
        assertEquals(listOf(KeyCodes.SLEEP, KeyCodes.POWER), tv.keys.map { it.first }.filter { it == KeyCodes.SLEEP || it == KeyCodes.POWER })
    }

    @Test fun standbyThatDropsWifiIsReportedHonestly() = runBlocking<Unit> {
        tv.networkOffInStandby = true
        val ex = connected()
        val off = ex.execute(VoiceAction.PowerOff)
        assertEquals(Outcome.CONFIRMED, off.outcome)
        withTimeout(5_000) { controller.state.first { it !is ConnectionState.Connected } }
        // No network wake available: must not claim the TV turned on.
        val on = ex.execute(VoiceAction.PowerOn)
        assertEquals(Outcome.FAILED, on.outcome)
        assertTrue(on.message.contains("isn't on the network"))
        assertTrue(!tv.isOn)
    }

    @Test fun networkWakeReconnectsAndConfirms() = runBlocking<Unit> {
        tv.networkOffInStandby = true
        connected()
        PowerControl.turnOff(controller.session.value, verifyMs = 2_000)
        withTimeout(5_000) { controller.state.first { it !is ConnectionState.Connected } }
        val r = PowerControl.turnOn(
            session = { controller.session.value },
            wake = { tv.wakeFromNetwork(); controller.retryNow(); true },
            awaitSession = ::awaitSession, verifyMs = 2_000, reconnectMs = 8_000,
        )
        assertIs<PowerResult.Done>(r)
        assertTrue(controller.state.value is ConnectionState.Connected)
    }

    @Test fun networkWakeThatDoesNothingIsNotReportedAsSuccess() = runBlocking<Unit> {
        tv.networkOffInStandby = true
        connected()
        PowerControl.turnOff(controller.session.value, verifyMs = 2_000)
        withTimeout(5_000) { controller.state.first { it !is ConnectionState.Connected } }
        val r = PowerControl.turnOn({ controller.session.value }, wake = { true }, awaitSession = ::awaitSession, reconnectMs = 1_500)
        assertIs<PowerResult.NotConfirmed>(r)
    }
}
