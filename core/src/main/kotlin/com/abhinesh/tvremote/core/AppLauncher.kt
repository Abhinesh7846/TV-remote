package com.abhinesh.tvremote.core

import com.abhinesh.tvremote.core.proto.RemoteMessages.Feature
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A TV app shortcut. [targets] are tried in order; each is either an app link / deep link
 * (anything with a URI scheme, e.g. "netflix://" or "https://www.youtube.com") or a bare
 * package name, which is sent as "market://launch?id=<package>" (the androidtvremote2 /
 * Home Assistant convention). [packageName] is only used to confirm the launch.
 */
data class AppShortcut(
    val id: String,
    val label: String,
    val packageName: String,
    val targets: List<String>,
    val colorArgb: Long,
    val onColorArgb: Long = 0xFFFFFFFF,
    val aliases: List<String> = emptyList(),
) {
    companion object {
        /** Play Store page of a TV app: opens on this TV (tested), with Install or Open. */
        fun store(pkg: String) = "market://details?id=$pkg"

        /**
         * Packages from the Google Play listings. Launch links were tested on the user's Xiaomi
         * MiTV-AXFR2 (Android TV 11, Google TV launcher):
         *  - 2026-11-03: netflix://, hotstar://, https://www.hotstar.com/in, https://app.primevideo.com
         *    opened the app; https://www.youtube.com reached YouTube via the "Open with" chooser;
         *  - 2026-11-04: sonyliv://, https://www.zee5.com, aha://, spotify://, https://tv.apple.com
         *    opened the app; zee5://, etvwin://, sunnxt:// were refused; market://details?id=<pkg>
         *    opened the Play Store page; market://launch, android-app:// and intent: links were refused.
         *  - ETV Win and Sun NXT weren't installed on that TV, so only their Play Store pages were seen.
         * Every shortcut ends with its Play Store page, so a missing app can be installed from there.
         */
        val DEFAULTS = listOf(
            AppShortcut(
                id = "netflix", label = "Netflix", packageName = "com.netflix.ninja",
                targets = listOf("netflix://", store("com.netflix.ninja")),
                colorArgb = 0xFFE50914, aliases = listOf("netflix", "నెట్‌ఫ్లిక్స్", "నెట్ఫ్లిక్స్", "నెట్ ఫ్లిక్స్", "नेटफ्लिक्स"),
            ),
            AppShortcut(
                id = "jiohotstar", label = "JioHotstar", packageName = "in.startv.hotstar",
                targets = listOf("hotstar://", "https://www.hotstar.com/in", store("in.startv.hotstar")),
                colorArgb = 0xFF1B2A8A, aliases = listOf("jiohotstar", "jio hotstar", "hotstar", "hot star", "హాట్‌స్టార్", "హాట్ స్టార్", "జియో హాట్‌స్టార్", "జియో హాట్ స్టార్", "हॉटस्टार", "जियो हॉटस्टार"),
            ),
            AppShortcut(
                id = "youtube", label = "YouTube", packageName = "com.google.android.youtube.tv",
                targets = listOf("https://www.youtube.com"),
                colorArgb = 0xFFCC0000, aliases = listOf("youtube", "you tube", "యూట్యూబ్", "యూ ట్యూబ్", "यूट्यूब"),
            ),
            AppShortcut(
                id = "primevideo", label = "Prime Video", packageName = "com.amazon.amazonvideo.livingroom",
                targets = listOf("https://app.primevideo.com", store("com.amazon.amazonvideo.livingroom")),
                colorArgb = 0xFF00A8E1, onColorArgb = 0xFF00131C,
                aliases = listOf("prime video", "prime", "amazon prime", "amazon", "ప్రైమ్ వీడియో", "ప్రైమ్", "అమెజాన్ ప్రైమ్", "प्राइम वीडियो", "अमेज़न प्राइम", "प्राइम"),
            ),
            AppShortcut(
                id = "sonyliv", label = "Sony LIV", packageName = "com.sonyliv",
                targets = listOf("sonyliv://", store("com.sonyliv")),
                colorArgb = 0xFF1A1A1A, aliases = listOf("sonyliv", "sony liv", "sony live", "sony", "సోనీ లివ్", "సోనీ లైవ్", "సోనీ", "सोनी लिव", "सोनी लाइव", "सोनी"),
            ),
            AppShortcut(
                id = "zee5", label = "ZEE5", packageName = "com.graymatrix.did",
                targets = listOf("https://www.zee5.com", store("com.graymatrix.did")),
                colorArgb = 0xFF8230C6, aliases = listOf("zee5", "zee 5", "zee five", "z5", "zee", "జీ5", "జీ 5", "జీ ఫైవ్","ज़ी5", "जी5", "ज़ी फाइव", "जी फाइव"),
            ),
            AppShortcut(
                id = "aha", label = "aha", packageName = "ahaflix.tv",
                targets = listOf("aha://", store("ahaflix.tv")),
                colorArgb = 0xFFFF6A13, aliases = listOf("aha", "aaha", "aha video", "ఆహా", "आहा"),
            ),
            AppShortcut(
                id = "etvwin", label = "ETV Win", packageName = "com.etvwin.androidtv",
                targets = listOf("https://www.etvwin.com", store("com.etvwin.androidtv")),
                colorArgb = 0xFFF5B400, aliases = listOf("etv win", "etvwin", "etv", "e tv win", "ఈటీవీ విన్", "ఈటీవీ", "ఈ టీవీ", "ईटीवी विन", "ईटीवी"),
            ),
            AppShortcut(
                id = "sunnxt", label = "Sun NXT", packageName = "com.suntv.sunnxt",
                targets = listOf("https://www.sunnxt.com", store("com.suntv.sunnxt")),
                colorArgb = 0xFFFF8A00, aliases = listOf("sun nxt", "sun next", "sunnxt", "sun", "సన్ నెక్స్ట్", "సన్ ఎన్ఎక్స్‌టి", "सन नेक्स्ट"),
            ),
            AppShortcut(
                id = "spotify", label = "Spotify", packageName = "com.spotify.tv.android",
                targets = listOf("spotify://", store("com.spotify.tv.android")),
                colorArgb = 0xFF1DB954, onColorArgb = 0xFF04210F, aliases = listOf("spotify", "spotify music", "స్పాటిఫై", "स्पॉटिफाई"),
            ),
            AppShortcut(
                id = "appletv", label = "Apple TV", packageName = "com.apple.atve.androidtv.appletv",
                targets = listOf("https://tv.apple.com", store("com.apple.atve.androidtv.appletv")),
                colorArgb = 0xFF2B2B2B, aliases = listOf("apple tv", "apple", "యాపిల్ టీవీ", "ఆపిల్ టీవీ", "एप्पल टीवी", "ऐप्पल टीवी"),
            ),
        )

        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        fun isPackageTarget(target: String) = !SCHEME.containsMatchIn(target.trim())

        /** A bare package name opens its Play Store page (market://launch was refused by the MiTV-AXFR2). */
        fun toAppLink(target: String): String =
            if (isPackageTarget(target)) store(target.trim()) else target.trim()
    }
}

sealed interface LaunchResult {
    val message: String
    val success: Boolean get() = this is Confirmed || this is AlreadyOpen

    data class Confirmed(val packageName: String, val via: String) : LaunchResult {
        override val message get() = "Opened on the TV."
    }
    data class AlreadyOpen(val packageName: String) : LaunchResult {
        override val message get() = "Already open on the TV."
    }
    data class ChooserShown(val label: String) : LaunchResult {
        override val message get() = "The TV is asking which app should open this. Pick $label on the TV (choose \"Always\" to skip this next time)."
    }
    data class PlayStoreOpened(val label: String) : LaunchResult {
        override val message get() = "$label didn't open directly, so the TV is showing its Play Store page. Press Install there, or Open if it's already installed."
    }
    data class Rejected(val label: String, val links: List<String>) : LaunchResult {
        override val message get() = "The TV refused the launch link for $label (${links.joinToString()}). Edit the shortcut to try a different link."
    }
    data class NotConfirmed(val label: String, val lastSeenApp: String?) : LaunchResult {
        override val message get() = if (lastSeenApp != null)
            "The TV accepted the request but showed $lastSeenApp, not $label. It may not be installed, or this link isn't supported."
        else "The TV accepted the request but didn't switch to $label. It may not be installed, or this link isn't supported."
    }
    data class SentUnverified(val via: String) : LaunchResult {
        override val message get() = "Launch request sent. This TV isn't reporting the open app, so it can't be confirmed. Check the TV."
    }
    data class Unsupported(override val message: String) : LaunchResult
    data object NotConnected : LaunchResult {
        override val message get() = "Not connected to the TV."
    }
}

/** Launches an app and confirms it by watching the foreground app the TV reports. */
object AppLauncher {
    const val PLAY_STORE = "com.android.vending"
    /** Android's "Open with" chooser (ResolverActivity) runs in the "android" system package. */
    const val CHOOSER = "android"

    /**
     * @param session returns the current connected session, waiting briefly if a reconnect is
     *        in progress (the TV drops the connection after rejecting a link).
     */
    suspend fun launch(
        shortcut: AppShortcut,
        confirmTimeoutMs: Long = 7_000,
        session: suspend () -> RemoteSession?,
    ): LaunchResult {
        if (shortcut.targets.isEmpty()) return LaunchResult.Unsupported("This shortcut has no launch link.")
        val expected = shortcut.packageName.trim()
        val rejected = ArrayList<String>()
        var lastOther: String? = null
        var refusedBy: RemoteSession? = null
        for (target in shortcut.targets) {
            val link = AppShortcut.toAppLink(target)
            // After a refused link the TV drops that connection, so wait for a different, open one.
            val s = withTimeoutOrNull(if (refusedBy == null) 3_000 else 10_000) {
                while (true) {
                    val cur = session()
                    if (cur != null && cur.isOpen && cur !== refusedBy && cur.launchAppLink(link)) return@withTimeoutOrNull cur
                    delay(150)
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            if (s == null) return if (rejected.isNotEmpty()) LaunchResult.Rejected(shortcut.label, rejected) else LaunchResult.NotConnected
            val status = s.status.value
            if (!status.has(Feature.APP_LINK)) return LaunchResult.Unsupported("This TV didn't enable app launching for remote apps.")
            val before = status.currentApp
            // Confirmation relies on the TV's foreground-app reports (sent with the keyboard/IME feature).
            val canConfirm = before != null && expected.isNotEmpty()
            // If the app is already in front, its presence proves nothing: wait briefly for a refusal instead.
            val alreadyOpen = canConfirm && before == expected
            val seen = withTimeoutOrNull(if (alreadyOpen) minOf(confirmTimeoutMs, 2_500) else if (canConfirm) confirmTimeoutMs else 2_000) {
                s.status.first {
                    it.rejectedAppLink == link || (it.currentApp == expected && !alreadyOpen) ||
                        (it.currentApp != before && (it.currentApp == PLAY_STORE || it.currentApp == CHOOSER))
                }
            }
            when {
                seen?.rejectedAppLink == link -> { rejected += link; refusedBy = s; continue }
                seen == null && alreadyOpen && s.isOpen -> return LaunchResult.AlreadyOpen(expected)
                seen == null && !canConfirm -> return LaunchResult.SentUnverified(link)
                seen?.currentApp == expected -> return LaunchResult.Confirmed(expected, link)
                seen?.currentApp == PLAY_STORE -> return LaunchResult.PlayStoreOpened(shortcut.label)
                seen?.currentApp == CHOOSER -> return LaunchResult.ChooserShown(shortcut.label)
            }
            val now = s.status.value.currentApp
            if (now != before) lastOther = now
        }
        return if (rejected.size == shortcut.targets.size) LaunchResult.Rejected(shortcut.label, rejected)
        else LaunchResult.NotConfirmed(shortcut.label, lastOther)
    }
}
