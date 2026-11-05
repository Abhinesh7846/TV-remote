package com.abhinesh.tvremote

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.abhinesh.tvremote.data.SavedTv
import com.abhinesh.tvremote.net.LinkPreview
import com.abhinesh.tvremote.ui.AppRoot
import com.abhinesh.tvremote.ui.TvRemoteTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the five main screens of the real app at a 1080x2400 / 440 dpi phone size
 * (392 x 872 dp) into app/screenshots. No TV is connected, so status shows "Not connected".
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w392dp-h872dp-440dpi")
class ScreenshotTest {
    private val app get() = ApplicationProvider.getApplicationContext<TvRemoteApp>()

    @Before fun setUp() {
        LinkPreview.enabled = false
        app.store.saveTv(SavedTv(name = "TV", host = "192.168.1.20", model = "MiTV-AXFR2"))
    }

    private fun shot(name: String, tab: Int, sendText: String? = null) =
        captureRoboImage("screenshots/$name.png") { TvRemoteTheme { AppRoot(app, initialTab = tab, sendText = sendText) } }

    @Test fun remote() = shot("1-remote", 0)
    @Test fun apps() = shot("2-apps", 1)
    @Test fun voice() = shot("3-voice", 2)
    @Test fun send() = shot("4-send", 3, "Check this out https://youtu.be/dQw4w9WgXcQ")
    @Test fun routines() = shot("5-routines", 4)
}
