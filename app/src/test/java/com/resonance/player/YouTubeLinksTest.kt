package com.resonance.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeLinksTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun acceptsOfficialVideoAndMusicLinks() {
        assertEquals(id, YouTubeLinks.parse("https://www.youtube.com/watch?v=$id&t=10"))
        assertEquals(id, YouTubeLinks.parse("https://music.youtube.com/watch?v=$id&si=abc"))
        assertEquals(id, YouTubeLinks.parse("https://youtu.be/$id?si=abc"))
        assertEquals(id, YouTubeLinks.parse("https://www.youtube.com/shorts/$id"))
        assertEquals(id, YouTubeLinks.parse("https://www.youtube.com/live/$id"))
        assertEquals(id, YouTubeLinks.parse("https://www.youtube-nocookie.com/embed/$id"))
        assertEquals(id, YouTubeLinks.parse(id))
        assertEquals("https://www.youtube.com/watch?v=$id",
            YouTubeLinks.canonical("https://youtu.be/$id"))
    }

    @Test fun rejectsHostSpoofingAndUnsafeSchemes() {
        assertNull(YouTubeLinks.parse("http://youtu.be/$id"))
        assertNull(YouTubeLinks.parse("https://youtube.com.evil.example/watch?v=$id"))
        assertNull(YouTubeLinks.parse("https://example.com/watch?v=$id"))
        assertNull(YouTubeLinks.parse("javascript:alert(1)"))
        assertNull(YouTubeLinks.parse("https://attacker@youtube.com/watch?v=$id"))
        assertNull(YouTubeLinks.parse("https://youtube.com:444/watch?v=$id"))
        assertNull(YouTubeLinks.parse("https://youtube.com/watch?v=bad"))
        assertNull(YouTubeLinks.parse("https://youtube.com/playlist?list=PL123"))
    }
}
