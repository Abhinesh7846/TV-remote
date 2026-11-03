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
        /**
         * Packages from the Google Play listings (checked 2026-11-03). Launch links were tested on
         * a Xiaomi MiTV-AXFR2 (Android TV 11, Google TV launcher) on 2026-11-03:
         *  - netflix://, hotstar://, https://www.hotstar.com/in, https://app.primevideo.com opened the app;
         *  - https://www.youtube.com reached YouTube via the TV's "Open with" chooser;
         *  - market://launch?id=<package> was rejected by that TV, so it is not used by default.
         */
        val DEFAULTS = listOf(
            AppShortcut(
                id = "netflix", label = "Netflix", packageName = "com.netflix.ninja",
                targets = listOf("netflix://"),
                colorArgb = 0xFFE50914, aliases = listOf("netflix", "నెట్‌ఫ్లిక్స్", "నెట్ఫ్లిక్స్", "నెట్ ఫ్లిక్స్"),
            ),
            AppShortcut(
                id = "jiohotstar", label = "JioHotstar", packageName = "in.startv.hotstar",
                targets = listOf("hotstar://", "https://www.hotstar.com/in"),
                colorArgb = 0xFF1B2A8A, aliases = listOf("jiohotstar", "jio hotstar", "hotstar", "hot star", "హాట్‌స్టార్", "హాట్ స్టార్", "జియో హాట్‌స్టార్", "జియో హాట్ స్టార్"),
            ),
            AppShortcut(
                id = "youtube", label = "YouTube", packageName = "com.google.android.youtube.tv",
                targets = listOf("https://www.youtube.com"),
                colorArgb = 0xFFCC0000, aliases = listOf("youtube", "you tube", "యూట్యూబ్", "యూ ట్యూబ్"),
            ),
            AppShortcut(
                id = "primevideo", label = "Prime Video", packageName = "com.amazon.amazonvideo.livingroom",
                targets = listOf("https://app.primevideo.com"),
                colorArgb = 0xFF00A8E1, onColorArgb = 0xFF00131C,
                aliases = listOf("prime video", "prime", "amazon prime", "amazon", "ప్రైమ్ వీడియో", "ప్రైమ్", "అమెజాన్ ప్రైమ్"),
            ),
        )

        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        fun isPackageTarget(target: String) = !SCHEME.containsMatchIn(target.trim())

        fun toAppLink(target: String): String =
            if (isPackageTarget(target)) "market://launch?id=${target.trim()}" else target.trim()
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
        override val message get() = "The TV opened the Play Store instead, so $label may not be installed on this TV."
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
            val seen = withTimeoutOrNull(if (canConfirm) confirmTimeoutMs else 2_000) {
                s.status.first {
                    it.rejectedAppLink == link || it.currentApp == expected ||
                        (it.currentApp != before && (it.currentApp == PLAY_STORE || it.currentApp == CHOOSER))
                }
            }
            when {
                seen == null && !canConfirm -> return LaunchResult.SentUnverified(link)
                seen?.rejectedAppLink == link -> { rejected += link; refusedBy = s; continue }
                seen?.currentApp == expected && before == expected -> return LaunchResult.AlreadyOpen(expected)
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
