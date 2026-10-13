package com.resonance.player

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Intentional split:
 *  - spotifyTrack => write to Spotify's own playlist via Spotify Web API
 *  - external => store in Resonance only, using Spotify account + playlist as key
 * NEVER pass external/audio URIs to Spotify.
 */
@Composable
fun SpotifyPlaylistPicker(
    activity: Activity,
    external: ExternalPlaylistItem? = null,
    spotifyTrack: SpotifyTrack? = null,
    onDismiss: () -> Unit
) {
    require((external == null) != (spotifyTrack == null)) { "Select exactly one item source" }
    val client = remember { SpotifyClient(activity) }
    val store = remember { HybridPlaylistStore(activity) }
    val scope = rememberCoroutineScope()
    var accountId by remember { mutableStateOf("") }
    var playlists by remember { mutableStateOf(emptyList<SpotifyPlaylist>()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    fun insert(playlist: SpotifyPlaylist) {
        if (saving) return
        saving = true
        error = ""
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (!playlist.editable) error("Bu liste düzenlenemez; sana ait veya ortak liste seç.")
                    if (external != null) {
                        if (store.add(accountId, playlist.id, external))
                            "Resonance eklerine kaydedildi (yalnızca bu cihazda; Spotify'a gönderilmedi)."
                        else "Bu parça zaten Resonance eklerinde."
                    } else {
                        client.addSpotifyTrack(playlist.id, spotifyTrack!!.uri)
                        "Spotify parçan Spotify hesabındaki listeye kaydedildi."
                    }
                }
                Toast.makeText(activity, result, Toast.LENGTH_LONG).show()
                onDismiss()
            } catch (e: Exception) {
                error = e.localizedMessage ?: "Kaydetme hatası"
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!client.authorized) {
            loading = false
            error = "Önce üstteki Spotify hesabı ekranından giriş yap."
            return@LaunchedEffect
        }
        try {
            withContext(Dispatchers.IO) {
                val account = client.ensureAccountId()
                val available = client.playlists()
                account to available
            }.let { (account, available) ->
                accountId = account
                playlists = available
            }
        } catch (e: Exception) {
            error = e.localizedMessage ?: "Spotify listeleri yüklenemedi"
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (external != null) "Resonance listesine ekle" else "Spotify listesine kaydet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (external != null)
                    "Dış kaynak parçası Spotify'a yüklenemez. Aynı liste altında yalnızca Resonance'ta saklanır."
                    else "Bu Spotify şarkısı doğrudan Spotify hesabındaki seçili listeye yazılır.",
                    style = MaterialTheme.typography.bodySmall)
                if (loading || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (!loading) {
                    OutlinedTextField(newName, onValueChange = { newName = it },
                        label = { Text("Yeni Spotify listesi oluştur") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !saving && newName.isNotBlank(), onClick = {
                        saving = true
                        scope.launch {
                            try {
                                val created = withContext(Dispatchers.IO) {
                                    client.createPlaylist(newName)
                                }
                                playlists = listOf(created) + playlists
                                newName = ""
                            } catch (e: Exception) {
                                error = e.localizedMessage ?: "Liste oluşturulamadı"
                            } finally { saving = false }
                        }
                    }) { Text("Yeni liste oluştur") }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        TextButton(onClick = { insert(playlist) },
                            enabled = playlist.editable && !saving,
                            modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.LibraryAdd, null)
                            Spacer(Modifier.width(8.dp))
                            Text(playlist.name, modifier = Modifier.weight(1f), maxLines = 2)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Vazgeç") }
        }
    )
}
