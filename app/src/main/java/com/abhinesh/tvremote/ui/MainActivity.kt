package com.abhinesh.tvremote.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinesh.tvremote.PairingUi
import com.abhinesh.tvremote.TvRemoteApp
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val app get() = application as TvRemoteApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TvRemoteTheme { AppRoot(app) } }
    }

    override fun onStart() {
        super.onStart()
        app.repo.onForeground()
    }

    override fun onStop() {
        // Leaving the app: release held keys, stop the microphone and close the TV connection.
        app.phoneSpeech.cancel()
        app.tvVoice.cancel()
        app.repo.onBackground()
        super.onStop()
    }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}


private data class Tab(val title: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("Remote", Icons.Outlined.SettingsRemote), Tab("Apps", Icons.Outlined.GridView), Tab("Voice", Icons.Outlined.Mic),
    Tab("Send", Icons.Outlined.Link), Tab("Routines", Icons.Outlined.Layers),
)

@Composable
fun AppRoot(app: TvRemoteApp, initialTab: Int = 0, sendText: String? = null) {
    val tv by app.store.tv.collectAsStateWithLifecycle()
    val pairing by app.repo.pairing.collectAsStateWithLifecycle()
    val settings by app.store.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity

    LaunchedEffect(settings.keepScreenOn) { activity?.keepScreenOn(settings.keepScreenOn) }
    LaunchedEffect(Unit) {
        app.repo.events.collect { msg ->
            snackbar.currentSnackbarData?.dismiss()
            launch { snackbar.showSnackbar(msg) }
        }
    }
    BackHandler(enabled = showSettings) { showSettings = false }
    // Back while pairing another TV returns to the remote (only when a TV is already paired).
    BackHandler(enabled = tv != null && pairing != PairingUi.Idle) { app.repo.cancelPairing() }

    CompositionLocalProvider(LocalHaptics provides rememberHaptics(settings.haptics)) {
        VoiceEffects(app)
        val pairingMode = tv == null || pairing != PairingUi.Idle
        Scaffold(
            containerColor = Ui.bg,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { if (!pairingMode && !showSettings) BottomTabs(tab) { tab = it } },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    pairingMode -> PairingScreen(app)
                    showSettings -> Column {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
                            IconButton(onClick = { showSettings = false }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Ui.text) }
                        }
                        SettingsScreen(app)
                    }
                    else -> when (tab) {
                        0 -> RemoteScreen(app, onOpenSettings = { showSettings = true })
                        1 -> AppsScreen(app)
                        2 -> VoiceScreen(app)
                        3 -> SendScreen(app, sendText, onPairAnother = { app.repo.choosePairing() })
                        else -> RoutinesScreen(app)
                    }
                }
            }
        }
    }
}

/** Bottom tabs: the selected tab sits on a copper-tinted rounded tile, as in the design. */
@Composable
private fun BottomTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ui.bg).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        tabs.forEachIndexed { i, t ->
            val on = i == selected
            Column(
                Modifier.weight(1f).padding(horizontal = 3.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (on) Ui.accentDark.copy(alpha = 0.55f) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, t.title, tint = if (on) Ui.onAccent else Ui.textDim, modifier = Modifier.size(26.dp))
                Text(t.title, color = if (on) Ui.onAccent else Ui.textDim, style = MaterialTheme.typography.labelMedium, fontSize = 12.sp)
            }
        }
    }
}