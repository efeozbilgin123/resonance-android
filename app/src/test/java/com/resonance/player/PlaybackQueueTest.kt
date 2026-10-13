package com.resonance.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueTest {
    private val spotify = UnifiedSong("spotify:track:abc", MusicOrigin.SPOTIFY,"One More Time","Daft Punk")
    private val youtube = UnifiedSong("dQw4w9WgXcQ",MusicOrigin.YOUTUBE_MUSIC,"Music","Channel")
    private val audius = UnifiedSong("track-12",MusicOrigin.AUDIUS,"Track","Artist")
    private val original = listOf(spotify,youtube,audius)

    @Test fun spotifyIsNeverDroppedFromMixedQueue() {
        var queue=PlaybackQueue().start(original, spotify.stableKey)
        assertEquals(3,queue.entries.size)
        queue=queue.forward()!!
        assertEquals(MusicOrigin.YOUTUBE_MUSIC,queue.current!!.source)
        queue=queue.forward()!!
        assertEquals(MusicOrigin.AUDIUS,queue.current!!.source)
        assertNull(queue.forward(automatic=true))
    }

    @Test fun repeatAndPreviousBehave() {
        val last=PlaybackQueue().start(original,audius.stableKey)
        assertNull(last.forward(automatic=true))
        assertEquals(spotify, last.withRepeat(1).forward(automatic=true)?.current)
        assertEquals(audius, last.withRepeat(2).forward(automatic=true)?.current)
        assertEquals(audius, last.withRepeat(2).forward()?.current)
        assertEquals(spotify, PlaybackQueue().start(original,spotify.stableKey).backward()?.current)
    }

    @Test fun shuffleNeverImmediatelyRepeatsSameItem() {
        var queue=PlaybackQueue().start(original,spotify.stableKey).withShuffle(true)
        repeat(100) {
            val current=queue.currentIndex
            queue=queue.forward()!!
            assertNotEquals(current,queue.currentIndex)
        }
    }

    @Test fun reorderedCurrentTrackRetainsIdentity() {
        val initial=PlaybackQueue().start(original,youtube.stableKey)
        val moved=initial.move(0,2)
        assertEquals(youtube.stableKey,moved.current?.stableKey)
        assertEquals(0,moved.currentIndex)
        assertEquals(listOf(youtube,audius,spotify),moved.entries)
    }

    @Test fun removeAndAddMaintainCurrentIndex() {
        var queue=PlaybackQueue().start(original,youtube.stableKey)
        queue=queue.remove(0)
        assertEquals(youtube.stableKey,queue.current?.stableKey)
        queue=queue.remove(0)
        assertEquals(audius.stableKey,queue.current?.stableKey)
        queue=queue.add(spotify)
        assertEquals(2,queue.entries.size)
        queue=queue.remove(1).remove(0)
        assertNull(queue.current)
        assertEquals(-1,queue.currentIndex)
    }

    @Test fun repeatedEntriesRetainExplicitPositionOnReorder() {
        val queue=PlaybackQueue().start(listOf(spotify,youtube,spotify,audius),youtube.stableKey)
        val moved=queue.move(3,0)
        assertEquals(youtube.stableKey,moved.current?.stableKey)
        assertEquals(2,moved.currentIndex)
        assertEquals(4,moved.entries.size)
    }
}
