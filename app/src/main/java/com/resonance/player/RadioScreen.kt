package com.resonance.player

import android.app.Activity
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
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
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
import org.json.JSONObject

private val green = Color(0xFFC6F17B)
private val darkPanel = Color(0xFF171D19)

@Composable
fun RadioScreen(activity: Activity, onPlay: (RadioStation) -> Unit, modifier: Modifier = Modifier) {
    val prefs = remember { activity.getSharedPreferences("radio_favorites", Context.MODE_PRIVATE) }
    var favoriteRecords by remember {
        mutableStateOf(prefs.getStringSet("stations", emptySet()).orEmpty().toSet())
    }
    val favorites = remember(favoriteRecords) {
        favoriteRecords.mapNotNull { stored ->
            runCatching {
                val j = JSONObject(stored)
                RadioStation(j.getString("id"), j.getString("name"), j.optString("country"),
                    j.optString("tags"), j.getString("stream"), j.optString("codec"), j.optInt("bitrate"))
            }.getOrNull()
        }
    }
    var category by remember { mutableStateOf("Türkiye") }
    var input by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<RadioStation>>(emptyList()) }

    LaunchedEffect(category, search) {
        if (category == "Favoriler" && search.isBlank()) {
            results = favorites
            loading = false
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val tag = when (category) {
                "Pop" -> "pop"
                "Rock" -> "rock"
                "Elektronik" -> "electronic"
                "Caz" -> "jazz"
                else -> ""
            }
            results = withContext(Dispatchers.IO) { RadioDirectory.list(search, tag) }
        } catch (e: Exception) {
            error = "İstasyonlar yüklenemedi: ${e.localizedMessage ?: "Bağlantı hatası"}"
            results = emptyList()
        } finally {
            loading = false
        }
    }

    Column(modifier.fillMaxWidth().background(Color(0xFF080B0A))) {
        Text("Canlı radyo", Modifier.padding(horizontal = 22.dp), color = Color.White,
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Text("Bağımsız istasyonlar · Spotify hesabı gerektirmez",
            Modifier.padding(horizontal = 22.dp, vertical = 6.dp), color = Color.LightGray,
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Türkiye", "Pop", "Rock", "Elektronik", "Caz", "Favoriler").forEach { item ->
                FilterChip(selected = category == item, onClick = {
                    category = item
                    search = ""
                    input = ""
                }, label = { Text(item) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                singleLine = true, label = { Text("İstasyon ara") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) })
            Spacer(Modifier.width(8.dp))
            Button(onClick = { search = input.trim() }) { Text("Ara") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        error?.let { Text(it, Modifier.padding(16.dp), color = Color(0xFFFF8A80)) }
        if (!loading && error == null && results.isEmpty()) {
            Text("İstasyon bulunamadı. Başka bir arama deneyebilirsin.",
                Modifier.padding(22.dp), color = Color.LightGray)
        }
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(results, key = { it.id }) { station ->
                Row(
                    Modifier.fillMaxWidth().background(darkPanel, RoundedCornerShape(16.dp))
                        .clickable { onPlay(station) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(48.dp).background(Color(0xFF283B2A), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Radio, null, tint = green, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(station.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(listOf(station.country, station.tags.substringBefore(','),
                            station.codec).filter { it.isNotBlank() }.joinToString(" · "),
                            color = Color.LightGray, maxLines = 1,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = {
                        val record = JSONObject().put("id", station.id).put("name", station.name)
                            .put("country", station.country).put("tags", station.tags)
                            .put("stream", station.stream).put("codec", station.codec)
                            .put("bitrate", station.bitrate).toString()
                        favoriteRecords = if (favoriteRecords.any {
                                runCatching { JSONObject(it).optString("id") == station.id }.getOrDefault(false)
                            }) favoriteRecords.filterNot {
                                runCatching { JSONObject(it).optString("id") == station.id }.getOrDefault(false)
                            }.toSet()
                            else favoriteRecords + record
                        prefs.edit().putStringSet("stations", favoriteRecords).apply()
                        if (category == "Favoriler" && search.isBlank()) {
                            results = results.filter { it.id in favoriteRecords.mapNotNull { json ->
                                runCatching { JSONObject(json).getString("id") }.getOrNull()
                            } }
                        }
                    }) {
                        Icon(if (favorites.any { it.id == station.id }) Icons.Rounded.Favorite
                             else Icons.Rounded.FavoriteBorder, null,
                            tint = if (favorites.any { it.id == station.id }) green else Color.Gray)
                    }
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Oynat", tint = green)
                }
            }
        }
    }
}
