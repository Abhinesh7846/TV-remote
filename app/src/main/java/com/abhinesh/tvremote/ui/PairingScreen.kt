package com.abhinesh.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinesh.tvremote.PairingUi
import com.abhinesh.tvremote.TvRemoteApp
import com.abhinesh.tvremote.net.FoundTv
import kotlinx.coroutines.launch

@Composable
fun PairingScreen(app: TvRemoteApp) {
    val repo = app.repo
    val pairing by repo.pairing.collectAsStateWithLifecycle()
    val savedTv by app.store.tv.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tv, null, Modifier.size(32.dp), tint = Ui.accent)
            Spacer(Modifier.width(10.dp))
            Text("Connect your TV", style = MaterialTheme.typography.headlineSmall)
        }
        when (val p = pairing) {
            is PairingUi.Connecting -> {
                Text("Contacting the TV at ${p.host}…", color = Ui.textDim)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { repo.cancelPairing() }) { Text("Cancel") }
            }
            is PairingUi.EnterCode -> CodeEntry(p, onSubmit = repo::submitCode, onCancel = repo::cancelPairing)
            is PairingUi.Failed -> {
                Text(p.message, color = Ui.danger)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { repo.startPairing(p.host, p.name) }) { Text("Try again") }
                    OutlinedButton(onClick = { repo.cancelPairing() }) { Text("Back") }
                }
            }
            PairingUi.Idle -> FindTv(app, onPick = { repo.startPairing(it.host, it.name) })
        }
        if (savedTv != null && pairing is PairingUi.Idle) {
            TextButton(onClick = { repo.cancelPairing() }) { Text("Back to the remote") }
        }
    }
}

@Composable
private fun CodeEntry(p: PairingUi.EnterCode, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    Text("A 6-character code is now on your TV (${p.name}). Enter it here.", style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        value = code,
        onValueChange = { v ->
            code = v.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
            if (code.length == 6 && !p.busy) onSubmit(code)
        },
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp, textAlign = TextAlign.Center),
        singleLine = true,
        enabled = !p.busy,
        label = { Text("Code from the TV") },
        supportingText = { Text("Digits 0–9 and letters A–F") },
        isError = p.error != null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (code.length == 6) onSubmit(code) }),
    )
    p.error?.let { Text(it, color = Ui.danger) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { onSubmit(code) }, enabled = code.length == 6 && !p.busy) { Text("Pair") }
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
        if (p.busy) CircularProgressIndicator(Modifier.size(24.dp))
    }
    Text("If the code disappeared from the TV, cancel and start again for a new one.", color = Ui.textDim, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun FindTv(app: TvRemoteApp, onPick: (FoundTv) -> Unit) {
    val repo = app.repo
    val scope = rememberCoroutineScope()
    val found by remember { repo.discovery.discover() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var scanned by remember { mutableStateOf<List<FoundTv>>(emptyList()) }
    var scanProgress by remember { mutableStateOf<Int?>(null) }
    var manualIp by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf(repo.discovery.networkProblem()) }
    LaunchedEffect(Unit) { problem = repo.discovery.networkProblem() }

    problem?.let { Text(it, color = Ui.warning) }
    Text("Turn the TV on and keep the phone on the same Wi-Fi. Pick your TV:", color = Ui.textDim)
    val all = (found + scanned).distinctBy { it.host }
    if (all.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Searching the network…", color = Ui.textDim)
        }
    }
    all.forEach { tv ->
        Row(
            Modifier.fillMaxWidth().background(Ui.key, RoundedCornerShape(16.dp)).clickable { onPick(tv) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Tv, null, tint = Ui.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tv.name, fontWeight = FontWeight.Medium)
                Text("${tv.host} · found by ${tv.via}", color = Ui.textDim, style = MaterialTheme.typography.bodySmall)
            }
            Text("Pair", color = Ui.accent, fontWeight = FontWeight.Medium)
        }
    }
    OutlinedButton(
        onClick = {
            scanProgress = 0
            scope.launch {
                scanned = repo.discovery.scanSubnet(app.identityStore.get()) { done -> scanProgress = done }
                scanProgress = null
                if (scanned.isEmpty()) repo.notify("No TV answered on this network. Check that the TV is on, or enter its IP address.")
            }
        },
        enabled = scanProgress == null,
    ) { Text(scanProgress?.let { "Scanning… ${it * 100 / 254}%" } ?: "Not listed? Scan the network") }

    Spacer(Modifier.height(4.dp))
    Text("Or enter the TV's IP address", style = MaterialTheme.typography.titleSmall)
    Text("On the TV: Settings › Network & Internet › your Wi-Fi network shows the IP address.", color = Ui.textDim, style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = manualIp, onValueChange = { manualIp = it.filter { c -> c.isDigit() || c == '.' }.take(15) },
            modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("192.168.1.20") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (isIpv4(manualIp)) onPick(FoundTv("Android TV", manualIp, 6466, null, "manual")) }),
        )
        Button(onClick = { onPick(FoundTv("Android TV", manualIp, 6466, null, "manual")) }, enabled = isIpv4(manualIp)) { Text("Pair") }
    }
}

private fun isIpv4(s: String) = s.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true } }
