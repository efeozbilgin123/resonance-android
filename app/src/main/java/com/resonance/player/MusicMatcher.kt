package com.resonance.player

import java.text.Normalizer
import kotlin.math.abs

/**
 * Optional, user-initiated discovery in Audius from a title/artist query.
 * No Spotify audio or DRM is touched. Suggestions are never auto-played:
 * the user must confirm the correct independently hosted recording.
 */
data class MusicMatch(val candidate: AudiusTrack, val score: Double) {
    val label: String get() = when {
        score >= 0.86 -> "Güçlü aday"
        score >= 0.64 -> "Benzer kayıt"
        else -> "Kontrol edilmeli"
    }
}

object MusicMatcher {
    private val whitespace = Regex("\\s+")
    private val decorations = setOf("official", "video", "audio", "lyrics", "lyric", "remastered",
        "remaster", "feat", "ft", "hd", "hq", "music")

    private fun normalized(text: String): Set<String> {
        val stripped = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace('ı', 'i')
            .replace(Regex("[^a-z0-9]+"), " ")
        return stripped.split(whitespace).filter { it.isNotBlank() && it !in decorations }.toSet()
    }

    private fun similarity(a: String, b: String): Double {
        val left = normalized(a)
        val right = normalized(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val shared = left.intersect(right).size.toDouble()
        return 2.0 * shared / (left.size + right.size)
    }

    fun rate(title: String, artist: String, lengthSeconds: Int?, candidate: AudiusTrack): Double {
        val song = similarity(title, candidate.title)
        val performer = similarity(artist, candidate.artist)
        val duration = if (lengthSeconds == null || lengthSeconds <= 0 ||
            candidate.duration <= 0) 0.5 else {
            val difference = abs(lengthSeconds - candidate.duration)
            when {
                difference <= 3 -> 1.0
                difference <= 12 -> 0.8
                difference <= 30 -> 0.4
                else -> 0.0
            }
        }
        // Weight title + artist most; duration only helps disambiguate different mixes.
        return song * 0.56 + performer * 0.36 + duration * 0.08
    }

    fun suggestions(title: String, artist: String, durationSeconds: Int?): List<MusicMatch> {
        val query = "$title $artist".trim()
        require(query.isNotBlank()) { "Parça bilgisi eksik" }
        return AudiusCatalog.fetch(query).map { track ->
            MusicMatch(track, rate(title, artist, durationSeconds, track))
        }.sortedByDescending { it.score }.take(12)
    }
}
