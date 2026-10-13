package com.resonance.player

/**
 * A Spotify playlist plus Resonance's non-Spotify sidecar in a single ordered
 * view. Reordering changes only Resonance's view; Spotify's remote ordering
 * remains untouched. Duplicate Spotify tracks are distinguished by occurrence.
 */
data class HybridPlaylistRow(
    val key: String,
    val spotify: SpotifyTrack? = null,
    val external: ExternalPlaylistItem? = null
) {
    val title: String get() = spotify?.title ?: external?.title.orEmpty()
    val artist: String get() = spotify?.artist ?: external?.artist.orEmpty()
    val origin: String get() = if (spotify != null) "Spotify" else
        when (external?.source) {
            "youtube" -> "YouTube Music"
            "audius" -> "Audius"
            "local" -> "Yerel"
            else -> "Bağımsız yayın"
        }

    fun unified(): UnifiedSong? = when {
        spotify != null -> {
            if (!spotify.uri.startsWith("spotify:track:")) return null
            UnifiedSong(spotify.uri, MusicOrigin.SPOTIFY, spotify.title,
                spotify.artist, spotify.durationSeconds)
        }
        external != null -> {
            val source = when (external.source) {
                "youtube" -> MusicOrigin.YOUTUBE_MUSIC
                "audius" -> MusicOrigin.AUDIUS
                "local" -> MusicOrigin.LOCAL
                else -> MusicOrigin.STREAM
            }
            val id = if (source == MusicOrigin.LOCAL ||
                source == MusicOrigin.STREAM) external.playbackUri else external.id
            UnifiedSong(id, source, external.title, external.artist,
                cover = external.artwork)
        }
        else -> null
    }
}

object HybridPlaylistOrder {
    fun merge(
        spotify: List<SpotifyTrack>,
        external: List<ExternalPlaylistItem>,
        order: List<String>
    ): List<HybridPlaylistRow> {
        val seen = mutableMapOf<String, Int>()
        val rows = spotify.map { track ->
            val occurrence = seen.getOrDefault(track.uri, 0)
            seen[track.uri] = occurrence + 1
            HybridPlaylistRow(key = "spotify:${track.uri}:$occurrence", spotify = track)
        } + external.map { item ->
            HybridPlaylistRow("external:${item.source}:${item.id}", external = item)
        }
        val index = order.withIndex().associate { it.value to it.index }
        return rows.sortedWith(compareBy<HybridPlaylistRow> {
            index[it.key] ?: (order.size + rows.indexOf(it))
        }.thenBy { rows.indexOf(it) })
    }

    fun swap(order: List<HybridPlaylistRow>, from: Int, to: Int): List<String> {
        if (from !in order.indices || to !in order.indices) return order.map { it.key }
        return order.map { it.key }.toMutableList().apply {
            val item = removeAt(from)
            add(to, item)
        }
    }
}
