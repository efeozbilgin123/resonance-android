package com.resonance.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device-only favorites for YouTube, Audius and local sources.
 * Spotify hearts are always written to the user's actual Spotify library.
 */
class ResonanceLikesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "resonance_favorites_v2", Context.MODE_PRIVATE
    )

    @Synchronized
    fun entries(): List<UnifiedSong> = runCatching {
        val array = JSONArray(prefs.getString("songs", "[]") ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val source = MusicOrigin.valueOf(item.getString("source"))
                require(source != MusicOrigin.SPOTIFY)
                UnifiedSong(
                    id = item.getString("id"),
                    source = source,
                    title = item.optString("title"),
                    artist = item.optString("artist"),
                    duration = item.optInt("duration"),
                    cover = item.optString("cover")
                )
            }.getOrNull()
        }.distinctBy { it.stableKey }
    }.getOrDefault(emptyList())

    @Synchronized
    fun keys(): Set<String> = entries().map { it.stableKey }.toSet()

    @Synchronized
    fun toggle(song: UnifiedSong): Boolean {
        require(song.source != MusicOrigin.SPOTIFY) {
            "Spotify beğenileri yalnızca Spotify hesabına kaydedilir"
        }
        val old = entries()
        val exists = old.any { it.stableKey == song.stableKey }
        val updated = if (exists) old.filterNot { it.stableKey == song.stableKey }
            else listOf(song) + old
        val array = JSONArray()
        updated.forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("source", item.source.name)
                .put("title", item.title)
                .put("artist", item.artist)
                .put("duration", item.duration)
                .put("cover", item.cover))
        }
        check(prefs.edit().putString("songs", array.toString()).commit())
        return !exists
    }
}
