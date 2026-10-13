package com.resonance.player

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Audius public, creator-uploaded music catalog.
 * The Audius API explicitly exposes free public track search and full-track streams.
 * Spotify audio is never downloaded, proxied, or accessed.
 * API docs: https://docs.audius.co/api/
 */
data class AudiusTrack(
    val id: String,
    val title: String,
    val artist: String,
    val genre: String,
    val duration: Int,
    val artwork: String = ""
) {
    val audioUrl: String
        get() = "https://api.audius.co/v1/tracks/${Uri.encode(id)}/stream?app_name=ResonanceAndroid"
    fun toJson(): String = JSONObject()
        .put("id", id).put("title", title).put("artist", artist)
        .put("genre", genre).put("duration", duration).put("artwork", artwork).toString()
    companion object {
        fun fromJson(raw: String): AudiusTrack {
            val json = JSONObject(raw)
            return AudiusTrack(json.getString("id"), json.optString("title"),
                json.optString("artist"), json.optString("genre"),
                json.optInt("duration"), json.optString("artwork"))
        }
    }
}

object AudiusCatalog {
    private val endpoints = listOf("https://api.audius.co/v1", "https://discoveryprovider.audius.co/v1")
    fun fetch(search: String = "", genre: String = ""): List<AudiusTrack> {
        val route: String
        val args = mutableListOf("app_name" to "ResonanceAndroid", "limit" to "40")
        if (search.isNotBlank()) {
            route = "/tracks/search"
            args += "query" to search
        } else {
            route = "/tracks/trending"
            if (genre.isNotBlank()) args += "genre" to genre
        }
        val query = args.joinToString("&") { "${Uri.encode(it.first)}=${Uri.encode(it.second)}" }
        var lastError: Exception? = null
        for (base in endpoints) {
            val connection = URL("$base$route?$query").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8000
                connection.readTimeout = 12000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "ResonanceAndroid/0.4")
                if (connection.responseCode !in 200..299) {
                    error("Audius HTTP ${connection.responseCode}")
                }
                val envelope = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val data = envelope.optJSONArray("data") ?: JSONArray()
                return (0 until data.length()).mapNotNull { index ->
                    val track = data.optJSONObject(index) ?: return@mapNotNull null
                    if (!track.optBoolean("is_streamable", true) ||
                        track.optBoolean("is_delete", false)) return@mapNotNull null
                    val id = track.optString("id")
                    if (id.isBlank()) return@mapNotNull null
                    val user = track.optJSONObject("user")
                    val artwork = track.optJSONObject("artwork")?.optString("480x480") ?: ""
                    AudiusTrack(
                        id = id,
                        title = track.optString("title").ifBlank { "Adsız parça" },
                        artist = user?.optString("name")?.ifBlank { user.optString("handle") }
                            ?: "Bilinmeyen sanatçı",
                        genre = track.optString("genre"),
                        duration = track.optInt("duration"),
                        artwork = artwork
                    )
                }.distinctBy { it.id }
            } catch (e: Exception) {
                lastError = e
            } finally { connection.disconnect() }
        }
        throw lastError ?: IllegalStateException("Audius bağlantısı kurulamadı")
    }
}
