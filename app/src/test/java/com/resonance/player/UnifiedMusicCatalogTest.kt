package com.resonance.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedMusicCatalogTest {
    @Test fun matchingMetadataRanksAboveUnrelatedMusic() {
        val exact = MusicRelevance.score("Daft Punk One More Time",
            "One More Time", "Daft Punk", MusicOrigin.YOUTUBE_MUSIC, 320)
        val other = MusicRelevance.score("Daft Punk One More Time",
            "Chillhop Coffee", "Unknown", MusicOrigin.YOUTUBE_MUSIC, 320)
        assertTrue(exact > other + 0.5)
    }

    @Test fun remixesAreDemotedUnlessRequested() {
        val exact = MusicRelevance.score("Stronger", "Stronger", "Kanye West",
            MusicOrigin.YOUTUBE_MUSIC, 280)
        val live = MusicRelevance.score("Stronger", "Stronger (Live Remix)", "Kanye West",
            MusicOrigin.YOUTUBE_MUSIC, 280)
        assertTrue(exact > live)
    }

    @Test fun spotifyAndYoutubeRemainSeparateSelectableSources() {
        val tracks = listOf(
            UnifiedSong("spotify:track:123", MusicOrigin.SPOTIFY,"Stronger","Kanye West"),
            UnifiedSong("dQw4w9WgXcQ", MusicOrigin.YOUTUBE_MUSIC,"Stronger","Kanye West"),
            UnifiedSong("dQw4w9WgXcQ", MusicOrigin.YOUTUBE_MUSIC,"Stronger","Kanye West")
        )
        val ranked = MusicRelevance.rank("Stronger", tracks)
        assertEquals(2,ranked.size)
        assertTrue(ranked.any { it.source == MusicOrigin.SPOTIFY })
        assertTrue(ranked.any { it.source == MusicOrigin.YOUTUBE_MUSIC })
    }

    @Test fun turkishTextNormalization() {
        assertTrue(MusicRelevance.score("Çilekeş", "CILEKES (Official Audio)",
            "Çilekeş",MusicOrigin.YOUTUBE_MUSIC,230) > 0.7)
    }
}
