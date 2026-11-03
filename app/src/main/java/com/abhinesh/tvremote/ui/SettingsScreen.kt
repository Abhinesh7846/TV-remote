package com.abhinesh.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinesh.tvremote.BuildConfig
import com.abhinesh.tvremote.TvRemoteApp
import com.abhinesh.tvremote.core.ConnectionState
import com.abhinesh.tvremote.core.proto.RemoteMessages.Feature
import com.abhinesh.tvremote.net.WakeOnLan
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: TvRemoteApp) {
    val store = app.store
    val repo = app.repo
    val tv by store.tv.collectAsStateWithLifecycle()
    val s by store.settings.collectAsStateWithLifecycle()
    val conn by repo.connection.collectAsStateWithLifecycle()
    val status by repo.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmForget by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var mac by remember(tv?.wolMac) { mutableStateOf(tv?.wolMac ?: "") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Section("TV") {
            tv?.let { t ->
                Info("Name", t.name)
                Info("Address", "${t.host} (remote port ${t.port}, pairing ${t.pairPort})")
                t.model?.let { Info("Model", it) }
                Info("Connection", when (val c = conn) {
                    is ConnectionState.Connected -> "Connected"
                    is ConnectionState.Connecting -> "Connecting (attempt ${c.attempt})"
                    is ConnectionState.Retrying -> "Retrying: ${friendlyReason(c)}"
                    is ConnectionState.PairingNeeded -> "Pairing needed"
                    is ConnectionState.IdentityChanged -> "TV identity changed: pair again"
                    ConnectionState.Idle -> "Not connected"
                })
                status?.let { st ->
                    Info("TV reports", listOfNotNull(st.vendor.ifEmpty { null }, st.model.ifEmpty { null }, st.isOn?.let { if (it) "screen on" else "standby" }).joinToString(" · "))
                    Info("Enabled features", Feature.describe(st.features))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { repo.reconnectNow() }) { Text("Reconnect") }
                    OutlinedButton(onClick = { repo.startPairing(t.host, t.name) }) { Text("Pair again") }
                    TextButton(onClick = { confirmForget = true }) { Text("Forget TV", color = Ui.danger) }
                }
            }
        }

        Section("Remote") {
            Toggle("Haptic feedback", "Vibrate lightly on each press.", s.haptics) { v -> store.updateSettings { it.copy(haptics = v) } }
            Toggle("Swipe pad instead of D-pad", "Swipes send arrow keys; tap sends OK.", s.swipePad) { v -> store.updateSettings { it.copy(swipePad = v) } }
            Toggle("Keep screen on", "While the remote is open.", s.keepScreenOn) { v -> store.updateSettings { it.copy(keepScreenOn = v) } }
        }

        Section("TV features (apply on reconnect)") {
            Toggle(
                "Keyboard and current-app detection", "Needed for typing and for confirming app launches. Turn off if your TV keeps showing 'Use keyboard on mobile device'.",
                s.imeEnabled,
            ) { v -> store.updateSettings { it.copy(imeEnabled = v) }; repo.reconnectNow() }
            Toggle("TV voice search", "Lets the TV accept microphone audio from this app.", s.tvVoiceEnabled) { v -> store.updateSettings { it.copy(tvVoiceEnabled = v) }; repo.reconnectNow() }
        }

        Section("Wake-on-LAN (experimental)") {
            Text(
                "Turn on only works while the TV is reachable. Your MiTV-AXFR2 switched its Wi-Fi off in standby during testing (no ping, no remote port), " +
                    "and Wake-on-LAN packets to both of its MAC addresses did not wake it. A TV switched off at the wall can never be woken over Wi-Fi. " +
                    "Leave this off unless your TV gains a network-standby option.",
                color = Ui.textDim, style = MaterialTheme.typography.bodySmall,
            )
            Toggle("Try Wake-on-LAN when the TV is off the network", "Used by \"Turn on\". Success is only shown if the TV really reconnects.", s.useWakeOnLan) { v ->
                store.updateSettings { it.copy(useWakeOnLan = v) }
            }
            OutlinedTextField(mac, { mac = it.uppercase().take(17) }, label = { Text("TV MAC address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("AA:BB:CC:DD:EE:FF") }, isError = mac.isNotEmpty() && !WakeOnLan.isValidMac(mac))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { store.updateTv { it.copy(wolMac = mac.ifBlank { null }) }; repo.notify("Saved.") }, enabled = mac.isEmpty() || WakeOnLan.isValidMac(mac)) { Text("Save") }
                OutlinedButton(onClick = {
                    scope.launch { repo.notify(WakeOnLan.send(mac, tv?.host).fold({ "Wake packet sent. No reply is expected; watch the TV." }, { it.message ?: "Couldn't send." })) }
                }, enabled = WakeOnLan.isValidMac(mac)) { Text("Send wake packet") }
            }
        }

        Section("Privacy and security") {
            Text(
                "No ads, analytics, accounts or cloud servers. The app only talks to your TV on the local network. Pairing keys stay on this phone, encrypted with a key held in the Android Keystore, and are excluded from backups. Phone commands use your phone's speech recogniser: on-device when available, otherwise your phone's speech service, which may send audio online.",
                color = Ui.textDim, style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { confirmReset = true }) { Text("Reset phone identity", color = Ui.danger) }
        }

        Section("About") {
            Text("TV Remote ${BuildConfig.VERSION_NAME}. Uses the Android TV Remote protocol v2. Protocol details follow tronikos/androidtvremote2 and Google's polo pairing protocol (both Apache-2.0); this app is an independent Kotlin implementation.", color = Ui.textDim, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this TV?") },
            text = { Text("The phone deletes its saved TV. You'll need to pair again with a new code. The TV keeps its own record until you clear the storage of 'Android TV Remote Service' on the TV.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; repo.forgetTv() }) { Text("Forget", color = Ui.danger) } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset phone identity?") },
            text = { Text("Creates a new pairing key for this phone. Every TV must be paired again.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; scope.launch { repo.resetIdentity() } }) { Text("Reset", color = Ui.danger) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ui.surface, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text(label, color = Ui.textDim, modifier = Modifier.padding(end = 12.dp))
        Text(value, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title)
            Text(detail, color = Ui.textDim, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
