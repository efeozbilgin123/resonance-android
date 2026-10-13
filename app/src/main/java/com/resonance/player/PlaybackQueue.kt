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
    val randomState: Long = 1L,
    val visited: Set<Int> = emptySet(),
    val history: List<Int> = emptyList()
) {
    val current: UnifiedSong? get() = entries.getOrNull(currentIndex)

    fun start(items: List<UnifiedSong>, selectedKey: String): PlaybackQueue {
        val tracks = items.ifEmpty { emptyList() }
        return copy(entries = tracks, currentIndex = tracks.indexOfFirst {
            it.stableKey == selectedKey
        }.takeIf { it >= 0 } ?: -1, visited = emptySet(), history = emptyList())
    }

    /** Returns null at queue end instead of silently wrapping with repeat off. */
    fun forward(automatic: Boolean = false): PlaybackQueue? {
        if (entries.isEmpty() || currentIndex !in entries.indices) return null
        if (automatic && repeat == 2) return this
        if (shuffle) {
            val heard = visited + currentIndex
            val available = entries.indices.filter { it !in heard }
            if (available.isEmpty() && repeat != 1) return null
            val candidates = if (available.isEmpty())
                entries.indices.filter { it != currentIndex } else available
            if (candidates.isEmpty()) return if (repeat == 1) this else null
            val nextState = randomState * 6364136223846793005L + 1442695040888963407L
            val index = java.lang.Long.remainderUnsigned(
                nextState, candidates.size.toLong()).toInt()
            return copy(currentIndex = candidates[index],
                randomState = nextState, visited = if (available.isEmpty())
                    setOf(currentIndex) else heard, history = history + currentIndex)
        }
        val n = currentIndex + 1
        if (n < entries.size) return copy(currentIndex = n)
        return if (repeat > 0) copy(currentIndex = 0) else null
    }

    fun backward(): PlaybackQueue? {
        if (entries.isEmpty() || currentIndex !in entries.indices) return null
        if (shuffle && history.isNotEmpty()) {
            val previous = history.last()
            return copy(currentIndex = previous, history = history.dropLast(1),
                visited = visited - previous)
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
        val indices = entries.indices.toMutableList().apply {
            val movedIndex = removeAt(from)
            add(to, movedIndex)
        }
        return copy(entries = order, currentIndex = newIndex,
            visited = visited.map { indices.indexOf(it) }.filter { it >= 0 }.toSet(),
            history = history.map { indices.indexOf(it) }.filter { it >= 0 })
    }

    fun remove(at: Int): PlaybackQueue {
        if (at !in entries.indices) return this
        val result = entries.toMutableList().also { it.removeAt(at) }
        return copy(entries = result,
            visited = visited.mapNotNull { index -> when {
                index == at -> null
                index > at -> index - 1
                else -> index
            } }.toSet(),
            history = history.mapNotNull { index -> when {
                index == at -> null
                index > at -> index - 1
                else -> index
            } },
            currentIndex = when {
            result.isEmpty() -> -1
            at < currentIndex -> currentIndex - 1
            at == currentIndex -> at.coerceAtMost(result.lastIndex)
            else -> currentIndex
        })
    }

    fun withRepeat(mode: Int): PlaybackQueue = copy(repeat = mode.coerceIn(0, 2))
    fun withShuffle(enabled: Boolean): PlaybackQueue = copy(
        shuffle = enabled, visited = emptySet(), history = emptyList())
}
