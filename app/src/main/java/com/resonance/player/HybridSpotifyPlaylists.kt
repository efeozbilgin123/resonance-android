package com.resonance.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLEncoder

private val resonanceAccent = Color(0xFFC6F17B)
private val resonancePanel = Color(0xFF171D19)

/**
 * Spotify playlists are REMOTE metadata; external tracks are a locally stored
 * separate section keyed by stable account ID + Spotify playlist ID.
 * Does not cross-stream Spotify Content or upload external files to Spotify.
 */
@Composable
fun HybridSpotifyPlaylists(
    activity: Activity,
    onExternalPlay: (ExternalPlaylistItem) -> Unit,
    onPremiumPlay: (SpotifyTrack) -> Unit,
    onPlayQueue: (UnifiedSong, List<UnifiedSong>) -> Unit,
    modifier: Modifier = Modifier
) {
    val client = remember { SpotifyClient(activity) }
    val local = remember { HybridPlaylistStore(activity) }
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(client.authorized) }
    var clientId by remember { mutableStateOf(client.clientId) }
    var accountId by remember { mutableStateOf(client.cachedAccountId) }
    var selected by remember { mutableStateOf<SpotifyPlaylist?>(null) }
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var spotifyRows by remember { mutableStateOf<List<SpotifyTrack>>(emptyList()) }
    var externalRows by remember { mutableStateOf<List<ExternalPlaylistItem>>(emptyList()) }
    var localOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var found by remember { mutableStateOf<List<SpotifyTrack>>(emptyList()) }
    var mode by remember { mutableStateOf("playlists") }
    var search by remember { mutableStateOf("") }
    var playlistName by remember { mutableStateOf("") }
    var pendingSpotify by remember { mutableStateOf<SpotifyTrack?>(null) }
    var matching by remember { mutableStateOf<SpotifyTrack?>(null) }
    var pendingExternal by remember { mutableStateOf<ExternalPlaylistItem?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }

    fun runTask(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = ""
            try { block() } catch (e: Exception) {
                message = e.localizedMessage ?: "Spotify işlemi başarısız"
            } finally { busy = false }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runTask {
                val data = withContext(Dispatchers.IO) { local.exportAccount(accountId) }
                withContext(Dispatchers.IO) {
                    val stream = activity.contentResolver.openOutputStream(uri)
                        ?: kotlin.error("Dosyaya yazılamıyor")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(data) }
                }
                message = "Resonance ekleri yedeklendi. Spotify hesabına yüklenmedi."
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runTask {
                val count = withContext(Dispatchers.IO) {
                    val input = activity.contentResolver.openInputStream(uri)
                        ?: kotlin.error("Yedek dosyası okunamadı")
                    val data = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    local.importAccount(accountId, data)
                }
                reload++
                message = "$count Resonance eki bu cihazdaki listelere aktarıldı."
            }
        }
    }

    fun playOnSpotify(track: SpotifyTrack) {
        if (track.local || !track.uri.startsWith("spotify:track:")) {
            message = "Bu yerel Spotify kaydı yalnızca onu bulunduran cihazda oynatılabilir."
            return
        }
        runTask {
            withContext(Dispatchers.IO) {
                client.put("/me/player/play",
                    org.json.JSONObject().put("uris", JSONArray().put(track.uri)))
            }
            message = "Spotify Connect komutu gönderildi. Aktif Spotify cihazı ve Premium gerektirir."
        }
    }

    fun showSpotifyLink(track: SpotifyTrack) {
        val id = track.uri.substringAfterLast(':')
        if (!id.matches(Regex("[A-Za-z0-9]+"))) return
        activity.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://open.spotify.com/track/$id")))
    }

    LaunchedEffect(connected, reload) {
        if (!connected) return@LaunchedEffect
        busy = true
        try {
            val result = withContext(Dispatchers.IO) {
                client.ensureAccountId() to client.playlists()
            }
            accountId = result.first
            playlists = result.second
        } catch (e: Exception) {
            message = "Spotify listeleri: ${e.localizedMessage ?: "Bağlantı hatası"}"
        } finally { busy = false }
    }

    LaunchedEffect(selected?.id, accountId, reload) {
        val playlist = selected ?: return@LaunchedEffect
        if (accountId.isBlank()) return@LaunchedEffect
        externalRows = local.entries(accountId, playlist.id)
        localOrder = local.readOrder(accountId, playlist.id)
        busy = true
        try {
            spotifyRows = withContext(Dispatchers.IO) { client.playlistTracks(playlist.id) }
        } catch (e: Exception) {
            message = "Spotify parçaları alınamadı: ${e.localizedMessage ?: "Yetki hatası"}"
            spotifyRows = emptyList()
        } finally { busy = false }
    }

    val merged = remember(spotifyRows, externalRows, localOrder) {
        HybridPlaylistOrder.merge(spotifyRows, externalRows, localOrder)
    }

    fun playMixed(row: HybridPlaylistRow) {
        val target = row.unified()
        if (target == null) {
            message = "Bu Spotify yerel dosyası başka cihazda oynatılamaz."
            return
        }
        onPlayQueue(target, merged.mapNotNull { it.unified() })
    }

    Column(modifier.fillMaxWidth().background(Color(0xFF080B0A)).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (!connected) {
            Text("Spotify çalma listelerin", color = Color.White,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Spotify listeleri ayrı, harici parçalar yalnızca Resonance'ta saklanır.",
                style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            OutlinedTextField(clientId, { clientId = it }, label = { Text("Spotify Client ID") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("Redirect URI: http://127.0.0.1:43892/callback",
                color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy && clientId.isNotBlank(), onClick = {
                client.clientId = clientId
                busy = true
                client.login { failure ->
                    busy = false
                    if (failure != null) message = failure else {
                        connected = true
                        reload++
                    }
                }
            }) { Text("Spotify hesabını bağla") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LibraryMusic, null, tint = resonanceAccent)
                Spacer(Modifier.width(8.dp))
                Text(if (selected == null) "Spotify listelerim" else selected!!.name,
                    style = MaterialTheme.typography.titleLarge, color = Color.White,
                    maxLines = 1, modifier = Modifier.weight(1f))
                if (selected != null) {
                    TextButton(onClick = { selected = null; mode = "playlists" }) { Text("Geri") }
                } else {
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Rounded.Refresh, "Yenile", tint = resonanceAccent)
                    }
                }
            }
            Text("Spotify müziği Spotify Connect'te çalar. Audius/yerel kayıtları Resonance çalar.",
                color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("playlists" to "Listeler", "search" to "Spotify ara").forEach { (id, label) ->
                    FilterChip(selected = mode == id, label = { Text(label) }, onClick = {
                        mode = id
                        selected = null
                    })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { exportLauncher.launch("Resonance-liste-yedegi.json") },
                    enabled = accountId.isNotBlank() && !busy) {
                    Text("Resonance eklerini yedekle")
                }
                TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                    enabled = accountId.isNotBlank() && !busy) {
                    Text("Yedeği yükle")
                }
            }
            if (mode == "search") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(search, { search = it }, Modifier.weight(1f), singleLine = true,
                        label = { Text("Spotify sanatçı / şarkı") })
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !busy && search.isNotBlank(), onClick = {
                        runTask {
                            val query = URLEncoder.encode(search.trim(), "UTF-8")
                            val data = withContext(Dispatchers.IO) {
                                client.get("/search?q=$query&type=track&limit=30")
                            }
                            val arr = data.optJSONObject("tracks")?.optJSONArray("items") ?: JSONArray()
                            found = (0 until arr.length()).mapNotNull { i ->
                                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                                val uri = item.optString("uri")
                                if (!uri.startsWith("spotify:track:")) return@mapNotNull null
                                SpotifyTrack(uri, item.optString("name"),
                                    item.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: "",
                                    false, item.optInt("duration_ms") / 1000)
                            }
                        }
                    }) { Text("Ara") }
                }
            }
            if (selected == null && mode == "playlists") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(playlistName, { playlistName = it },
                        Modifier.weight(1f), singleLine = true,
                        label = { Text("Yeni Spotify çalma listesi") })
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !busy && playlistName.isNotBlank(), onClick = {
                        runTask {
                            val created = withContext(Dispatchers.IO) {
                                client.createPlaylist(playlistName)
                            }
                            playlists = listOf(created) + playlists
                            playlistName = ""
                            message = "Yeni çalma listesi Spotify hesabına kaydedildi."
                        }
                    }) { Text("Oluştur") }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) {
            Text(message, color = if (message.contains("kaydedildi") || message.contains("komutu"))
                resonanceAccent else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        if (connected) {
            LazyColumn(Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 20.dp)) {
                val detail = selected
                if (mode == "search") {
                    item {
                        Text("Spotify kataloğundaki şarkılar", fontWeight = FontWeight.Bold,
                            color = Color.White)
                    }
                    items(found) { track ->
                        Row(Modifier.fillMaxWidth().background(resonancePanel,
                            RoundedCornerShape(12.dp)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(track.title, color = Color.White, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(track.artist, color = Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { onPremiumPlay(track) }) {
                                    Text("Premium çal")
                                }
                            }
                            IconButton(onClick = { matching = track }) {
                                Icon(Icons.Rounded.Search,
                                    "Audius'ta benzer kayıtları ara", tint = resonanceAccent)
                            }
                            IconButton(onClick = { pendingSpotify = track }) {
                                Icon(Icons.Rounded.PlaylistAdd,
                                    "Gerçek Spotify listesine kaydet", tint = resonanceAccent)
                            }
                        }
                    }
                } else if (detail == null) {
                    items(playlists, key = { it.id }) { playlist ->
                        Row(Modifier.fillMaxWidth().background(resonancePanel,
                            RoundedCornerShape(12.dp)).clickable {
                            selected = playlist
                        }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.QueueMusic, null, tint = resonanceAccent)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(playlist.name, fontWeight = FontWeight.SemiBold,
                                    color = Color.White, maxLines = 2)
                                Text(if (playlist.editable) "Düzenlenebilir Spotify listesi"
                                    else "Yalnızca görüntülenebilir", color = Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = resonanceAccent)
                        }
                    }
                } else {
                    item {
                        Text("Karma çalma listen", color = resonanceAccent,
                            fontWeight = FontWeight.Bold)
                        Text("Spotify parçaları hesapta, YouTube/Audius/yerel parçalar "
                                + "Resonance'ta saklanır. Buradaki sıralama yalnızca Resonance içindir.",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = {
                            merged.firstOrNull { it.unified() != null }?.let { playMixed(it) }
                        }, enabled = merged.any { it.unified() != null },
                            modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Karma listeyi sırayla çal (${merged.size} parça)")
                        }
                    }
                    if (merged.isEmpty()) {
                        item {
                            Text("Bu listede henüz parça bulunamadı. Ara sekmesinden "
                                    + "Spotify, YouTube Music veya Audius şarkıları ekleyebilirsin.",
                                color = Color.LightGray,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    itemsIndexed(merged, key = { _, row -> row.key }) { index, row ->
                        Row(Modifier.fillMaxWidth().background(resonancePanel,
                            RoundedCornerShape(12.dp)).clickable { playMixed(row) }
                            .padding(horizontal = 7.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("${index+1}", color = Color.LightGray,
                                style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.title, color = Color.White, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(row.artist, color = Color.LightGray, maxLines = 1,
                                    style = MaterialTheme.typography.bodySmall)
                                Text(row.origin + if (row.spotify == null)
                                    " · yalnız Resonance" else " · Spotify hesabında",
                                    color = resonanceAccent,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1)
                            }
                            IconButton(onClick = {
                                val newOrder = HybridPlaylistOrder.swap(merged,index,index-1)
                                local.saveOrder(accountId, detail.id, newOrder)
                                localOrder = newOrder
                            }, enabled = index > 0, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.KeyboardArrowUp,
                                    "Yukarı taşı", tint = Color.LightGray)
                            }
                            IconButton(onClick = {
                                val newOrder = HybridPlaylistOrder.swap(merged,index,index+1)
                                local.saveOrder(accountId, detail.id, newOrder)
                                localOrder = newOrder
                            }, enabled = index < merged.lastIndex,
                                modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.KeyboardArrowDown,
                                    "Aşağı taşı", tint = Color.LightGray)
                            }
                            if (row.external != null) {
                                IconButton(onClick = {
                                    local.remove(accountId, detail.id, row.external)
                                    externalRows = local.entries(accountId, detail.id)
                                }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.Close,
                                        "Resonance ekini kaldır", tint = Color.LightGray)
                                }
                            } else {
                                IconButton(onClick = {
                                    row.spotify?.let { matching = it }
                                }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.Search,
                                        "Benzer dış kaynak bul", tint = Color.LightGray)
                                }
                            }
                        }
                    }
                    item {
                        Text("İstediğin şarkıyı ana aramada bulup + simgesiyle ekleyebilirsin. "
                                + "YouTube kaynakları gayriresmîdir; bazı kayıtlar açılamayabilir.",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            TextButton(onClick = {
                client.logout()
                connected = false
                accountId = ""
                playlists = emptyList()
                selected = null
            }, enabled = !busy) { Text("Spotify izinlerini yenile / hesap değiştir") }
        }
    }
    matching?.let { track ->
        SourceMatchDialog(
            original = track,
            onPlay = onExternalPlay,
            onAdd = { pendingExternal = it },
            onDismiss = { matching = null }
        )
    }
    pendingExternal?.let { entry ->
        SpotifyPlaylistPicker(activity, external = entry, onDismiss = {
            pendingExternal = null
            reload++
        })
    }
    pendingSpotify?.let { track ->
        SpotifyPlaylistPicker(activity, spotifyTrack = track, onDismiss = {
            pendingSpotify = null
            reload++
        })
    }
}
