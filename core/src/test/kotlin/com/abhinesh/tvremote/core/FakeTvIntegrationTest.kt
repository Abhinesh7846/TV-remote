package com.abhinesh.tvremote.core

import com.abhinesh.tvremote.core.proto.RemoteMessages.Direction
import com.abhinesh.tvremote.core.proto.RemoteMessages.Feature
import com.abhinesh.tvremote.core.tls.ClientIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeTvIntegrationTest {
    private val tv = FakeTv()
    private val phone = ClientIdentity.generate("TVRemote test phone")
    private val host = "127.0.0.1"

    @AfterTest fun tearDown() = tv.close()

    private suspend fun pair(client: ClientIdentity = phone) {
        PairingSession(host, client, "test", port = tv.pairingPort).use { p ->
            p.start()
            p.submitCode(tv.currentCode!!)
        }
    }

    private suspend fun connect(idleMs: Long = 16_000): RemoteSession =
        RemoteSession(host, phone, null, port = tv.remotePort, idleTimeoutMs = idleMs).also { it.connect() }

    private suspend fun <T> eventually(timeoutMs: Long = 5_000, check: () -> T?): T = withTimeout(timeoutMs) {
        while (true) { check()?.let { return@withTimeout it }; delay(25) }
        @Suppress("UNREACHABLE_CODE") error("")
    }

    @Test fun pairsAndConnects() = runBlocking {
        pair()
        assertTrue(tv.isPaired(phone))
        val s = connect()
        val st = s.status.value
        assertEquals(623 and RemoteSession.DEFAULT_FEATURES, st.features)
        assertEquals(true, st.isOn)
        assertEquals(17, s.status.first { it.volumeLevel != null }.volumeLevel)
        assertTrue(s.sendKey(KeyCodes.HOME))
        assertEquals(KeyCodes.HOME to 3, eventually { tv.keys.firstOrNull() })
        s.close()
    }

    @Test fun mistypedCodeIsCaughtLocallyAndSessionStaysUsable() = runBlocking {
        PairingSession(host, phone, "test", port = tv.pairingPort).use { p ->
            p.start()
            val real = tv.currentCode!!
            val typo = real.substring(0, 5) + if (real[5] == '0') '1' else '0'
            // A one-character typo almost always fails the check byte (1/256 chance it doesn't).
            if (!p.isCodeConsistent(typo)) {
                val e = assertFailsWith<PairingException> { p.submitCode(typo) }
                assertEquals(PairingException.Reason.CODE_MISTYPED, e.reason)
            }
            assertFailsWith<PairingException> { p.submitCode("12345") }.also { assertEquals(PairingException.Reason.CODE_MISTYPED, it.reason) }
            p.submitCode(real.lowercase()) // still works after the typos, case-insensitive
        }
        assertTrue(tv.isPaired(phone))
    }

    @Test fun wrongCodeRejectedByTv() = runBlocking {
        PairingSession(host, phone, "test", port = tv.pairingPort).use { p ->
            p.start()
            val wrong = tv.wrongCodeWithValidCheckByte(phone)
            val e = assertFailsWith<PairingException> { p.submitCode(wrong) }
            assertEquals(PairingException.Reason.REJECTED_BY_TV, e.reason)
        }
        assertTrue(!tv.isPaired(phone))
    }

    @Test fun unpairedPhoneNeedsPairing() = runBlocking {
        val s = RemoteSession(host, phone, null, port = tv.remotePort)
        assertFailsWith<PairingRequiredException> { s.connect() }
        Unit
    }

    @Test fun unavailableTvReportsCannotConnect() = runBlocking {
        val freePort = ServerSocket(0).use { it.localPort }
        assertFailsWith<CannotConnectException> { RemoteSession(host, phone, null, port = freePort).connect() }
        val e = assertFailsWith<PairingException> { PairingSession(host, phone, "test", port = freePort).start() }
        assertEquals(PairingException.Reason.CANNOT_CONNECT, e.reason)
    }

    @Test fun answersPings() = runBlocking {
        tv.pingIntervalMs = 200
        pair()
        val s = connect()
        eventually { tv.pingResponses.takeIf { it.size >= 2 } }
        s.close()
    }

    @Test fun silentTvIsDetected() = runBlocking {
        pair()
        val s = connect(idleMs = 1_500)
        tv.silent = true
        val reason = withTimeout(8_000) { s.closed.await() }
        assertIs<ConnectionClosedException>(reason)
        assertTrue(reason.message!!.contains("stopped responding"))
    }

    @Test fun longPressSendsDownThenUp() = runBlocking {
        pair()
        val s = connect()
        s.sendKey(KeyCodes.DPAD_CENTER, Direction.START_LONG)
        s.sendKey(KeyCodes.DPAD_CENTER, Direction.END_LONG)
        assertEquals(listOf(23 to 1, 23 to 2), eventually { tv.keys.toList().takeIf { it.size >= 2 } })
        s.close()
    }

    @Test fun textGoesThroughIme() = runBlocking {
        pair()
        val s = connect()
        assertTrue(s.sendText("hello"))
        assertEquals("hello", eventually { tv.texts.firstOrNull() })
        s.close()
    }

    @Test fun controllerReconnectsAfterDropAndOutage() = runBlocking {
        pair()
        val controller = TvController(identity = { phone }, maxBackoffMs = 400)
        controller.start(TvEndpoint(host, tv.remotePort))
        withTimeout(5_000) { controller.state.first { it is ConnectionState.Connected } }
        assertEquals(1, tv.remoteConnections)

        tv.dropConnections() // Wi-Fi blip
        withTimeout(5_000) { controller.state.first { it is ConnectionState.Retrying } }
        withTimeout(5_000) { controller.state.first { it is ConnectionState.Connected } }
        assertTrue(tv.remoteConnections >= 2)

        val port = tv.remotePort
        tv.stopRemote() // TV off / unreachable
        val retry = withTimeout(5_000) { controller.state.first { it is ConnectionState.Retrying && it.attempt >= 2 } }
        assertIs<ConnectionState.Retrying>(retry)
        tv.startRemote(port) // TV back
        withTimeout(8_000) { controller.state.first { it is ConnectionState.Connected } }
        assertTrue(controller.session.value!!.sendKey(KeyCodes.BACK))
        controller.stop()
    }

    @Test fun controllerStopsWhenPairingIsNeeded() = runBlocking {
        val controller = TvController(identity = { phone }, maxBackoffMs = 300, log = { println("ctl: $it") })
        controller.start(TvEndpoint(host, tv.remotePort))
        val st = withTimeout(8_000) { controller.state.first { println("state: $it"); it is ConnectionState.PairingNeeded } }
        assertIs<ConnectionState.PairingNeeded>(st)
        controller.stop()
    }

    @Test fun controllerDetectsDifferentTvAtSameAddress() = runBlocking {
        pair()
        val controller = TvController(identity = { phone }, maxBackoffMs = 300)
        controller.start(TvEndpoint(host, tv.remotePort, pinnedKeySha256 = ByteArray(32)))
        val st = withTimeout(8_000) { controller.state.first { it is ConnectionState.IdentityChanged } }
        assertIs<ConnectionState.IdentityChanged>(st)
        controller.stop()
    }

    @Test fun appLaunchConfirmedChooserAndRejection() = runBlocking {
        pair()
        val controller = TvController(identity = { phone }, maxBackoffMs = 300)
        controller.start(TvEndpoint(host, tv.remotePort))
        val provider: suspend () -> RemoteSession? = {
            withTimeout(6_000) { controller.state.first { it is ConnectionState.Connected } }
            controller.session.value
        }
        val netflix = AppShortcut.DEFAULTS.first { it.id == "netflix" }
        tv.linkOutcome = { FakeTv.LinkOutcome.Open("com.netflix.ninja") }
        assertIs<LaunchResult.Confirmed>(AppLauncher.launch(netflix, session = provider))

        tv.linkOutcome = { FakeTv.LinkOutcome.Open(AppLauncher.CHOOSER) }
        assertIs<LaunchResult.ChooserShown>(AppLauncher.launch(AppShortcut.DEFAULTS.first { it.id == "youtube" }, session = provider))

        // First link refused (TV closes the connection), second link works after the reconnect.
        val hotstar = AppShortcut.DEFAULTS.first { it.id == "jiohotstar" }
        tv.linkOutcome = { link -> if (link == "hotstar://") FakeTv.LinkOutcome.Reject else FakeTv.LinkOutcome.Open("in.startv.hotstar") }
        val r = AppLauncher.launch(hotstar, session = provider)
        assertIs<LaunchResult.Confirmed>(r)
        assertEquals("https://www.hotstar.com/in", r.via)

        tv.linkOutcome = { FakeTv.LinkOutcome.Ignore }
        assertIs<LaunchResult.NotConfirmed>(AppLauncher.launch(netflix, confirmTimeoutMs = 800, session = provider))
        controller.stop()
    }

    @Test fun voiceSessionStreamsChunkedAudio() = runBlocking {
        pair()
        val s = connect()
        val id = s.startVoice()
        assertEquals(7, id)
        s.sendVoiceAudio(id, ByteArray(45_000))
        s.sendVoiceAudio(id, ByteArray(100))
        s.endVoice(id)
        val sizes = eventually { tv.voicePayloadSizes.toList().takeIf { it.size >= 4 } }
        assertEquals(listOf(20_480, 20_480, 8_192, 8_192), sizes) // split at 20 KB, padded to 8 KB
        assertEquals(7, eventually { tv.voiceEnds.firstOrNull() })
        s.close()
    }

    @Test fun voiceUnavailableWhenTvDoesNotOfferIt() = runBlocking {
        tv.features = 623 and Feature.VOICE.inv()
        pair()
        val s = connect()
        assertFailsWith<VoiceUnavailableException> { s.startVoice() }
        s.close()
    }

    @Test fun voiceUnavailableWhenTvNeverStartsSession() = runBlocking {
        tv.answerVoice = false
        pair()
        val s = connect()
        val e = assertFailsWith<VoiceUnavailableException> { s.startVoice(timeoutMs = 700) }
        assertTrue(e.message!!.contains("didn't start a voice session"))
        s.close()
    }
}
