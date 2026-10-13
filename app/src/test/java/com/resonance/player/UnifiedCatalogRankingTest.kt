package com.resonance.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedCatalogRankingTest {
    @Test fun prefersExactTitleWithMatchingPerformer() {
        val exact = RelevanceRanker.score("Kanye West Stronger", "Stronger", "Kanye West", 1, 1.0)
        val cover = RelevanceRanker.score("Kanye West Stronger", "Stronger Live Cover",
            "Random Band", 0, 0.2)
        assertTrue(exact > cover)
    }

    @Test fun keepsSourceChoicesForSameSong() {
        val query = "stronger"
        val spotify = UnifiedMusicTrack("spotify:track:abc", "Stronger", "Kanye West", 300,
            "", CatalogSource.SPOTIFY, 0.97, true)
        val youtube = UnifiedMusicTrack("dQw4w9WgXcQ", "Stronger", "Kanye West", 310,
            "", CatalogSource.YOUTUBE, 0.92, true)
        val duplicate = youtube.copy(id = "aaaaaaaaaaa", score = 0.6)
        val merged = RelevanceRanker.merge(query, listOf(spotify, youtube, duplicate))
        assertEquals(2, merged.size)
        assertEquals(CatalogSource.SPOTIFY, merged[0].source)
        assertEquals(CatalogSource.YOUTUBE, merged[1].source)
    }

    @Test fun handlesTurkishSpellingAndStableOrdering() {
        assertEquals("cilekes ask", RelevanceRanker.normalize("ÇİLEKEŞ AŞK"))
        val list = listOf(
            UnifiedMusicTrack("x", "Çilekeş", "Çilekeş", 200, "",
                CatalogSource.SPOTIFY, 0.4),
            UnifiedMusicTrack("aaaaaaaaaaa", "Çilekeş", "Çilekeş", 200, "",
                CatalogSource.YOUTUBE, 0.4)
        )
        assertEquals(2, RelevanceRanker.merge("Çilekeş", list).size)
    }
}
