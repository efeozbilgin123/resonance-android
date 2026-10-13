package com.resonance.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.sp

private val lime = Color(0xFFC6F17B)
private val bg = Color(0xFF080B0A)
private val panel = Color(0xFF171D19)
private val muted = Color(0xFF9BA39B)
private data class Song(val uri: Uri, val title: String, val artist: String)

class MainActivity : ComponentActivity() {
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storage = getSharedPreferences("tracks", Context.MODE_PRIVATE)
        setContent {
            var songs by remember {
                mutableStateOf(storage.getStringSet("uris", emptySet()).orEmpty()
                    .mapNotNull { runCatching { song(Uri.parse(it)) }.getOrNull() })
            }
            var selected by remember { mutableStateOf<Song?>(null) }
            var playing by remember { mutableStateOf(false) }
            var page by remember { mutableStateOf(0) }
            var search by remember { mutableStateOf("") }
            var favorites by remember { mutableStateOf(storage.getStringSet("favorites", emptySet()).orEmpty()) }
            var error by remember { mutableStateOf<String?>(null) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { chosen ->
                chosen.forEach { uri ->
                    try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                }
                songs = (songs + chosen.mapNotNull { runCatching { song(it) }.getOrNull() }).distinctBy { it.uri }
                storage.edit().putStringSet("uris", songs.map { it.uri.toString() }.toSet()).apply()
            }
            val startSong: (Song) -> Unit = { track ->
                runCatching {
                    player?.release()
                    player = MediaPlayer().apply {
                        setDataSource(this@MainActivity, track.uri)
                        setOnPreparedListener { it.start(); playing = true }
                        setOnCompletionListener { playing = false }
                        prepareAsync()
                    }
                    selected = track
                    playing = false
                    error = null
                }.onFailure { error = it.localizedMessage }
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = lime, background = bg, surface = panel)) {
                Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("RESONANCE", color = lime, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { page = 3 }) { Text("Spotify", color = lime) }
                        IconButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                            Icon(Icons.Rounded.LibraryAdd, contentDescription = "Müzik içe aktar", tint = lime)
                        }
                    }
                    if (page == 3) {
                        SpotifyScreen(this@MainActivity)
                    } else {
                    Text(
                        when(page) { 0 -> "Müzik senin dünyan."; 1 -> "Keşfet"; else -> "Kütüphanen" },
                        Modifier.padding(horizontal = 22.dp), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    if (page == 1) {
                        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                            singleLine = true, label = { Text("Yerel şarkılarında ara") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) })
                    }
                    if (songs.isEmpty()) {
                        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Headphones, null, tint = lime, modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("İlk parçanı ekle", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("Telefonundaki müzik dosyalarını seç.", color = muted)
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Müzik ekle") }
                        }
                    } else {
                        Text(
                            if (page == 2) "Beğendiklerin ve tüm parçalar" else "Yerel koleksiyon",
                            Modifier.padding(horizontal = 22.dp, vertical = 12.dp), color = muted, fontSize = 13.sp
                        )
                        val visible = songs.filter {
                            (page != 1 || it.title.contains(search, true) || it.artist.contains(search, true)) &&
                            (page != 2 || search != "favorites" || it.uri.toString() in favorites)
                        }
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                            items(visible) { track ->
                                Row(
                                    Modifier.fillMaxWidth().padding(bottom = 7.dp)
                                        .background(panel, RoundedCornerShape(14.dp))
                                        .clickable { startSong(track) }.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.GraphicEq, null, tint = lime, modifier = Modifier.size(38.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, fontWeight = FontWeight.Bold)
                                        Text(track.artist, color = muted, fontSize = 12.sp, maxLines = 1)
                                    }
                                    IconButton(onClick = {
                                        val key = track.uri.toString()
                                        favorites = if(key in favorites) favorites - key else favorites + key
                                        storage.edit().putStringSet("favorites", favorites).apply()
                                    }) {
                                        Icon(if(track.uri.toString() in favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                            null, tint = if(track.uri.toString() in favorites) lime else muted)
                                    }
                                }
                            }
                        }
                    }
                    if (error != null) Text("Oynatma hatası: $error", Modifier.padding(12.dp), color = Color.Red)
                    selected?.let { track ->
                        Column(Modifier.fillMaxWidth().background(panel).padding(horizontal = 16.dp, vertical = 9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.MusicNote, null, tint = lime)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(track.artist, color = muted, fontSize = 12.sp, maxLines = 1)
                                }
                                IconButton(onClick = {
                                    runCatching {
                                        player?.let { if (it.isPlaying) it.pause() else it.start(); playing = it.isPlaying }
                                    }.onFailure { error = it.localizedMessage }
                                }) { Icon(if(playing) Icons.Rounded.PauseCircleFilled else Icons.Rounded.PlayCircleFilled, null, tint = lime) }
                            }
                        }
                    }
                    NavigationBar(containerColor = panel) {
                        listOf(Triple("Ana sayfa", Icons.Rounded.Home, 0),Triple("Ara", Icons.Rounded.Search, 1), Triple("Kütüphane", Icons.Rounded.LibraryMusic, 2)).forEach { (label, icon, n) ->
                            NavigationBarItem(selected = page == n, onClick = { page = n }, icon = { Icon(icon, null) }, label = { Text(label) })
                        }
                    }
                    }
                }
            }
        }
    }

    private fun song(uri: Uri): Song {
        var title = uri.lastPathSegment?.substringAfterLast('/') ?: "Bilinmeyen parça"
        var artist = "Bilinmeyen sanatçı"
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(this, uri)
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: title
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: artist
            }
        }
        return Song(uri, title, artist)
    }
    override fun onDestroy() { player?.release(); super.onDestroy() }
}
