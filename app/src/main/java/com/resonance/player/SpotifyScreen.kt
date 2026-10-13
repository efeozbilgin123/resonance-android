package com.resonance.player

import android.app.Activity
import androidx.compose.runtime.Composable

/** Spotify account is presented as a library, not a second settings/search app. */
@Composable
fun SpotifyScreen(
    activity: Activity,
    onExternalPlay: (ExternalPlaylistItem) -> Unit = {},
    onPremiumPlay: (SpotifyTrack) -> Unit = {},
    onPlayQueue: (UnifiedSong, List<UnifiedSong>) -> Unit = { _, _ -> }
) {
    LibraryHomeScreen(activity, onExternalPlay, onPremiumPlay, onPlayQueue)
}
