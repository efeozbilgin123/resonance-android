package com.resonance.player

import android.app.Activity
import coil.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val accentGreen = Color(0xFFC6F17B)
private val tile = Color(0xFF171D19)

@Composable
fun AudiusScreen(activity: Activity, onPlay: (AudiusTrack, List<AudiusTrack>) -> Unit,
    modifier: Modifier = Modifier,
    onAddExternal: (ExternalPlaylistItem) -> Unit = {}
) {
    val prefs = remember { activity.getSharedPreferences("audius_favorites", Context.MODE_PRIVATE) }
    var favoritesRaw by remember { mutableStateOf(prefs.getStringSet("tracks", emptySet()).orEmpty().toSet()) }
    val favorites = remember(favoritesRaw) {
        favoritesRaw.mapNotNull { runCatching { AudiusTrack.fromJson(it) }.getOrNull() }
    }
    var mode by remember { mutableStateOf("Trendler") }
    var typed by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<AudiusTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mode, searched, favoritesRaw) {
        if (mode == "Beğenilenler" && searched.isBlank()) {
            tracks = favorites
            loading = false
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val genre = when (mode) {
                "Elektronik" -> "Electronic"
                "Hip-Hop" -> "Hip-Hop/Rap"
                "Pop" -> "Pop"
                "Rock" -> "Rock"
                else -> ""
            }
            tracks = withContext(Dispatchers.IO) { AudiusCatalog.fetch(searched, genre) }
        } catch (e: Exception) {
            tracks = emptyList()
            error = "Parçalar yüklenemedi: ${e.localizedMessage ?: "Bağlantı hatası"}"
        } finally {
            loading = false
        }
    }

    Column(modifier.fillMaxWidth().background(Color(0xFF080B0A))) {
        Text("Müziği keşfet", Modifier.padding(horizontal = 22.dp), color = Color.White,
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Text("Audius · Gerçek şarkılar, doğrudan Resonance içinde",
            Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Trendler", "Elektronik", "Hip-Hop", "Pop", "Rock", "Beğenilenler").forEach { genre ->
                FilterChip(selected = mode == genre, onClick = {
                    mode = genre
                    searched = ""
                    typed = ""
                }, label = { Text(genre) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(typed, onValueChange = { typed = it },
                modifier = Modifier.weight(1f), singleLine = true,
                label = { Text("Parça veya sanatçı ara") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) })
            Spacer(Modifier.width(8.dp))
            Button(onClick = { searched = typed.trim(); if (mode == "Beğenilenler") mode = "Trendler" }) {
                Text("Ara")
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        error?.let { Text(it, Modifier.padding(16.dp), color = Color(0xFFFF8A80)) }
        if (!loading && error == null && tracks.isEmpty()) {
            Text("Henüz müzik bulunamadı.", Modifier.padding(22.dp), color = Color.LightGray)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(tracks, key = { it.id }) { song ->
                Row(Modifier.fillMaxWidth().background(tile, RoundedCornerShape(16.dp))
                    .clickable { onPlay(song, tracks) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (song.artwork.startsWith("https://")) {
                        AsyncImage(model = song.artwork, contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)))
                    } else {
                        Box(Modifier.size(50.dp).background(Color(0xFF283B2A), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Album, null, tint = accentGreen,
                                modifier = Modifier.size(30.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val minutes = song.duration / 60
                        val seconds = song.duration % 60
                        Text("${song.artist} · ${minutes}:${seconds.toString().padStart(2, '0')}",
                            color = Color.LightGray, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val liked = favorites.any { it.id == song.id }
                    IconButton(onClick = {
                        favoritesRaw = if (liked) favoritesRaw.filterNot {
                            runCatching { AudiusTrack.fromJson(it).id == song.id }.getOrDefault(false)
                        }.toSet() else favoritesRaw + song.toJson()
                        prefs.edit().putStringSet("tracks", favoritesRaw).apply()
                    }) {
                        Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Beğen", tint = if (liked) accentGreen else Color.Gray)
                    }
                    IconButton(onClick = { onAddExternal(ExternalPlaylistItem.fromAudius(song)) }) {
                        Icon(Icons.Rounded.PlaylistAdd, contentDescription = "Spotify listesine Resonance eki olarak ekle", tint = accentGreen)
                    }
                }
            }
        }
    }
}
