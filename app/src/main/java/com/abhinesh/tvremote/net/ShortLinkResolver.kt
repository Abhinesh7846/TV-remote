package com.abhinesh.tvremote.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Follows HTTP redirects of a short link (a.co, page.link, …) to find the real page, without
 * cookies or downloading page bodies. Only runs when the user sends a short link.
 */
object ShortLinkResolver {
    suspend fun resolve(start: String, maxHops: Int = 6): String? = withContext(Dispatchers.IO) {
        var url = start
        repeat(maxHops) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 6_000
                readTimeout = 6_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TVRemote")
            }
            try {
                val code = conn.responseCode
                val next = conn.getHeaderField("Location")
                if (code in 300..399 && next != null) {
                    url = URL(URL(url), next).toString()
                    if (!url.startsWith("https://") && !url.startsWith("http://")) return@withContext url
                } else {
                    return@withContext url.takeIf { it != start }
                }
            } catch (e: Exception) {
                return@withContext null
            } finally {
                conn.disconnect()
            }
        }
        url
    }
}
