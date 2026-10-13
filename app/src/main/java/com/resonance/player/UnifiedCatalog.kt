package com.resonance.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

enum class CatalogSource { SPOTIFY, YOUTUBE }

data class UnifiedMusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Int,
    val cover: String,
    val source: CatalogSource,
    val score: Double,
    val verifiedMusic: Boolean = false
) {
    val stableId: String get() = source.name + ":" + id

    fun spotifyItem(): SpotifyTrack? =
        if (source == CatalogSource.SPOTIFY) SpotifyTrack(
            id, title, artist, false, duration
        ) else null

    fun externalItem(): ExternalPlaylistItem? =
        if (source == CatalogSource.YOUTUBE) {
            val videoId = id
            ExternalPlaylistItem("youtube", videoId, title, artist,
                "https://www.youtube.com/watch?v=$videoId", cover)
        } else null
}

data class UnifiedSearchResult(
    val tracks: List<UnifiedMusicTrack>,
    val warnings: List<String>,
    val spotifyConnected: Boolean
)

object RelevanceRanker {
    private val combining = Regex("\\p{M}+")
    private val nonLetters = Regex("[^\\p{L}\\p{N}]+")
    private val stopWords = setOf("official", "video", "audio", "music", "song", "hd",
        "hq", "lyrics", "lyric", "feat", "ft", "remastered", "topic", "vevo")
    private val undesirable = listOf("cover", "reaction", "karaoke", "nightcore",
        "sped up", "slowed", "8d audio")

    fun normalize(raw: String): String =
        Normalizer.normalize(raw.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
            .replace(combining, "").replace('ı', 'i')
            .replace(nonLetters, " ").trim().replace(Regex("\\s+"), " ")

    private fun tokens(text: String): Set<String> =
        normalize(text).split(" ").filter { it.isNotBlank() && it !in stopWords }.toSet()

    private fun dice(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return (2.0 * a.intersect(b).size) / (a.size + b.size)
    }

    fun score(query: String, title: String, artist: String, providerRank: Int,
        musicConfidence: Double = 0.8
    ): Double {
        val q = tokens(query)
        val name = tokens(title)
        val performer = tokens(artist)
        val joined = name + performer
        val match = dice(q, joined)
        val titleMatch = dice(q, name)
        val exact = if (normalize(title) == normalize(query)) 0.20 else 0.0
        val prefix = if (normalize(title).startsWith(normalize(query))) 0.08 else 0.0
        val penalty = if (undesirable.any { normalize(title).contains(it) } &&
            undesirable.none { normalize(query).contains(it) }) 0.18 else 0.0
        return (match * 0.49 + titleMatch * 0.27 + exact + prefix +
            musicConfidence.coerceIn(0.0, 1.0) * 0.17 -
            penalty + 0.09 / (1.0 + providerRank * 0.2)).coerceIn(0.0, 1.2)
    }

    /** Keep the best result for repeated uploads within the same catalog. */
    fun merge(query: String, songs: List<UnifiedMusicTrack>): List<UnifiedMusicTrack> {
        val ranked = songs.sortedWith(compareByDescending<UnifiedMusicTrack> { it.score }
            .thenBy { it.stableId })
        val seen = mutableSetOf<String>()
        return ranked.filter {
            val key = it.source.name + ":" + normalize(it.title).replace("official audio", "")
                + ":" + normalize(it.artist).replace(" topic", "")
            if (seen.add(key)) true else false
        }.take(60)
    }
}

object UnifiedCatalog {
    suspend fun search(
        query: String,
        spotify: SpotifyClient,
        allowSpotify: Boolean
    ): UnifiedSearchResult = coroutineScope {
        val trimmed = query.trim()
        require(trimmed.length in 2..180)
        val youtube = async(Dispatchers.IO) {
            runCatching { YouTubeMusicProvider.search(trimmed) }
        }
        val spotifyRequest = async(Dispatchers.IO) {
            if (!allowSpotify) Result.success(emptyList<UnifiedMusicTrack>())
            else runCatching {
                val q = URLEncoder.encode(trimmed, "UTF-8")
                val response = spotify.get("/search?q=$q&type=track&limit=30")
                val array = response.optJSONObject("tracks")
                    ?.optJSONArray("items") ?: JSONArray()
                (0 until array.length()).mapNotNull { index ->
                    val obj = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = obj.optString("uri")
                    if (!id.startsWith("spotify:track:")) return@mapNotNull null
                    val title = obj.optString("name").ifBlank { "Adsız şarkı" }
                    val artist = obj.optJSONArray("artists")
                        ?.optJSONObject(0)?.optString("name").orEmpty()
                    val cover = obj.optJSONObject("album")?.optJSONArray("images")
                        ?.optJSONObject(0)?.optString("url").orEmpty()
                    UnifiedMusicTrack(
                        id, title, artist, obj.optInt("duration_ms") / 1000, cover,
                        CatalogSource.SPOTIFY,
                        RelevanceRanker.score(trimmed, title, artist, index, 1.0),
                        verifiedMusic = true
                    )
                }
            }
        }
        val yt = youtube.await()
        val sp = spotifyRequest.await()
        val warnings = buildList {
            yt.exceptionOrNull()?.let {
                add("YouTube araması: ${it.localizedMessage ?: "Kaynak yanıt vermedi"}")
            }
            sp.exceptionOrNull()?.let {
                add("Spotify araması: ${it.localizedMessage ?: "Giriş veya API sorunu"}")
            }
        }
        val videoSongs = yt.getOrDefault(emptyList()).mapIndexed { index, track ->
            UnifiedMusicTrack(
                id = track.videoId, title = track.title, artist = track.artist,
                duration = track.durationSeconds, cover = track.artwork,
                source = CatalogSource.YOUTUBE,
                score = RelevanceRanker.score(trimmed, track.title, track.artist,
                    index, track.musicLikelihood),
                verifiedMusic = track.musicLikelihood > 0.9
            )
        }
        UnifiedSearchResult(
            RelevanceRanker.merge(trimmed, videoSongs + sp.getOrDefault(emptyList())),
            warnings, allowSpotify
        )
    }
}
