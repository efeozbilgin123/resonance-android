package com.resonance.player

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

private val highlight = Color(0xFFC6F17B)
private val surface = Color(0xFF171D19)

/** Full-screen native controls shared by Media3 and the native Premium player. */
@Composable
fun NowPlayingScreen(
    title: String,
    artist: String,
    cover: String,
    source: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    queue: PlaybackQueue,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onRepeat: (Int) -> Unit,
    onShuffle: (Boolean) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var scrub by remember(title) { mutableStateOf<Float?>(null) }
    val maxTime = durationMs.coerceAtLeast(1L).toFloat()
    Column(modifier.fillMaxWidth().background(Color(0xFF080B0A))
        .padding(horizontal = 19.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Oynatıcıyı küçült", tint = highlight)
            }
            Text("ŞİMDİ ÇALIYOR", color = highlight,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f))
            Text(source, color = Color.LightGray,
                style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().heightIn(max = 260.dp)
            .aspectRatio(1.2f).clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF26362B)), contentAlignment = Alignment.Center) {
            if (cover.startsWith("https://")) {
                AsyncImage(model = cover, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else Icon(Icons.Rounded.Album, null, tint = highlight,
                modifier = Modifier.size(92.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(title.ifBlank { "Parça seçilmedi" },
            color = Color.White, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineSmall, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        Text(artist, color = Color.LightGray,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Slider(
            value = (scrub ?: positionMs.toFloat()).coerceIn(0f, maxTime),
            onValueChange = { scrub = it },
            onValueChangeFinished = {
                scrub?.let { onSeek(it.toLong()) }
                scrub = null
            },
            valueRange = 0f..maxTime,
            enabled = durationMs > 0
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(positionMs), color = Color.LightGray,
                style = MaterialTheme.typography.labelSmall)
            Text(if (durationMs > 0) clock(durationMs) else "--:--",
                color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onShuffle(!queue.shuffle) }) {
                Icon(Icons.Rounded.Shuffle, "Karışık çalma",
                    tint = if (queue.shuffle) highlight else Color.LightGray)
            }
            IconButton(onClick = onPrevious) {
                Icon(Icons.Rounded.SkipPrevious, "Önceki", tint = Color.White,
                    modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = onToggle) {
                Icon(if (isPlaying) Icons.Rounded.PauseCircleFilled
                    else Icons.Rounded.PlayCircleFilled,
                    if (isPlaying) "Duraklat" else "Oynat",
                    tint = highlight, modifier = Modifier.size(60.dp))
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Rounded.SkipNext, "Sonraki", tint = Color.White,
                    modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = { onRepeat((queue.repeat + 1) % 3) }) {
                Icon(if (queue.repeat == 2) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Tekrar modu", tint = if (queue.repeat > 0) highlight else Color.LightGray)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ÇALMA SIRASI", color = Color.White,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${queue.entries.size} parça",
                color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(queue.entries) { index, entry ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                    .background(if (index == queue.currentIndex)
                        Color(0xFF30422F) else surface)
                    .clickable { onSelect(index) }.padding(horizontal = 7.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${index+1}", color = Color.LightGray,
                        style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, color = if (index == queue.currentIndex)
                            highlight else Color.White,
                            fontWeight = FontWeight.Medium, maxLines = 1)
                        Text("${entry.artist} · ${entry.source.name}",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    IconButton(onClick = { onMove(index, index-1) },
                        enabled = index > 0, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.KeyboardArrowUp, "Yukarı taşı", tint = Color.LightGray)
                    }
                    IconButton(onClick = { onMove(index, index+1) },
                        enabled = index < queue.entries.lastIndex, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Aşağı taşı", tint = Color.LightGray)
                    }
                    IconButton(onClick = { onRemove(index) },
                        modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Close, "Sıradan çıkar", tint = Color.LightGray)
                    }
                }
            }
        }
    }
}

private fun clock(milliseconds: Long): String {
    val sec = milliseconds.coerceAtLeast(0L) / 1000L
    return "${sec / 60}:${(sec % 60).toString().padStart(2,'0')}"
}
