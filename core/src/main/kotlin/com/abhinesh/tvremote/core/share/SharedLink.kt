package com.abhinesh.tvremote.core.share

import java.net.URI
import java.net.URLDecoder

/** Streaming services the Send to TV feature understands. [shortcutId] matches AppShortcut.DEFAULTS. */
enum class StreamingService(val label: String, val packageName: String, val shortcutId: String) {
    YOUTUBE("YouTube", "com.google.android.youtube.tv", "youtube"),
    NETFLIX("Netflix", "com.netflix.ninja", "netflix"),
    JIOHOTSTAR("JioHotstar", "in.startv.hotstar", "jiohotstar"),
    PRIME_VIDEO("Prime Video", "com.amazon.amazonvideo.livingroom", "primevideo"),
}

/**
 * A recognised link, normalised. [tvLinks] are tried on the TV in order; each is a link the
 * TV app may accept for this exact item. [kind] describes what the link points at.
 */
data class SharedLink(
    val service: StreamingService,
    val kind: Kind,
    val id: String,
    val original: String,
    val tvLinks: List<String>,
) {
    enum class Kind(val label: String) { VIDEO("video"), PLAYLIST("playlist"), TITLE("title"), EPISODE("episode"), PAGE("page") }

    val description: String get() = "${service.label} ${kind.label}"
}

sealed interface ParseResult {
    data class Ok(val link: SharedLink) : ParseResult
    /** A known short or redirect link; resolve it (follow redirects) and parse the result. */
    data class NeedsResolve(val url: String) : ParseResult
    /** A link to a known service that can't point the TV at a specific item; the app can still be opened. */
    data class AppOnly(val service: StreamingService, val reason: String) : ParseResult
    data class Unsupported(val reason: String) : ParseResult
}

object LinkParser {
    private val URL_IN_TEXT = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val YT_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val DIGITS = Regex("^\\d{5,12}$")

    /** Hosts that only redirect to the real page. Resolving them contacts that host. */
    val SHORTENER_HOSTS = setOf(
        "a.co", "amzn.to", "amzn.in", "amzn.eu", "bit.ly", "tinyurl.com", "t.co", "goo.gl", "rb.gy",
        "hotstar.page.link", "jiohotstar.page.link", "hotstar.onelink.me", "jiohotstar.onelink.me",
        "watch.amazon.in", "primevideo.page.link", "nflx.it",
    )

    /** Pulls the first web link out of shared text such as "Watch X on Netflix: https://…". */
    fun extractUrl(text: String?): String? =
        text?.let { URL_IN_TEXT.find(it)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';') }

    fun parse(text: String?): ParseResult {
        val raw = extractUrl(text) ?: return ParseResult.Unsupported("That isn't a web link. Share or paste a link that starts with https://")
        val uri = runCatching { URI(raw) }.getOrNull() ?: return ParseResult.Unsupported("That link is malformed.")
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return ParseResult.Unsupported("That link has no website address.")
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return ParseResult.Unsupported("Only web links are supported.")
        if (host in SHORTENER_HOSTS || host.endsWith(".page.link") || host.endsWith(".onelink.me") || host.endsWith(".app.link")) {
            return ParseResult.NeedsResolve(raw)
        }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val segs = path.split('/').filter { it.isNotEmpty() }
        val query = query(uri)
        return when {
            host == "youtu.be" -> youtubeVideo(segs.firstOrNull(), query, raw)
            host == "youtube.com" || host == "music.youtube.com" || host.endsWith(".youtube.com") -> youtube(segs, query, raw)
            host == "netflix.com" || host.endsWith(".netflix.com") -> netflix(segs, raw)
            host == "hotstar.com" || host == "jiohotstar.com" || host.endsWith(".hotstar.com") -> hotstar(segs, raw)
            host == "primevideo.com" || host == "app.primevideo.com" || host.endsWith(".primevideo.com") -> prime(segs, query, raw)
            host == "amazon.in" || host == "amazon.com" || host == "amazon.co.uk" -> amazon(segs, raw)
            else -> ParseResult.Unsupported("Links from $host aren't supported. Supported: YouTube, Netflix, JioHotstar, Prime Video.")
        }
    }

    private fun query(uri: URI): Map<String, String> = uri.rawQuery.orEmpty().split('&').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i) to runCatching { URLDecoder.decode(it.substring(i + 1), "UTF-8") }.getOrDefault("")
    }.toMap()

    // ---- YouTube: the TV app accepts https://www.youtube.com/watch?v=<id> (Home Assistant docs; tested).
    private fun youtube(segs: List<String>, q: Map<String, String>, raw: String): ParseResult = when {
        segs.firstOrNull() == "watch" -> youtubeVideo(q["v"], q, raw)
        segs.size >= 2 && segs[0] in setOf("shorts", "live", "embed", "v") -> youtubeVideo(segs[1], q, raw)
        segs.firstOrNull() == "playlist" && !q["list"].isNullOrBlank() -> ParseResult.Ok(
            SharedLink(StreamingService.YOUTUBE, SharedLink.Kind.PLAYLIST, q["list"]!!, raw, listOf("https://www.youtube.com/playlist?list=${q["list"]}")),
        )
        else -> ParseResult.AppOnly(StreamingService.YOUTUBE, "This YouTube link isn't a single video or playlist.")
    }

    private fun youtubeVideo(id: String?, q: Map<String, String>, raw: String): ParseResult {
        if (id == null || !YT_ID.matches(id)) return ParseResult.Unsupported("That YouTube link doesn't contain a valid video ID.")
        val start = (q["t"] ?: q["start"])?.let(::seconds)
        val link = "https://www.youtube.com/watch?v=$id" + (start?.let { "&t=${it}s" } ?: "")
        return ParseResult.Ok(SharedLink(StreamingService.YOUTUBE, SharedLink.Kind.VIDEO, id, raw, listOf(link)))
    }

    /** "90", "90s", "1m30s", "1h2m3s" -> seconds. */
    private fun seconds(t: String): Int? {
        t.toIntOrNull()?.let { return it }
        val m = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").matchEntire(t) ?: return null
        val (h, mi, s) = m.destructured
        val total = (h.toIntOrNull() ?: 0) * 3600 + (mi.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
        return total.takeIf { it > 0 }
    }

    // ---- Netflix: titles and episodes are numeric IDs; "?source=30" makes the TV app open that title (HA docs).
    private fun netflix(segs: List<String>, raw: String): ParseResult {
        val i = segs.indexOfFirst { it == "title" || it == "watch" }
        val id = segs.getOrNull(i + 1)
        if (i < 0 || id == null || !DIGITS.matches(id)) return ParseResult.AppOnly(StreamingService.NETFLIX, "This Netflix link doesn't point at a title.")
        return if (segs[i] == "watch") ParseResult.Ok(
            SharedLink(StreamingService.NETFLIX, SharedLink.Kind.EPISODE, id, raw,
                listOf("https://www.netflix.com/watch/$id?source=30", "https://www.netflix.com/title/$id?source=30")),
        ) else ParseResult.Ok(
            SharedLink(StreamingService.NETFLIX, SharedLink.Kind.TITLE, id, raw, listOf("https://www.netflix.com/title/$id?source=30")),
        )
    }

    // ---- JioHotstar: page paths end in a numeric content ID; on the MiTV-AXFR2 the TV app handled
    // https://www.hotstar.com/in links, but not the jiohotstar.com host, so links are rewritten to that host.
    private fun hotstar(segs: List<String>, raw: String): ParseResult {
        val id = segs.lastOrNull { DIGITS.matches(it) }
            ?: return ParseResult.AppOnly(StreamingService.JIOHOTSTAR, "This JioHotstar link doesn't point at a show, movie or episode.")
        val rest = if (segs.firstOrNull()?.length == 2) segs.drop(1) else segs // drop a country segment like "in"
        val path = rest.joinToString("/")
        val kind = when {
            rest.contains("movies") -> SharedLink.Kind.TITLE
            rest.count { DIGITS.matches(it) } >= 2 || rest.lastOrNull() == "watch" -> SharedLink.Kind.EPISODE
            else -> SharedLink.Kind.TITLE
        }
        val links = listOf("https://www.hotstar.com/in/$path", "hotstar://$path").distinct()
        return ParseResult.Ok(SharedLink(StreamingService.JIOHOTSTAR, kind, id, raw, links))
    }

    // ---- Prime Video: items are "gti" (amzn1.dv.gti.*) or ASIN-like IDs in /detail/<id>.
    private fun prime(segs: List<String>, q: Map<String, String>, raw: String): ParseResult {
        q["gti"]?.takeIf { it.startsWith("amzn1.dv.gti.") }?.let { gti ->
            return ParseResult.Ok(SharedLink(StreamingService.PRIME_VIDEO, SharedLink.Kind.TITLE, gti, raw,
                listOf("https://app.primevideo.com/detail?gti=$gti")))
        }
        val i = segs.indexOf("detail")
        val id = segs.getOrNull(i + 1)?.takeIf { i >= 0 && it.matches(Regex("^[A-Za-z0-9.]{8,}$")) }
            ?: return ParseResult.AppOnly(StreamingService.PRIME_VIDEO, "This Prime Video link doesn't point at a title.")
        return ParseResult.Ok(SharedLink(StreamingService.PRIME_VIDEO, SharedLink.Kind.TITLE, id, raw,
            listOf("https://www.primevideo.com/detail/$id", "https://app.primevideo.com/detail?asin=$id")))
    }

    private fun amazon(segs: List<String>, raw: String): ParseResult {
        val i = segs.indexOf("detail")
        val video = segs.contains("video")
        val id = segs.getOrNull(i + 1)?.takeIf { i >= 0 && it.matches(Regex("^[A-Z0-9]{10}$")) }
        return when {
            video && id != null -> ParseResult.Ok(SharedLink(StreamingService.PRIME_VIDEO, SharedLink.Kind.TITLE, id, raw,
                listOf("https://www.primevideo.com/detail/$id", "https://app.primevideo.com/detail?asin=$id")))
            video -> ParseResult.AppOnly(StreamingService.PRIME_VIDEO, "This Prime Video link doesn't point at a title.")
            else -> ParseResult.Unsupported("That's an Amazon shopping link, not a Prime Video title.")
        }
    }
}
