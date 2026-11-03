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
private val tabs = listOf(Tab("Remote", Icons.Rounded.Tv), Tab("Apps", Icons.Rounded.Apps), Tab("Voice", Icons.Rounded.Mic), Tab("Settings", Icons.Rounded.Settings))

@Composable
fun AppRoot(app: TvRemoteApp) {
    val tv by app.store.tv.collectAsStateWithLifecycle()
    val pairing by app.repo.pairing.collectAsStateWithLifecycle()
    val settings by app.store.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity

    LaunchedEffect(settings.keepScreenOn) { activity?.keepScreenOn(settings.keepScreenOn) }
    LaunchedEffect(Unit) {
        app.repo.events.collect { msg ->
            snackbar.currentSnackbarData?.dismiss()
            launch { snackbar.showSnackbar(msg) }
        }
    }

    CompositionLocalProvider(LocalHaptics provides rememberHaptics(settings.haptics)) {
        val pairingMode = tv == null || pairing != PairingUi.Idle
        Scaffold(
            containerColor = Ui.bg,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!pairingMode) NavigationBar(containerColor = Ui.surface) {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(t.icon, null) }, label = { Text(t.title) })
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (pairingMode) PairingScreen(app)
                else when (tab) {
                    0 -> RemoteScreen(app)
                    1 -> AppsScreen(app)
                    2 -> VoiceScreen(app)
                    else -> SettingsScreen(app)
                }
            }
        }
    }
}
