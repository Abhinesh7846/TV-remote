package com.abhinesh.tvremote.core

import com.abhinesh.tvremote.core.share.LinkParser
import com.abhinesh.tvremote.core.share.ParseResult
import com.abhinesh.tvremote.core.share.SendResult
import com.abhinesh.tvremote.core.share.SendToTv
import com.abhinesh.tvremote.core.share.SharedLink
import com.abhinesh.tvremote.core.share.StreamingService
import com.abhinesh.tvremote.core.tls.ClientIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinkParserTest {
    private fun ok(text: String) = assertIs<ParseResult.Ok>(LinkParser.parse(text), text).link

    @Test fun youtube() {
        val a = ok("https://youtu.be/dQw4w9WgXcQ?si=abc123")
        assertEquals(StreamingService.YOUTUBE, a.service)
        assertEquals(listOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), a.tvLinks)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=90s", ok("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1m30s").tvLinks[0])
        assertEquals("dQw4w9WgXcQ", ok("https://m.youtube.com/shorts/dQw4w9WgXcQ").id)
        assertEquals(SharedLink.Kind.PLAYLIST, ok("https://youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG").kind)
        assertIs<ParseResult.AppOnly>(LinkParser.parse("https://www.youtube.com/@SomeChannel"))
        assertIs<ParseResult.Unsupported>(LinkParser.parse("https://youtu.be/short"))
    }

    @Test fun netflix() {
        val t = ok("Check out \"Stranger Things\" on Netflix https://www.netflix.com/in/title/80057281?s=a&trkid=13747225&vlang=en.")
        assertEquals(SharedLink.Kind.TITLE, t.kind)
        assertEquals(listOf("https://www.netflix.com/title/80057281?source=30"), t.tvLinks)
        val e = ok("https://www.netflix.com/watch/80077209?trackId=14170286")
        assertEquals(SharedLink.Kind.EPISODE, e.kind)
        assertEquals("https://www.netflix.com/watch/80077209?source=30", e.tvLinks[0])
        assertIs<ParseResult.AppOnly>(LinkParser.parse("https://www.netflix.com/browse"))
    }

    @Test fun jioHotstar() {
        val s = ok("https://www.hotstar.com/in/shows/the-night-manager/1260130437")
        assertEquals(StreamingService.JIOHOTSTAR, s.service)
        assertEquals("https://www.hotstar.com/in/shows/the-night-manager/1260130437", s.tvLinks[0])
        val j = ok("Watch on JioHotstar: https://www.jiohotstar.com/in/movies/jawan/1271223443")
        assertEquals("https://www.hotstar.com/in/movies/jawan/1271223443", j.tvLinks[0]) // rewritten to the host the TV accepted
        assertEquals(SharedLink.Kind.TITLE, j.kind)
        assertEquals(SharedLink.Kind.EPISODE, ok("https://www.hotstar.com/in/shows/x/1260130437/episode-1/1260130500/watch").kind)
        assertIs<ParseResult.AppOnly>(LinkParser.parse("https://www.hotstar.com/in/home"))
    }

    @Test fun primeVideo() {
        val g = ok("https://app.primevideo.com/detail?gti=amzn1.dv.gti.0eb8a0a8-1f1b-4c60-9d5e-123456789abc&ref_=atv_dp_share_mv&r=web")
        assertEquals(listOf("https://app.primevideo.com/detail?gti=amzn1.dv.gti.0eb8a0a8-1f1b-4c60-9d5e-123456789abc"), g.tvLinks)
        assertEquals(StreamingService.PRIME_VIDEO, ok("https://www.primevideo.com/detail/0QZ1ABCDEFGH/ref=atv_sr").service)
        assertEquals("B0B8XYZ123", ok("https://www.amazon.in/gp/video/detail/B0B8XYZ123/ref=atv_dp").id)
        assertIs<ParseResult.Unsupported>(LinkParser.parse("https://www.amazon.in/dp/B09G9FPHY6"))
    }

    @Test fun shortLinksNeedResolving() {
        assertIs<ParseResult.NeedsResolve>(LinkParser.parse("https://a.co/d/abc123"))
        assertIs<ParseResult.NeedsResolve>(LinkParser.parse("Watch this https://hotstar.page.link/xyz"))
        assertIs<ParseResult.NeedsResolve>(LinkParser.parse("https://jiohotstar.onelink.me/abc/def"))
    }

    @Test fun badLinks() {
        assertIs<ParseResult.Unsupported>(LinkParser.parse("just some words"))
        assertIs<ParseResult.Unsupported>(LinkParser.parse(""))
        assertIs<ParseResult.Unsupported>(LinkParser.parse("https://example.com/video/1"))
        assertIs<ParseResult.Unsupported>(LinkParser.parse("ftp://youtube.com/watch?v=dQw4w9WgXcQ"))
    }
}

class SendToTvTest {
    private val tv = FakeTv()
    private val phone = ClientIdentity.generate("test phone")
    private lateinit var controller: TvController
    @AfterTest fun tearDown() { controller.stop(); tv.close() }

    private suspend fun session(): RemoteSession? = controller.session.value

    private suspend fun connect() {
        PairingSession("127.0.0.1", phone, "t", port = tv.pairingPort).use { it.start(); it.submitCode(tv.currentCode!!) }
        controller = TvController(identity = { phone }, maxBackoffMs = 300)
        controller.start(TvEndpoint("127.0.0.1", tv.remotePort))
        withTimeout(6_000) { controller.state.first { it is ConnectionState.Connected } }
    }

    private fun link(text: String) = (LinkParser.parse(text) as ParseResult.Ok).link

    @Test fun exactLinkOpensTheApp() = runBlocking<Unit> {
        connect()
        tv.linkOutcome = { l -> FakeTv.LinkOutcome.Open(if (l.contains("youtube")) "com.google.android.youtube.tv" else "x") }
        val r = SendToTv.send(link("https://youtu.be/dQw4w9WgXcQ"), 2_000, ::session)
        assertIs<SendResult.LinkOpenedApp>(r)
        assertEquals(listOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), tv.links)
    }

    @Test fun refusedLinkFallsBackToNextFormThenReportsUnsupported() = runBlocking<Unit> {
        connect()
        tv.linkOutcome = { l -> if (l.contains("/watch/")) FakeTv.LinkOutcome.Reject else FakeTv.LinkOutcome.Open("com.netflix.ninja") }
        val r = SendToTv.send(link("https://www.netflix.com/watch/80077209"), 2_000, ::session)
        assertIs<SendResult.LinkOpenedApp>(r)
        assertTrue(tv.links.last().contains("/title/80077209"))

        tv.linkOutcome = { FakeTv.LinkOutcome.Reject }
        assertIs<SendResult.Unsupported>(SendToTv.send(link("https://www.netflix.com/title/80057281"), 2_000, ::session))
    }

    @Test fun ignoredLinkIsNotReportedAsOpened() = runBlocking<Unit> {
        connect()
        tv.linkOutcome = { FakeTv.LinkOutcome.Ignore }
        assertIs<SendResult.AppNotOpened>(SendToTv.send(link("https://www.primevideo.com/detail/0QZ1ABCDEFGH"), 1_000, ::session))
    }

    @Test fun disconnectedTvIsOffline() = runBlocking<Unit> {
        connect()
        controller.stop()
        assertIs<SendResult.Offline>(SendToTv.send(link("https://youtu.be/dQw4w9WgXcQ"), 1_000, ::session))
    }
}
