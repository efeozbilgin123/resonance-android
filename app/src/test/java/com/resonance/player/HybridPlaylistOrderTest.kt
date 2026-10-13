package com.resonance.player

import org.junit.Assert.*
import org.junit.Test

class HybridPlaylistOrderTest {
    private val spotify = SpotifyTrack("spotify:track:1234", "Stronger", "Kanye West", false, 312)
    private val youtube = ExternalPlaylistItem("youtube","dQw4w9WgXcQ",
        "Track", "Uploader", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
    private val audius = ExternalPlaylistItem("audius", "song-id",
        "Beat", "Producer", "https://api.audius.co/v1/tracks/song-id/stream")

    @Test fun defaultListContainsSpotifyAndExternalEntries() {
        val rows=HybridPlaylistOrder.merge(listOf(spotify),listOf(youtube,audius),emptyList())
        assertEquals(listOf("Spotify","YouTube Music","Audius"), rows.map { it.origin })
        assertEquals(listOf(MusicOrigin.SPOTIFY, MusicOrigin.YOUTUBE_MUSIC, MusicOrigin.AUDIUS),
            rows.map { it.unified()!!.source })
    }

    @Test fun localReorderPreservesRemoteSpotifyAndDuplicates() {
        val rows=HybridPlaylistOrder.merge(listOf(spotify,spotify),
            listOf(youtube),emptyList())
        assertEquals(3,rows.map{it.key}.distinct().size)
        val keys=HybridPlaylistOrder.swap(rows,2,0)
        val moved=HybridPlaylistOrder.merge(listOf(spotify,spotify),listOf(youtube),keys)
        assertEquals(MusicOrigin.YOUTUBE_MUSIC,moved[0].unified()!!.source)
        assertEquals(2,moved.count{it.spotify!=null})
    }

    @Test fun missingOldEntriesDoNotHideNewOnes() {
        val rows=HybridPlaylistOrder.merge(listOf(spotify),listOf(youtube),
            listOf("nonexistent-key", "external:youtube:dQw4w9WgXcQ"))
        assertEquals(2,rows.size)
        assertEquals("YouTube Music",rows[0].origin)
    }

    @Test fun externalLocalUriCanJoinQueue() {
        val file=ExternalPlaylistItem("local", "content://resonance/test", "File",
            "Local artist", "content://resonance/test")
        val row=HybridPlaylistOrder.merge(emptyList(),listOf(file),emptyList()).single()
        assertEquals(MusicOrigin.LOCAL,row.unified()?.source)
    }

    @Test fun spotifyLocalItemIsDisplayedButNotPlayedAsPremium() {
        val local=SpotifyTrack("spotify:local:Artist:Album:Title:180", "Title", "Artist", true)
        val row=HybridPlaylistOrder.merge(listOf(local),emptyList(),emptyList()).single()
        assertNull(row.unified())
    }
}
