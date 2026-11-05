package com.abhinesh.tvremote.core.share

import java.net.URLEncoder

/**
 * Search pages of each service's website. The TV app may accept them as app links and open
 * its own search for the query; this must be confirmed per TV (see README test results).
 */
object SearchLinks {
    fun forShortcut(shortcutId: String, query: String): List<String> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return when (shortcutId) {
            "youtube" -> listOf("https://www.youtube.com/results?search_query=$q")
            "netflix" -> listOf("https://www.netflix.com/search?q=$q")
            "jiohotstar" -> listOf("https://www.hotstar.com/in/explore?search_query=$q")
            "primevideo" -> listOf("https://www.primevideo.com/search/ref=atv_nb_sr?phrase=$q")
            else -> emptyList()
        }
    }
}
