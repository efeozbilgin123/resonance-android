package com.resonance.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spotube-like *metadata matching*, not Spotify audio extraction.
 * Results are explicitly labelled Audius recordings. The user chooses which
 * recording to play; there is no automatic substitution or false match.
 */
@Composable
fun SourceMatchDialog(
    original: SpotifyTrack,
    onPlay: (ExternalPlaylistItem) -> Unit,
    onAdd: (ExternalPlaylistItem) -> Unit,
    onDismiss: () -> Unit
) {
    var loading by remember(original.uri) { mutableStateOf(true) }
    var results by remember(original.uri) { mutableStateOf<List<MusicMatch>>(emptyList()) }
    var problem by remember(original.uri) { mutableStateOf("") }

    LaunchedEffect(original.uri) {
        loading = true
        problem = ""
        try {
            results = withContext(Dispatchers.IO) {
                MusicMatcher.suggestions(original.title, original.artist,
                    original.durationSeconds.takeIf { it > 0 })
            }
        } catch (e: Exception) {
            problem = e.localizedMessage ?: "Audius eşleştirmesi başarısız"
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bağımsız kaynaktaki eşleşmeler") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${original.title} — ${original.artist}",
                    style = MaterialTheme.typography.titleMedium)
                Text("Bunlar Spotify'ın ses kayıtları değil, Audius'ta bulunan olası karşılıklar. Farklı sürüm veya yanlış şarkı çıkabilir. Oynatacağın kaydı kendin seç.",
                    style = MaterialTheme.typography.bodySmall)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (problem.isNotBlank()) Text(problem, color = MaterialTheme.colorScheme.error)
                if (!loading && results.isEmpty() && problem.isBlank()) {
                    Text("Bu parça için Audius'ta bir aday bulunamadı.")
                }
                LazyColumn(Modifier.heightIn(max = 390.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results, key = { it.candidate.id }) { match ->
                        val track = match.candidate
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(track.title, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2)
                                Text("${track.artist} · ${match.label} (${(match.score*100).toInt()}%)",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = {
                                onPlay(ExternalPlaylistItem.fromAudius(track))
                                onDismiss()
                            }) {
                                Icon(Icons.Rounded.PlayArrow, "Audius sürümünü çal")
                            }
                            IconButton(onClick = {
                                onAdd(ExternalPlaylistItem.fromAudius(track))
                                onDismiss()
                            }) {
                                Icon(Icons.Rounded.PlaylistAdd, "Resonance listesine ekle")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Kapat") }
        }
    )
}
