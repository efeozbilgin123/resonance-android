package com.resonance.player

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube metadata and link reference only. Playback stays inside the official
 * embeddable YouTube player; we do not extract, proxy, cache or download audio.
 */
data class YouTubeEntry(
    val videoId: String,
    val title: String,
    val channel: String
) {
    val canonicalUrl: String get() = "https://www.youtube.com/watch?v=$videoId"
    val thumbnail: String get() = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    fun asExternal(): ExternalPlaylistItem =
        ExternalPlaylistItem("youtube", videoId, title, channel, canonicalUrl, thumbnail)
}

object YouTubeLinks {
    private val VIDEO = Regex("^[a-zA-Z0-9_-]{11}$")
    private val SAFE_HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com",
        "music.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com",
        "youtu.be", "www.youtu.be"
    )

    fun parse(raw: String): String? {
        val text = raw.trim()
        if (VIDEO.matches(text)) return text
        val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in SAFE_HOSTS) return null
        val segments = uri.pathSegments
        val candidate = when {
            host.endsWith("youtu.be") -> segments.firstOrNull()
            segments.firstOrNull() == "watch" -> uri.getQueryParameter("v")
            segments.firstOrNull() in setOf("shorts", "live", "embed", "v") -> segments.getOrNull(1)
            else -> null
        } ?: return null
        return candidate.takeIf { VIDEO.matches(it) }
    }

    fun canonical(raw: String): String? =
        parse(raw)?.let { "https://www.youtube.com/watch?v=$it" }
}

object YouTubeInfo {
    /**
     * Official public oEmbed endpoint; lookup can fail for private or restricted
     * videos, in which case the user supplies the title manually.
     * Do not make this request on the UI thread.
     */
    fun resolve(videoId: String): YouTubeEntry {
        require(YouTubeLinks.parse(videoId) != null) { "Geçersiz YouTube video kimliği" }
        val canonical = "https://www.youtube.com/watch?v=$videoId"
        val endpoint = "https://www.youtube.com/oembed?format=json&url=" +
            Uri.encode(canonical)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ResonanceAndroid/0.6")
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Video bilgileri alınamadı (HTTP ${connection.responseCode})")
            }
            val info = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return YouTubeEntry(videoId,
                info.optString("title").ifBlank { "YouTube videosu" },
                info.optString("author_name").ifBlank { "YouTube" })
        } finally {
            connection.disconnect()
        }
    }
}
