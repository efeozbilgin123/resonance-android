package com.resonance.player

import org.junit.Assert.assertTrue
import org.junit.Test

class MusicMatcherTest {
    @Test fun identifiesArtistTitleAndLength() {
        val exact = AudiusTrack("1", "Stronger", "Kanye West", "Hip-Hop", 311)
        val wrong = AudiusTrack("2", "Stronger Live Cover", "Anonymous Band", "Rock", 470)
        val goodScore = MusicMatcher.rate("Stronger", "Kanye West", 312, exact)
        val badScore = MusicMatcher.rate("Stronger", "Kanye West", 312, wrong)
        assertTrue("Exact candidate should rank highly", goodScore >= 0.95)
        assertTrue("Exact candidate should beat cover", goodScore > badScore)
    }

    @Test fun ignoresCosmeticMetadataAndNormalizesUnicode() {
        val candidate = AudiusTrack("3", "Çilekeş (Official Audio)", "Çilekeş", "Rock", 220)
        val score = MusicMatcher.rate("Çilekeş", "Çilekeş", 220, candidate)
        assertTrue("Punctuation and accent differences must be normalized", score > 0.85)
    }

    @Test fun rejectsDifferentArtistAndSong() {
        val candidate = AudiusTrack("4", "Morning Coffee", "Unknown", "Jazz", 180)
        assertTrue(MusicMatcher.rate("Night Drive", "A Producer", 180, candidate) < 0.2)
    }
}
