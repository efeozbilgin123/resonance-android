package com.resonance.player

import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private val accent = Color(0xFFC6F17B)
private val dark = Color(0xFF090E0B)
private val surface = Color(0xFF19221B)

private data class SpotifyRow(val name: String, val sub: String, val uri: String, val detail: String = "")

@Composable
fun SpotifyScreen(
    activity: Activity,
    onExternalPlay: (ExternalPlaylistItem) -> Unit = {},
    onPremiumPlay: (SpotifyTrack) -> Unit = {},
    onPlayQueue: (UnifiedSong, List<UnifiedSong>) -> Unit = { _, _ -> }
) {
    var showHybrid by remember { mutableStateOf(true) }
    if (showHybrid) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { showHybrid = false }) {
                Text("Spotify Connect kontrolleri (aktif Spotify cihazı gerektirir)")
            }
            HybridSpotifyPlaylists(activity, onExternalPlay, onPremiumPlay, onPlayQueue, Modifier.weight(1f))
        }
        return
    }
    val client = remember { SpotifyClient(activity) }
    var id by remember { mutableStateOf(client.clientId) }
    var connected by remember { mutableStateOf(client.authorized) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("playlists") }
    var keyword by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(emptyList<SpotifyRow>()) }
    var current by remember { mutableStateOf("Henüz oynatılan parça yok") }
    var deviceName by remember { mutableStateOf("") }

    fun work(task: () -> Unit) {
        busy = true
        error = ""
        Thread {
            try { task() } catch(e: Exception) { activity.runOnUiThread { error = e.message ?: "Spotify hatası" } }
            finally { activity.runOnUiThread { busy = false } }
        }.start()
    }
    fun refresh(kind: String) {
        mode = kind
        work {
            val result: List<SpotifyRow> = when(kind) {
                "search" -> {
                    val q = URLEncoder.encode(keyword.trim(), "UTF-8")
                    if(q.isBlank()) emptyList() else {
                        val response = client.get("/search?q=$q&type=track&limit=10")
                        val tracks = response.optJSONObject("tracks")?.optJSONArray("items") ?: JSONArray()
                        (0 until tracks.length()).mapNotNull { i ->
                            tracks.optJSONObject(i)?.let { t ->
                                SpotifyRow(t.optString("name"), t.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: "",
                                    t.optString("uri"))
                            }
                        }
                    }
                }
                "tracks" -> {
                    val arr = client.get("/me/tracks?limit=20").optJSONArray("items") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optJSONObject("track")?.let { t ->
                            SpotifyRow(t.optString("name"),
                                t.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: "", t.optString("uri"))
                        }
                    }
                }
                else -> {
                    val arr = client.get("/me/playlists?limit=20").optJSONArray("items") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { p ->
                            SpotifyRow(p.optString("name"), "Spotify çalma listesi", p.optString("uri"), "playlist")
                        }
                    }
                }
            }
            activity.runOnUiThread { rows = result }
        }
    }
    Column(Modifier.fillMaxSize().background(dark).padding(18.dp)) {
        TextButton(onClick = { showHybrid = true }) { Text("← Spotify listelerim") }
        Text("SPOTIFY CONNECT · HARİCİ OYNATICI KONTROLÜ", color = accent, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            Text("Spotify Developer Dashboard'dan Client ID al ve aşağıya gir.", color = Color.White)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(id, { id = it }, label = { Text("Spotify Client ID") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true)
            Spacer(Modifier.height(8.dp))
            Text("Dashboard → Redirect URIs: http://127.0.0.1:43892/callback", color = Color.LightGray)
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                client.clientId = id
                busy = true
                client.login { issue ->
                    busy = false
                    if (issue != null) error = issue else {
                        connected = true
                        refresh("playlists")
                    }
                }
            }, enabled = !busy && id.isNotBlank()) { Text("Spotify ile giriş yap") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Spotify bağlı", color = accent, modifier = Modifier.weight(1f))
                TextButton(onClick = { client.logout(); connected = false; rows = emptyList() }) { Text("Çıkış") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("playlists" to "Listeler", "tracks" to "Beğenilen", "search" to "Ara")
                    .forEach { (key, title) ->
                        OutlinedButton(onClick = { refresh(key) }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text(title)
                        }
                    }
            }
            if(mode == "search") {
                OutlinedTextField(keyword, { keyword = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Spotify kataloğunda ara") }, singleLine = true)
                TextButton(onClick = { refresh("search") }) { Text("Ara") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Spotify oynatıcı", color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    work {
                        val p = client.get("/me/player")
                        val item = p.optJSONObject("item")
                        val artist = item?.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: ""
                        val title = item?.optString("name") ?: "Çalma yok"
                        val device = p.optJSONObject("device")?.optString("name") ?: "Aktif cihaz bulunamadı"
                        activity.runOnUiThread { current = "$title • $artist"; deviceName = device }
                    }
                }) { Text("Yenile") }
            }
            Text(current, color = accent, maxLines = 2)
            Text("Cihaz: $deviceName", color = Color.LightGray)
            Row {
                TextButton(onClick = { work { client.post("/me/player/previous"); refreshState(activity, client) { current = it } } }) { Text("⏮") }
                TextButton(onClick = { work { client.put("/me/player/play"); refreshState(activity, client) { current = it } } }) { Text("▶") }
                TextButton(onClick = { work { client.put("/me/player/pause"); refreshState(activity, client) { current = it } } }) { Text("⏸") }
                TextButton(onClick = { work { client.post("/me/player/next"); refreshState(activity, client) { current = it } } }) { Text("⏭") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(contentPadding = PaddingValues(vertical = 10.dp)) {
                items(rows) { entry ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .background(surface, RoundedCornerShape(12.dp))
                        .clickable {
                            work {
                                val uri = entry.uri
                                if (uri.isBlank()) error("Bu şarkı için URI bulunamadı")
                                val body = if (entry.detail == "playlist")
                                    JSONObject().put("context_uri", uri)
                                else JSONObject().put("uris", JSONArray().put(uri))
                                client.put("/me/player/play", body)
                                refreshState(activity, client) { current = it }
                            }
                        }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(entry.sub, color = Color.LightGray, maxLines = 1)
                        }
                        Text("▶", color = accent)
                    }
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF8A80), modifier = Modifier.padding(vertical = 8.dp))
    }
}

private fun refreshState(activity: Activity, client: SpotifyClient, set: (String) -> Unit) {
    val p = client.get("/me/player")
    val item = p.optJSONObject("item")
    activity.runOnUiThread { set(item?.optString("name") ?: "Oynatılıyor") }
}
