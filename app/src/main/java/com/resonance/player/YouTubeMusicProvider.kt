package com.resonance.player

import android.net.Uri
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * NewPipe-backed public YouTube video/music discovery.
 *
 * This is an unofficial provider, not the official YouTube Music API.
 * Results may include official YouTube Music artist uploads and Topic channels,
 * alongside covers and live performances. No DRM/license bypass is attempted.
 */
object YouTubeMusicProvider {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    @Volatile private var started = false

    @Synchronized
    private fun initialize() {
        if (!started) {
            NewPipe.init(YouTubeDownloader(), Localization("tr", "TR"))
            started = true
        }
    }

    data class Recording(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationSeconds: Int,
        val artwork: String,
        val musicLikelihood: Double
    ) {
        fun asExternal() = ExternalPlaylistItem(
            "youtube", videoId, title, artist,
            "https://www.youtube.com/watch?v=$videoId", artwork
        )
    }

    /**
     * Uses NewPipe's YouTube Music "Songs" search filter. Falls back to Music
     * Videos and then public video search if no songs are available.
     * Results are source-specific; they are NOT official Spotify audio.
     */
    fun search(query: String): List<Recording> {
        require(query.length in 2..180) { "Arama 2–180 karakter olmalı" }
        initialize()
        val musicFilters = listOf(
            YoutubeSearchQueryHandlerFactory.MUSIC_SONGS,
            YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS,
            YoutubeSearchQueryHandlerFactory.VIDEOS
        )
        val recordings = mutableListOf<Recording>()
        var error: Exception? = null

        for (filter in musicFilters) {
            if (recordings.size >= 28) break
            try {
                val extractor = ServiceList.YouTube.getSearchExtractor(
                    query, listOf(filter), ""
                )
                extractor.fetchPage()
                val songCategory = filter == YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
                val musicCategory = filter != YoutubeSearchQueryHandlerFactory.VIDEOS
                val parsed = extractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .mapNotNull { item ->
                        val id = YouTubeLinks.parse(item.url) ?: return@mapNotNull null
                        if (!VIDEO_ID.matches(id) || item.duration < 15 ||
                            item.duration > 2700) return@mapNotNull null
                        val channel = item.uploaderName.ifBlank { "YouTube Music" }
                        val title = item.name.ifBlank { "Adsız parça" }
                        val lower = "$title $channel".lowercase()
                        val topical = channel.contains(" - Topic", true)
                        val likelihood = when {
                            songCategory -> 1.0
                            topical || lower.contains("official audio") ||
                                channel.contains("vevo", true) -> 1.0
                            lower.contains("cover") || lower.contains("reaction") ||
                                lower.contains("karaoke") -> 0.20
                            musicCategory -> 0.83
                            lower.contains("live") -> 0.42
                            else -> 0.62
                        }
                        Recording(
                            videoId = id, title = title, artist = channel,
                            durationSeconds = item.duration.coerceAtMost(
                                Int.MAX_VALUE.toLong()).toInt(),
                            artwork = item.thumbnails.firstOrNull()?.url
                                ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                            musicLikelihood = likelihood
                        )
                    }
                recordings += parsed
            } catch (e: Exception) {
                error = e
            }
            if (recordings.size >= 12) break
        }
        if (recordings.isEmpty() && error != null) throw error
        return recordings.distinctBy { it.videoId }.take(35)
    }


    /**
     * Separate ordinary YouTube video search. The Music Songs results are not
     * comprehensive: users must be able to find independent uploads too.
     * Playback still resolves audio on demand and may fail for restricted videos.
     */
    fun searchVideos(query: String): List<Recording> {
        require(query.length in 2..180) { "Arama 2–180 karakter olmalı" }
        initialize()
        val extractor = ServiceList.YouTube.getSearchExtractor(
            query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), ""
        )
        extractor.fetchPage()
        return extractor.initialPage.items.filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val id = YouTubeLinks.parse(item.url) ?: return@mapNotNull null
                if (!VIDEO_ID.matches(id)) return@mapNotNull null
                Recording(
                    videoId = id,
                    title = item.name.ifBlank { "Adsız YouTube videosu" },
                    artist = item.uploaderName.ifBlank { "YouTube" },
                    durationSeconds = item.duration.coerceAtLeast(0L)
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    artwork = item.thumbnails.firstOrNull()?.url
                        ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                    musicLikelihood = 0.5
                )
            }.distinctBy { it.videoId }.take(35)
    }

    data class StreamLocation(val url: String, val mimeType: String)

    /** Resolve on each media open, since YouTube's signed CDN URLs expire. */
    fun resolveAudio(videoId: String): StreamLocation {
        require(VIDEO_ID.matches(videoId)) { "Geçersiz YouTube kimliği" }
        initialize()
        val extractor = ServiceList.YouTube.getStreamExtractor(
            "https://www.youtube.com/watch?v=$videoId"
        )
        extractor.fetchPage()
        val audio = extractor.audioStreams.filter {
            it.isUrl && it.content.startsWith("https://") &&
                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        val best = audio.maxByOrNull {
            val compatible = when (it.format?.mimeType) {
                "audio/mp4" -> 2000
                "audio/webm" -> 1400
                "audio/mpeg" -> 900
                else -> 0
            }
            compatible + it.averageBitrate.coerceAtLeast(0)
        } ?: throw IOException(
            "Bu YouTube kaydı için kullanılabilir ses yayını bulunamadı. " +
                "Yaş/telif/bölge kısıtlaması olabilir veya kaynak değişmiş olabilir."
        )
        return StreamLocation(best.content, best.format?.mimeType ?: "audio/mp4")
    }

    /** HttpURLConnection adapter for NewPipe's Java extractor. */
    private class YouTubeDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val connection = URL(request.url()).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 12000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = true
                connection.requestMethod = request.httpMethod()
                connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                connection.setRequestProperty("Accept-Encoding", "gzip")
                request.headers().forEach { (name, values) ->
                    if (name.equals("Accept-Encoding", true)) return@forEach
                    if (values.isNotEmpty()) connection.setRequestProperty(
                        name, values.joinToString(if (name.equals("Cookie", true)) "; " else ", ")
                    )
                }
                request.dataToSend()?.let { data ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(data) }
                }
                val code = connection.responseCode
                val stream = (if (code < 400) connection.inputStream else connection.errorStream)
                val contents = stream?.use { input ->
                    val readable = if (connection.contentEncoding.equals("gzip", true))
                        GZIPInputStream(input) else input
                    readable.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.orEmpty()
                val responseHeaders = connection.headerFields
                    .filterKeys { it != null }
                    .mapKeys { it.key!! }
                return Response(
                    code, connection.responseMessage.orEmpty(), responseHeaders,
                    contents, connection.url.toString()
                )
            } finally {
                connection.disconnect()
            }
        }
    }
}
