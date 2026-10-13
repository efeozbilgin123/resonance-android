package com.resonance.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persist only stable track identifiers/metadata, never Spotify OAuth tokens or
 * signed YouTube CDN URLs. On recovery streams must resolve a fresh audio URL.
 */
class PlaybackQueuePersistence(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "resonance_queue_v1", Context.MODE_PRIVATE
    )

    fun save(queue: PlaybackQueue) {
        val records = JSONArray()
        queue.entries.take(750).forEach { song ->
            records.put(JSONObject()
                .put("id", song.id)
                .put("source", song.source.name)
                .put("title", song.title)
                .put("artist", song.artist)
                .put("duration", song.duration)
                .put("cover", song.cover))
        }
        val visited = JSONArray().also { array ->
            queue.visited.forEach { array.put(it) }
        }
        val history = JSONArray().also { array ->
            queue.history.forEach { array.put(it) }
        }
        val root = JSONObject()
            .put("version", 1)
            .put("entries", records)
            .put("currentIndex", queue.currentIndex)
            .put("shuffle", queue.shuffle)
            .put("repeat", queue.repeat)
            .put("randomState", queue.randomState)
            .put("visited", visited)
            .put("history", history)
        prefs.edit().putString("queue", root.toString()).apply()
    }

    fun restore(): PlaybackQueue = runCatching {
        val root = JSONObject(prefs.getString("queue", "") ?: "")
        require(root.optInt("version") == 1)
        val items = root.getJSONArray("entries")
        val songs = (0 until items.length().coerceAtMost(750)).mapNotNull { i ->
            runCatching {
                val j = items.getJSONObject(i)
                val source = MusicOrigin.valueOf(j.getString("source"))
                val id = j.getString("id")
                require(id.isNotBlank())
                UnifiedSong(id, source, j.optString("title"),
                    j.optString("artist"), j.optInt("duration"),
                    j.optString("cover"))
            }.getOrNull()
        }
        val visitedArr = root.optJSONArray("visited") ?: JSONArray()
        val historyArr = root.optJSONArray("history") ?: JSONArray()
        PlaybackQueue(
            entries = songs,
            currentIndex = root.optInt("currentIndex", -1).takeIf {
                it in songs.indices
            } ?: -1,
            repeat = root.optInt("repeat").coerceIn(0, 2),
            shuffle = root.optBoolean("shuffle"),
            randomState = root.optLong("randomState", 1L),
            visited = (0 until visitedArr.length()).map {
                visitedArr.optInt(it, -1)
            }.filter { it in songs.indices }.toSet(),
            history = (0 until historyArr.length()).map {
                historyArr.optInt(it, -1)
            }.filter { it in songs.indices }
        )
    }.getOrDefault(PlaybackQueue())
}
