package com.abhinesh.tvremote.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abhinesh.tvremote.TvRemoteApp

/** Receives links from Android's Share menu ("Open on TV") and shows the send panel. */
class ShareActivity : ComponentActivity() {
    private val app get() = application as TvRemoteApp
    private var shared by mutableStateOf<Pair<String?, String?>>(null to null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        shared = read(intent)
        setContent {
            TvRemoteTheme {
                Surface(color = Ui.bg, modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding()) {
                        val (text, title) = shared
                        SendScreen(app, text, title, onClose = { finish() }, onPairAnother = {
                            // Open the main app (reusing it if already open) straight on the pairing screen.
                            if (app.store.tv.value != null) app.repo.choosePairing()
                            startActivity(
                                Intent(this@ShareActivity, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            )
                            finish()
                        })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shared = read(intent)
    }

    private fun read(i: Intent?): Pair<String?, String?> =
        i?.getStringExtra(Intent.EXTRA_TEXT) to i?.getStringExtra(Intent.EXTRA_SUBJECT)

    override fun onStart() {
        super.onStart()
        app.repo.onForeground()
    }

    override fun onStop() {
        app.repo.onBackground()
        super.onStop()
    }
}
