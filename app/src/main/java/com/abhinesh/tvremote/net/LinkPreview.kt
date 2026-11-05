package com.abhinesh.tvremote.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Title and thumbnail for a YouTube video, from YouTube's public oEmbed endpoint and image host.
 * Fetched only when the user shares or pastes that link. Other services don't offer an open
 * preview, so the Send screen shows their mark instead.
 */
object LinkPreview {
    /** Turned off in screenshot tests, which have no network. */
    @Volatile var enabled = true

    data class Preview(val title: String?, val thumbnail: Bitmap?)

    suspend fun youtube(videoId: String): Preview = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext Preview(null, null)
        val watch = "https://www.youtube.com/watch?v=$videoId"
        val title = runCatching {
            JSONObject(get("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(watch, "UTF-8")).decodeToString()).optString("title").ifBlank { null }
        }.getOrNull()
        val thumb = runCatching { get("https://i.ytimg.com/vi/$videoId/hqdefault.jpg").let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
        Preview(title, thumb)
    }

    private fun get(url: String): ByteArray {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 5_000; readTimeout = 5_000 }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes().also { b -> require(b.size < 2_000_000) } }
        } finally {
            c.disconnect()
        }
    }
}
