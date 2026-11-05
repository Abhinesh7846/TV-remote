package com.abhinesh.tvremote.core.share

import com.abhinesh.tvremote.core.AppLauncher
import com.abhinesh.tvremote.core.AppShortcut
import com.abhinesh.tvremote.core.LaunchResult
import com.abhinesh.tvremote.core.RemoteSession

sealed interface SendResult {
    val message: String

    /** The TV accepted the link and brought the service's app to the front. The TV doesn't report which title is showing. */
    data class LinkOpenedApp(val service: StreamingService, val via: String) : SendResult {
        override val message get() = "Link sent. The TV opened ${service.label}; check that it shows the right title."
    }
    /** The TV took the link but didn't report the app (it may not report the foreground app). */
    data class LinkSent(val via: String) : SendResult {
        override val message get() = "Link sent. The TV didn't confirm what opened; check the TV."
    }
    data class ChooserShown(val service: StreamingService) : SendResult {
        override val message get() = "The TV is asking which app should open this. Pick ${service.label} on the TV (choose \"Always\" to skip this next time)."
    }
    /** The TV refused every link form for this item; opening the app is still possible. */
    data class Unsupported(val service: StreamingService, val detail: String) : SendResult {
        override val message get() = "The TV's ${service.label} app didn't accept this link. You can open ${service.label} and search for the title instead."
    }
    data class AppNotOpened(val service: StreamingService, val detail: String) : SendResult {
        override val message get() = "The TV accepted the link but didn't switch to ${service.label}. $detail"
    }
    data object Offline : SendResult {
        override val message get() = "The TV isn't connected. Turn it on with its remote, wait for \"Connected\", then try again."
    }
}

/** Sends a parsed link to the paired TV using the same launch-and-confirm path as the app shortcuts. */
object SendToTv {
    suspend fun send(link: SharedLink, confirmTimeoutMs: Long = 8_000, session: suspend () -> RemoteSession?): SendResult {
        val s = session()
        if (s == null || !s.isOpen) return SendResult.Offline
        val probe = AppShortcut(link.service.shortcutId, link.service.label, link.service.packageName, link.tvLinks, 0)
        return when (val r = AppLauncher.launch(probe, confirmTimeoutMs, session)) {
            is LaunchResult.Confirmed -> SendResult.LinkOpenedApp(link.service, r.via)
            is LaunchResult.AlreadyOpen -> SendResult.LinkOpenedApp(link.service, link.tvLinks.first())
            is LaunchResult.SentUnverified -> SendResult.LinkSent(r.via)
            is LaunchResult.ChooserShown -> SendResult.ChooserShown(link.service)
            is LaunchResult.Rejected -> SendResult.Unsupported(link.service, r.message)
            is LaunchResult.PlayStoreOpened -> SendResult.AppNotOpened(link.service, r.message)
            is LaunchResult.NotConfirmed -> SendResult.AppNotOpened(link.service, r.message)
            is LaunchResult.Unsupported -> SendResult.Unsupported(link.service, r.message)
            LaunchResult.NotConnected -> SendResult.Offline
        }
    }
}
