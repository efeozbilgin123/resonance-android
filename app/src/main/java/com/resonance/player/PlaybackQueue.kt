package com.resonance.player

/**
 * Immutable, ordered queue shared by Spotify Premium, YouTube Music, Audius and
 * local playback. It intentionally does NOT remove Spotify entries from mixed
 * searches. Source switching is handled by the Android coordinator.
 *
 * Repeat: 0 = off, 1 = all, 2 = current track.
 */
data class PlaybackQueue(
    val entries: List<UnifiedSong> = emptyList(),
    val currentIndex: Int = -1,
    val repeat: Int = 0,
    val shuffle: Boolean = false,
    val randomState: Long = 1L
) {
    val current: UnifiedSong? get() = entries.getOrNull(currentIndex)

    fun start(items: List<UnifiedSong>, selectedKey: String): PlaybackQueue {
        val tracks = items.ifEmpty { emptyList() }
        return copy(entries = tracks, currentIndex = tracks.indexOfFirst {
            it.stableKey == selectedKey
        }.takeIf { it >= 0 } ?: -1)
    }

    /** Returns null at queue end instead of silently wrapping with repeat off. */
    fun forward(automatic: Boolean = false): PlaybackQueue? {
        if (entries.isEmpty() || currentIndex !in entries.indices) return null
        if (automatic && repeat == 2) return this
        if (shuffle && entries.size > 1) {
            val nextState = randomState * 6364136223846793005L + 1442695040888963407L
            // Avoid looping back to the same track when shuffle is enabled.
            val offset = java.lang.Long.remainderUnsigned(nextState, (entries.size - 1).toLong()).toInt() + 1
            return copy(currentIndex = (currentIndex + offset) % entries.size,
                randomState = nextState)
        }
        val n = currentIndex + 1
        if (n < entries.size) return copy(currentIndex = n)
        return if (repeat == 1) copy(currentIndex = 0) else null
    }

    fun backward(): PlaybackQueue? {
        if (entries.isEmpty() || currentIndex !in entries.indices) return null
        if (shuffle && entries.size > 1) {
            val seed = randomState * 2862933555777941757L + 3037000493L
            val offset = java.lang.Long.remainderUnsigned(seed, (entries.size - 1).toLong()).toInt() + 1
            return copy(currentIndex = (currentIndex + offset) % entries.size,
                randomState = seed)
        }
        val n = currentIndex - 1
        return when {
            n >= 0 -> copy(currentIndex = n)
            repeat == 1 -> copy(currentIndex = entries.lastIndex)
            else -> copy(currentIndex = 0)
        }
    }

    fun add(item: UnifiedSong): PlaybackQueue =
        if (entries.isEmpty()) copy(entries = listOf(item), currentIndex = 0)
        else copy(entries = entries + item)

    fun move(from: Int, to: Int): PlaybackQueue {
        if (from !in entries.indices || to !in entries.indices || from == to) return this
        val order = entries.toMutableList()
        val activeItem = currentIndex
        val moved = order.removeAt(from)
        order.add(to, moved)
        val newIndex = when {
            activeItem == from -> to
            from < activeItem && to >= activeItem -> activeItem - 1
            from > activeItem && to <= activeItem -> activeItem + 1
            else -> activeItem
        }
        return copy(entries = order, currentIndex = newIndex)
    }

    fun remove(at: Int): PlaybackQueue {
        if (at !in entries.indices) return this
        val result = entries.toMutableList().also { it.removeAt(at) }
        return copy(entries = result, currentIndex = when {
            result.isEmpty() -> -1
            at < currentIndex -> currentIndex - 1
            at == currentIndex -> at.coerceAtMost(result.lastIndex)
            else -> currentIndex
        })
    }

    fun withRepeat(mode: Int): PlaybackQueue = copy(repeat = mode.coerceIn(0, 2))
    fun withShuffle(enabled: Boolean): PlaybackQueue = copy(shuffle = enabled)
}
