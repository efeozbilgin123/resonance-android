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
    /**
     * Portable backup for user-chosen cloud/file storage, NOT Spotify synchronization.
     * Local content:// URIs in exported entries are device-specific.
     */
    @Synchronized
    fun exportAccount(accountId: String): String {
        require(accountId.isNotBlank())
        val prefix = "account:" + accountId + ":playlist:"
        val lists = JSONArray()
        for ((name, value) in prefs.all) {
            if (!name.startsWith(prefix) || value !is String) continue
            val playlistId = name.removePrefix(prefix)
            if (!playlistId.matches(Regex("[A-Za-z0-9]+"))) continue
            val items = JSONArray()
            entries(accountId, playlistId).forEach { items.put(it.json()) }
            lists.put(JSONObject().put("playlistId", playlistId).put("entries", items))
        }
        return JSONObject().put("format", "resonance-sidecar-v1")
            .put("accountId", accountId).put("playlists", lists).toString(2)
    }

    /**
     * Merges entries for the same Spotify account, deduplicating by source + ID.
     * Does not import or modify any Spotify playlist server-side.
     */
    @Synchronized
    fun importAccount(accountId: String, data: String): Int {
        require(data.length < 2_000_000) { "Yedek dosyası fazla büyük" }
        val root = JSONObject(data)
        require(root.optString("format") == "resonance-sidecar-v1") {
            "Bu bir Resonance liste yedeği değil"
        }
        require(root.optString("accountId") == accountId && accountId.isNotBlank()) {
            "Yedek başka bir Spotify hesabına ait"
        }
        val lists = root.getJSONArray("playlists")
        require(lists.length() <= 2000) { "Çok fazla liste var" }
        var imported = 0
        for (i in 0 until lists.length()) {
            val list = lists.getJSONObject(i)
            val playlistId = list.getString("playlistId")
            require(playlistId.matches(Regex("[A-Za-z0-9]+"))) {
                "Yedekte geçersiz Spotify liste kimliği"
            }
            val items = list.getJSONArray("entries")
            require(items.length() <= 10000) { "Çok fazla parça var" }
            for (n in 0 until items.length()) {
                val item = ExternalPlaylistItem.fromJson(items.getJSONObject(n))
                if (add(accountId, playlistId, item)) imported++
            }
        }
        return imported
    }

    private fun save(accountId: String, playlistId: String, entries: List<ExternalPlaylistItem>) {
        val array = JSONArray()
        entries.forEach { array.put(it.json()) }
        check(prefs.edit().putString(key(accountId, playlistId), array.toString()).commit()) {
            "Liste cihazda kaydedilemedi"
        }
    }
}
