package com.resonance.player

import android.net.Uri
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Public Radio Browser directory, independent of Spotify.
 * Streams are supplied by radio stations and may disappear or be geographically restricted.
 * https://docs.radio-browser.info/
 */
data class RadioStation(
    val id: String,
    val name: String,
    val country: String,
    val tags: String,
    val stream: String,
    val codec: String,
    val bitrate: Int
)

object RadioDirectory {
    private val servers = listOf(
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info"
    )

    fun list(query: String = "", category: String = ""): List<RadioStation> {
        val options = mutableListOf("limit" to "45", "hidebroken" to "true", "order" to "clickcount", "reverse" to "true")
        when {
            query.isNotBlank() -> options.add("name" to query)
            category.isNotBlank() -> options.add("tag" to category)
            else -> options.add("countrycode" to "TR")
        }
        val parameters = options.joinToString("&") {
            Uri.encode(it.first) + "=" + Uri.encode(it.second)
        }
        var failure: Exception? = null
        for (server in servers) {
            val connection = URL("$server/json/stations/search?$parameters").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 7000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "ResonanceAndroid/0.4 (radio-browser client)")
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode !in 200..299) {
                    error("Radyo sunucusu: HTTP ${connection.responseCode}")
                }
                val json = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                return (0 until json.length()).mapNotNull { index ->
                    val item = json.optJSONObject(index) ?: return@mapNotNull null
                    val raw = item.optString("url_resolved").ifBlank { item.optString("url") }
                    val uri = Uri.parse(raw)
                    if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank()) return@mapNotNull null
                    val id = item.optString("stationuuid")
                    if (id.isBlank()) return@mapNotNull null
                    RadioStation(
                        id = id,
                        name = item.optString("name").trim().ifBlank { "Bilinmeyen istasyon" },
                        country = item.optString("countrycode"),
                        tags = item.optString("tags"),
                        stream = raw,
                        codec = item.optString("codec"),
                        bitrate = item.optInt("bitrate")
                    )
                }.distinctBy { it.id }
            } catch (e: Exception) {
                failure = e
            } finally { connection.disconnect() }
        }
        throw failure ?: IllegalStateException("Radyo dizinine ulaşılamadı")
    }
}
