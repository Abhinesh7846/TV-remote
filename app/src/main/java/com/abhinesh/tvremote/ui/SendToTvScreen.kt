package com.abhinesh.tvremote.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinesh.tvremote.TvRemoteApp
import com.abhinesh.tvremote.core.AppShortcut
import com.abhinesh.tvremote.core.ConnectionState
import com.abhinesh.tvremote.core.share.LinkParser
import com.abhinesh.tvremote.core.share.ParseResult
import com.abhinesh.tvremote.core.share.SendResult
import com.abhinesh.tvremote.core.share.SharedLink
import com.abhinesh.tvremote.core.share.StreamingService
import com.abhinesh.tvremote.net.LinkPreview
import com.abhinesh.tvremote.net.ShortLinkResolver
import kotlinx.coroutines.launch

private sealed interface Prepared {
    data object Empty : Prepared
    data object Resolving : Prepared
    data class Ready(val result: ParseResult) : Prepared
}

/**
 * Send tab and Share target: preview of the shared or pasted link, the paired TV, and
 * "Open on TV". Nothing is sent until the user taps.
 */
@Composable
fun SendScreen(app: TvRemoteApp, initialText: String?, sharedTitle: String? = null, onClose: (() -> Unit)? = null, onPairAnother: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember(initialText) { mutableStateOf(initialText.orEmpty()) }
    var prepared by remember { mutableStateOf<Prepared>(Prepared.Empty) }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SendResult?>(null) }
    var preview by remember { mutableStateOf<LinkPreview.Preview?>(null) }

    suspend fun prepare(input: String) {
        result = null; preview = null
        if (input.isBlank()) { prepared = Prepared.Empty; return }
        var r = LinkParser.parse(input)
        if (r is ParseResult.NeedsResolve) {
            prepared = Prepared.Resolving
            val full = ShortLinkResolver.resolve(r.url)
            r = if (full == null) ParseResult.Unsupported("Couldn't open that short link to find the real page. Check the phone's internet, or share the full link.")
            else LinkParser.parse(full).let { if (it is ParseResult.NeedsResolve) ParseResult.Unsupported("That short link didn't lead to a supported page.") else it }
        }
        prepared = Prepared.Ready(r)
        val link = (r as? ParseResult.Ok)?.link
        if (link?.service == StreamingService.YOUTUBE && link.kind == SharedLink.Kind.VIDEO) preview = LinkPreview.youtube(link.id)
    }
    LaunchedEffect(initialText) { prepare(initialText.orEmpty()) }

    val ready = (prepared as? Prepared.Ready)?.result
    val link = (ready as? ParseResult.Ok)?.link
    val serviceOnly = (ready as? ParseResult.AppOnly)?.service

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Send to TV", "Open a link on your TV")

        // ---- preview
        GCard(padding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 138.dp, height = 100.dp).clip(RoundedCornerShape(14.dp)).background(Ui.key), contentAlignment = Alignment.Center) {
                    val thumb = preview?.thumbnail
                    val svc = link?.service ?: serviceOnly
                    when {
                        thumb != null -> Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        svc != null -> ServiceMark(shortcutFor(app, svc), 46.dp)
                        prepared is Prepared.Resolving -> CircularProgressIndicator(Modifier.size(26.dp), color = Ui.accent, strokeWidth = 2.dp)
                        else -> Icon(Icons.Rounded.OndemandVideo, null, tint = Ui.textMuted, modifier = Modifier.size(40.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val svc = link?.service ?: serviceOnly
                    val title = sharedTitle?.takeIf { it.isNotBlank() } ?: preview?.title ?: when {
                        link != null -> "${link.service.label} ${link.kind.label}"
                        serviceOnly != null -> serviceOnly.label
                        prepared is Prepared.Resolving -> "Opening short link…"
                        ready is ParseResult.Unsupported -> "Link not supported"
                        else -> "No link yet"
                    }
                    ScriptText(title, style = MaterialTheme.typography.titleLarge, fontSize = 19.sp, maxLines = 3)
                    if (svc != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { ServiceMark(shortcutFor(app, svc), 16.dp) }
                        Spacer(Modifier.width(6.dp))
                        Text(svc.label, color = Ui.textDim, style = MaterialTheme.typography.bodyLarge)
                    } else if (ready is ParseResult.Unsupported) {
                        Text(ready.reason, color = Ui.danger, style = MaterialTheme.typography.bodySmall)
                    } else if (prepared is Prepared.Empty) {
                        Text("Share from YouTube, Netflix, JioHotstar or Prime Video, or paste a link.", color = Ui.textDim, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (serviceOnly != null) Text("${(ready as ParseResult.AppOnly).reason} You can open ${serviceOnly.label} and search on the TV.", color = Ui.textDim, style = MaterialTheme.typography.bodySmall)
        }

        TvSelector(app, onPairAnother)

        CopperButton(
            if (serviceOnly != null) "Open ${serviceOnly.label} on TV" else "Open on TV",
            onClick = {
                when {
                    link != null -> { sending = true; result = null; scope.launch { try { result = app.repo.sendLink(link) } finally { sending = false } } }
                    serviceOnly != null -> openApp(app, serviceOnly)
                }
            },
            icon = Icons.Outlined.Tv, enabled = !sending && app.store.tv.value != null && (link != null || serviceOnly != null),
        )
        OutlineCopperButton("Paste link", {
            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            if (pasted.isBlank()) app.repo.notify("The clipboard is empty. Copy a link first (in WhatsApp: long-press the message › Copy).")
            else { text = pasted; scope.launch { prepare(pasted) } }
        }, icon = Icons.Rounded.Link)

        if (sending) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Ui.accent, strokeWidth = 2.dp)
            Text("  Sending and waiting for the TV…", color = Ui.textDim)
        }
        result?.let { r ->
            val color = when (r) {
                is SendResult.LinkOpenedApp -> Ui.success
                is SendResult.LinkSent, is SendResult.ChooserShown -> Ui.warning
                else -> Ui.danger
            }
            GCard(padding = 14.dp) {
                Text(r.message, color = color, style = MaterialTheme.typography.bodyLarge)
                if (r is SendResult.Unsupported || r is SendResult.AppNotOpened) link?.let { l ->
                    TextButton(onClick = { openApp(app, l.service) }) { Text("Open ${l.service.label} instead", color = Ui.accentText) }
                }
                Text("If the service asks you to sign in, choose a profile or subscribe, do that on the TV.", color = Ui.textMuted, style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("YouTube · Netflix · JioHotstar · Prime Video", color = Ui.textDim, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally))
        onClose?.let { TextButton(onClick = it, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Close", color = Ui.accentText) } }
    }
}

/** Paired TV with live status. The app pairs one TV at a time, so the menu offers re-pairing rather than a list. */
@Composable
private fun TvSelector(app: TvRemoteApp, onPairAnother: () -> Unit) {
    val tv by app.store.tv.collectAsStateWithLifecycle()
    val conn by app.repo.connection.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val (label, color) = when (conn) {
        is ConnectionState.Connected -> "Connected" to Ui.success
        is ConnectionState.Connecting, is ConnectionState.Retrying -> "Not connected (TV off?)" to Ui.warning
        else -> "Not connected" to Ui.danger
    }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Ui.surface).border(1.dp, Ui.border, RoundedCornerShape(22.dp))
                .clickable { open = true }.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Tv, null, tint = Ui.text, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(tv?.name ?: "No TV paired", style = MaterialTheme.typography.titleLarge, fontSize = 19.sp)
                StatusDot(if (tv == null) "Pair a TV first" else label, if (tv == null) Ui.warning else color)
            }
            Icon(Icons.Rounded.KeyboardArrowDown, "Choose TV", tint = Ui.text)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            tv?.let { t ->
                DropdownMenuItem(text = { Text(t.name, fontWeight = FontWeight.SemiBold) }, leadingIcon = { Icon(Icons.Rounded.Check, null) }, onClick = { open = false })
            }
            DropdownMenuItem(text = { Text(if (tv == null) "Pair a TV" else "Pair a different TV (replaces this one)") }, onClick = { open = false; onPairAnother() })
        }
    }
}

private fun shortcutFor(app: TvRemoteApp, s: StreamingService): AppShortcut =
    app.store.shortcuts.value.firstOrNull { it.id == s.shortcutId } ?: AppShortcut.DEFAULTS.first { it.id == s.shortcutId }

private fun openApp(app: TvRemoteApp, service: StreamingService) = app.repo.launch(shortcutFor(app, service))
