package com.resonance.player

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * An on-device sidecar for non-Spotify music.
 *
 * Spotify does not support inserting arbitrary external tracks via the Web API.
 * These entries are NEVER sent to Spotify or misrepresented as Spotify tracks.
 * Data is isolated by Spotify account_id and the Spotify playlist ID.
 */
data class ExternalPlaylistItem(
    val source: String,
    val id: String,
    val title: String,
    val artist: String,
    val playbackUri: String,
    val artwork: String = ""
) {
    init {
        require(source in setOf("audius", "local", "https"))
        require(id.isNotBlank() && playbackUri.isNotBlank())
    }
    fun json(): JSONObject = JSONObject()
        .put("source", source).put("id", id).put("title", title)
        .put("artist", artist).put("playbackUri", playbackUri).put("artwork", artwork)

    companion object {
        fun fromJson(j: JSONObject): ExternalPlaylistItem = ExternalPlaylistItem(
            j.getString("source"), j.getString("id"), j.optString("title"),
            j.optString("artist"), j.getString("playbackUri"), j.optString("artwork")
        )
        fun fromAudius(song: AudiusTrack): ExternalPlaylistItem = ExternalPlaylistItem(
            "audius", song.id, song.title, song.artist, song.audioUrl, song.artwork
        )
        fun fromLocal(uri: Uri, title: String, artist: String): ExternalPlaylistItem =
            ExternalPlaylistItem("local", uri.toString(), title, artist, uri.toString())
    }
}

class HybridPlaylistStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "resonance_hybrid_playlist_v1", Context.MODE_PRIVATE
    )
    private fun key(accountId: String, playlistId: String): String {
        require(accountId.isNotBlank() && playlistId.isNotBlank()) {
            "Spotify hesabı ve liste kimliği bulunamadı"
        }
        return "account:" + accountId + ":playlist:" + playlistId
    }
    @Synchronized
    fun entries(accountId: String, playlistId: String): List<ExternalPlaylistItem> {
        val raw = prefs.getString(key(accountId, playlistId), "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { index ->
            runCatching { ExternalPlaylistItem.fromJson(arr.getJSONObject(index)) }.getOrNull()
        }
    }
    @Synchronized
    fun add(accountId: String, playlistId: String, item: ExternalPlaylistItem): Boolean {
        val previous = entries(accountId, playlistId)
        if (previous.any { it.source == item.source && it.id == item.id }) return false
        save(accountId, playlistId, previous + item)
        return true
    }
    @Synchronized
    fun remove(accountId: String, playlistId: String, item: ExternalPlaylistItem) {
        save(accountId, playlistId, entries(accountId, playlistId)
            .filterNot { it.source == item.source && it.id == item.id })
    }
    @Synchronized
    fun move(accountId: String, playlistId: String, item: ExternalPlaylistItem, delta: Int) {
        val previous = entries(accountId, playlistId).toMutableList()
        val from = previous.indexOfFirst { it.source == item.source && it.id == item.id }
        val to = from + delta
        if (from !in previous.indices || to !in previous.indices) return
        val moving = previous.removeAt(from)
        previous.add(to, moving)
        save(accountId, playlistId, previous)
    }
    private fun save(accountId: String, playlistId: String, entries: List<ExternalPlaylistItem>) {
        val array = JSONArray()
        entries.forEach { array.put(it.json()) }
        check(prefs.edit().putString(key(accountId, playlistId), array.toString()).commit()) {
            "Liste cihazda kaydedilemedi"
        }
    }
}
