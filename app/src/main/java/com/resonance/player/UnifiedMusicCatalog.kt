package com.resonance.player

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Shared metadata search with a source-aware playback identity.
 * Spotify URI is never used as an audio URL, and a YouTube ID is never
 * represented as an item saved to Spotify servers.
 */
enum class MusicOrigin { SPOTIFY, YOUTUBE_MUSIC, AUDIUS }

data class UnifiedSong(
    val id: String,
    val source: MusicOrigin,
    val title: String,
    val artist: String,
    val duration: Int = 0,
    val cover: String = "",
    val streamable: Boolean = true,
    val relevance: Double = 0.0
) {
    val stableKey: String get() = "${source.name}:$id"
    fun external(): ExternalPlaylistItem = when (source) {
        MusicOrigin.YOUTUBE_MUSIC -> {
            require(id.matches(Regex("[A-Za-z0-9_-]{11}")))
            ExternalPlaylistItem("youtube", id, title, artist,
                "https://www.youtube.com/watch?v=$id", cover)
        }
        MusicOrigin.AUDIUS -> ExternalPlaylistItem(
            "audius", id, title, artist,
            "https://api.audius.co/v1/tracks/${Uri.encode(id)}/stream?app_name=ResonanceAndroid", cover)
        MusicOrigin.SPOTIFY -> error("Spotify parçaları yalnız Spotify Web API ile listeye eklenir")
    }
    fun spotifyTrack() = SpotifyTrack(id, title, artist, false, duration)
}

data class SearchBundle(val songs: List<UnifiedSong>, val warnings: List<String>)

object MusicRelevance {
    private val words = Regex("[a-z0-9]+")
    fun normalize(raw: String): String =
        Normalizer.normalize(raw.lowercase(Locale.ROOT).replace('ı','i'),
            Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ").trim()

    fun score(query: String, title: String, artist: String,
        source: MusicOrigin, length: Int = 0): Double {
        val wanted = words.findAll(normalize(query)).map { it.value }.toSet()
        if (wanted.isEmpty()) return 0.0
        val titleWords = words.findAll(normalize(title)).map { it.value }.toSet()
        val artistWords = words.findAll(normalize(artist)).map { it.value }.toSet()
        val matchesTitle = wanted.intersect(titleWords).size.toDouble() / wanted.size
        val matchesArtist = wanted.intersect(artistWords).size.toDouble() / wanted.size
        val coverage = wanted.intersect(titleWords + artistWords).size.toDouble() / wanted.size
        var rank = matchesTitle * 0.40 + matchesArtist * 0.18 + coverage * 0.42
        val q = normalize(query)
        val t = normalize(title)
        if (q == t) rank += 0.15
        else if (t.startsWith(q)) rank += 0.07
        val negative = listOf("live", "remix", "nightcore", "sped up", "slowed",
            "cover", "karaoke", "reaction", "instrumental", "8d", "loop", "lyrics")
        if (negative.any { it in t && it !in q }) rank -= 0.19
        if (source == MusicOrigin.YOUTUBE_MUSIC &&
            (normalize(artist).endsWith("topic") || "official" in normalize(artist))) {
            rank += 0.04
        }
        if (length > 0 && length < 50) rank -= 0.10
        if (length > 960) rank -= 0.12
        return rank.coerceIn(0.0, 1.0)
    }

    fun rank(query: String, items: List<UnifiedSong>): List<UnifiedSong> =
        items.map {
            it.copy(relevance = score(query, it.title, it.artist, it.source, it.duration))
        }.distinctBy { it.stableKey }.sortedWith(
            compareByDescending<UnifiedSong> { it.relevance }
                .thenBy { when (it.source) {
                    MusicOrigin.YOUTUBE_MUSIC -> 0
                    MusicOrigin.SPOTIFY -> 1
                    MusicOrigin.AUDIUS -> 2
                } }
        )
}

/**
 * Unofficial Piped/Invidious provider for YouTube Music search and streams.
 * Public provider uptime, available audio tracks and URLs are not guaranteed.
 *
 * IMPORTANT: This does not bypass DRM, login, members-only restrictions, or
 * capture protected audio; it only uses open audio URLs returned by independent
 * public endpoints. Video URLs never go directly into ExoPlayer.
 */
class YouTubeAudioProvider(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "resonance_youtube_provider_v1", Context.MODE_PRIVATE
    )

    var preferredServer: String
        get() = prefs.getString("server", "").orEmpty()
        set(value) {
            val trimmed = value.trim().trimEnd('/')
            if (trimmed.isNotEmpty()) require(allowedBase(trimmed)) {
                "HTTPS üzerinden erişilen geçerli bir API adresi gir"
            }
            prefs.edit().putString("server", trimmed).apply()
        }

    private val defaults = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://inv.nadeko.net",
        "https://invidious.tiekoetter.com",
        "https://invidious.nerdvpn.de"
    )

    private fun allowedBase(base: String): Boolean {
        val u = runCatching { URI(base) }.getOrNull() ?: return false
        return u.scheme == "https" && !u.host.isNullOrBlank() &&
            u.userInfo == null && u.rawQuery == null && u.rawFragment == null &&
            (u.path.isNullOrBlank() || u.path == "/")
    }

    private fun bases(): List<String> =
        (listOf(preferredServer).filter { it.isNotBlank() } + defaults).distinct()

    private fun api(base: String, path: String): String {
        val c = (URL(base + path).openConnection() as HttpURLConnection)
        try {
            c.connectTimeout = 4300
            c.readTimeout = 7500
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "Resonance/0.7")
            val status = c.responseCode
            require(status in 200..299) { "Sunucu HTTP $status" }
            val text = c.inputStream.bufferedReader().use { it.readText() }
            require(text.length < 4_000_000) { "API yanıtı çok büyük" }
            return text
        } finally { c.disconnect() }
    }

    fun search(query: String): List<UnifiedSong> {
        require(query.isNotBlank())
        var last: Exception? = null
        // Prefer direct YouTube Music Songs results without depending on
        // volunteer Piped/Invidious instances. If Youtube changes its API,
        // existing Piped/Invidious fallback still works.
        if (preferredServer.isBlank()) {
            try {
                val local = YouTubeMusicProvider.search(query.trim()).map {
                    UnifiedSong(it.videoId, MusicOrigin.YOUTUBE_MUSIC,
                        it.title, it.artist, it.durationSeconds, it.artwork)
                }
                if (local.isNotEmpty()) return local
            } catch (e: Exception) {
                last = e
            }
        }
        val q = Uri.encode(query.trim())
        for (base in bases()) {
            try {
                val piped = base.contains("piped")
                val path = if (piped) "/search?q=$q&filter=music_songs" else
                    "/api/v1/search?q=$q&type=video"
                val response = api(base, path)
                val json = if (piped) JSONObject(response).optJSONArray("items")
                    ?: JSONArray() else JSONArray(response)
                val tracks = (0 until json.length()).mapNotNull { index ->
                    val data = json.optJSONObject(index) ?: return@mapNotNull null
                    if (piped && data.optString("type") !in setOf("stream", "video", "song",
                            "music_song", "musicSong", "")) return@mapNotNull null
                    if (!piped && data.optString("type") != "video") return@mapNotNull null
                    val id = if (piped) {
                        YouTubeLinks.parse(
                            "https://www.youtube.com" + data.optString("url")) 
                    } else data.optString("videoId")
                    if (id == null || !id.matches(Regex("[A-Za-z0-9_-]{11}")))
                        return@mapNotNull null
                    val duration = if (piped) data.optInt("duration") else
                        data.optInt("lengthSeconds")
                    val artist = if (piped) data.optString("uploaderName") else
                        data.optString("author")
                    UnifiedSong(
                        id = id, source = MusicOrigin.YOUTUBE_MUSIC,
                        title = data.optString("title").ifBlank { "YouTube Music" },
                        artist = artist.ifBlank { "YouTube Music" },
                        duration = duration,
                        cover = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                    )
                }.distinctBy { it.id }
                if (tracks.isNotEmpty()) return tracks.take(35)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("YouTube Music arama sunucuları erişilebilir değil")
    }

    data class Resolved(val url: String, val mimeType: String,
        val server: String)

    private val streamCache = ConcurrentHashMap<String, Pair<Long, Resolved>>()
    private val rejectedProvider = ConcurrentHashMap<String, String>()

    fun invalidate(videoId: String) {
        streamCache.remove(videoId)?.let { cached ->
            rejectedProvider[videoId] = cached.second.server
        }
    }

    fun resolve(videoId: String): Resolved {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
        streamCache[videoId]?.takeIf { it.first > System.currentTimeMillis() }
            ?.let { return it.second }
        var last: Exception? = null
        if (preferredServer.isBlank() && rejectedProvider[videoId] != "NewPipe") {
            try {
                val audio = YouTubeMusicProvider.resolveAudio(videoId)
                val result = Resolved(audio.url, audio.mimeType, "NewPipe")
                streamCache[videoId] = (System.currentTimeMillis() + 7 * 60_000L) to result
                return result
            } catch (e: Exception) {
                last = e
            }
        }
        for (base in bases()) {
            if (base == rejectedProvider[videoId]) continue
            try {
                val piped = base.contains("piped")
                val response = JSONObject(api(base,
                    if (piped) "/streams/$videoId" else "/api/v1/videos/$videoId"))
                val list = if (piped) response.optJSONArray("audioStreams") else
                    response.optJSONArray("adaptiveFormats")
                if (list == null || list.length() == 0) continue
                val options = (0 until list.length()).mapNotNull { idx ->
                    val o = list.optJSONObject(idx) ?: return@mapNotNull null
                    val mime = (if (piped) o.optString("mimeType") else
                        o.optString("type")).substringBefore(';').trim()
                    val uri = o.optString("url")
                    if (!mime.startsWith("audio/") || !isStreamUrl(uri))
                        return@mapNotNull null
                    Triple(uri, mime, o.optInt("bitrate", 0))
                }
                // Prefer widely supported AAC/M4A to Opus, then highest bitrate.
                val best = options.maxByOrNull {
                    (if (it.second == "audio/mp4") 1_000_000 else 0) +
                        it.third.coerceIn(0, 999_999)
                } ?: continue
                val resolved = Resolved(best.first, best.second, base)
                // URLs from Piped/YouTube expire; re-resolve well before expiry.
                streamCache[videoId] = (System.currentTimeMillis() + 9 * 60_000L) to resolved
                return resolved
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException(
            "YouTube ses akışı bulunamadı. Kaynak sunucusunu değiştir.")
    }

    private fun isStreamUrl(raw: String): Boolean {
        val u = runCatching { URI(raw) }.getOrNull() ?: return false
        return u.scheme == "https" && !u.host.isNullOrBlank() &&
            u.userInfo == null
    }
}

class UnifiedMusicCatalog(private val context: Context, private val spotify: SpotifyClient) {
    private val youtube = YouTubeAudioProvider(context)

    fun searchSpotify(query: String): List<UnifiedSong> {
        if (!spotify.authorized) return emptyList()
        val arr = spotify.get("/search?q=${Uri.encode(query.trim())}&type=track&limit=35")
            .optJSONObject("tracks")?.optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val uri = obj.optString("uri")
            if (!uri.startsWith("spotify:track:")) return@mapNotNull null
            val images = obj.optJSONObject("album")?.optJSONArray("images")
            UnifiedSong(uri, MusicOrigin.SPOTIFY, obj.optString("name"),
                obj.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty(),
                obj.optInt("duration_ms") / 1000,
                images?.optJSONObject(0)?.optString("url").orEmpty())
        }
    }

    fun searchYouTube(query: String): List<UnifiedSong> = youtube.search(query)

    fun searchAudius(query: String): List<UnifiedSong> =
        AudiusCatalog.fetch(query).take(15).map {
            UnifiedSong(it.id, MusicOrigin.AUDIUS, it.title, it.artist,
                it.duration, it.artwork)
        }

    fun search(query: String): SearchBundle {
        val results = mutableListOf<UnifiedSong>()
        val warnings = mutableListOf<String>()
        val providers = listOf<Pair<String, () -> List<UnifiedSong>>>(
            "Spotify" to { searchSpotify(query) },
            "YouTube Music" to { searchYouTube(query) },
            "Audius" to { searchAudius(query) }
        )
        for ((name, searcher) in providers) {
            try { results += searcher() } catch (e: Exception) {
                warnings += "$name: ${e.localizedMessage ?: "Bağlantı yok"}"
            }
        }
        return SearchBundle(MusicRelevance.rank(query, results), warnings)
    }

    fun invalidateStream(song: UnifiedSong) {
        if (song.source == MusicOrigin.YOUTUBE_MUSIC) youtube.invalidate(song.id)
    }

    fun audioStream(song: UnifiedSong): YouTubeAudioProvider.Resolved =
        when (song.source) {
            MusicOrigin.YOUTUBE_MUSIC -> youtube.resolve(song.id)
            MusicOrigin.AUDIUS -> YouTubeAudioProvider.Resolved(
                song.external().playbackUri, "audio/mpeg", "Audius")
            MusicOrigin.SPOTIFY -> error("Spotify sesi yalnızca Premium Rust oynatıcısında")
        }
}
