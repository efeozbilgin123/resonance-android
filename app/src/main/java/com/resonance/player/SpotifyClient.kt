package com.resonance.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Spotify Web API client with Authorization Code + PKCE.
 * No client secret is ever used or embedded into the APK.
 * Spotify playback happens on an authorized Spotify Connect device.
 */
class SpotifyClient(private val activity: Activity) {
    companion object {
        const val CALLBACK = "http://127.0.0.1:43892/callback"
        private const val API = "https://api.spotify.com/v1"
        private const val PORT = 43892
    }

    private val pref = activity.getSharedPreferences("spotify_auth", Context.MODE_PRIVATE)
    var clientId: String
        get() = pref.getString("client_id", "") ?: ""
        set(value) { pref.edit().putString("client_id", value.trim()).apply() }

    private var accessToken = pref.getString("access_token", "") ?: ""
    private var refreshToken = pref.getString("refresh_token", "") ?: ""
    private var expiration = pref.getLong("expires_at", 0L)

    val authorized: Boolean
        get() = accessToken.isNotBlank() || refreshToken.isNotBlank()

    fun logout() {
        accessToken = ""; refreshToken = ""; expiration = 0L
        pref.edit().remove("access_token").remove("refresh_token").remove("expires_at").apply()
    }

    fun login(done: (String?) -> Unit) {
        val id = clientId
        if (id.isBlank()) { done("Önce Spotify Developer Client ID gir."); return }
        Thread {
            try {
                ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    server.soTimeout = 180000
                    val secret = randomString(40)
                    val nonce = randomString(20)
                    val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.US_ASCII))
                    )
                    val scope = "user-read-private user-read-email playlist-read-private " +
                        "playlist-read-collaborative user-library-read user-read-playback-state " +
                        "user-modify-playback-state"
                    val authUrl = "https://accounts.spotify.com/authorize?" + form(mapOf(
                        "client_id" to id, "response_type" to "code", "redirect_uri" to CALLBACK,
                        "scope" to scope, "code_challenge_method" to "S256",
                        "code_challenge" to challenge, "state" to nonce, "show_dialog" to "false"
                    ))
                    activity.runOnUiThread {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                    }
                    val socket = server.accept()
                    socket.use {
                        it.soTimeout = 8000
                        val first = BufferedReader(InputStreamReader(it.getInputStream())).readLine() ?: ""
                        val requestPath = first.split(" ").getOrNull(1) ?: ""
                        val params = parseParams(requestPath.substringAfter('?', ""))
                        val valid = params["state"] == nonce
                        val code = if (valid) params["code"] else null
                        val response = if (code != null) "Spotify girişi tamamlandı. Resonance uygulamasına dön." else
                            "Giriş iptal edildi veya doğrulama başarısız."
                        val payload = ("<html><meta charset='UTF-8'><body><h2>" + response + "</h2></body></html>")
                            .toByteArray(Charsets.UTF_8)
                        val headers = ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII)
                        it.getOutputStream().write(headers)
                        it.getOutputStream().write(payload)
                        it.getOutputStream().flush()
                        if (code == null) error(params["error"] ?: "Güvenlik kontrolü veya giriş başarısız")
                        val res = tokenRequest(mapOf("grant_type" to "authorization_code", "code" to code,
                            "redirect_uri" to CALLBACK, "client_id" to id, "code_verifier" to secret))
                        storeTokens(res)
                    }
                }
                activity.runOnUiThread { done(null) }
            } catch (ex: Exception) {
                activity.runOnUiThread { done(ex.message ?: "Spotify giriş hatası") }
            }
        }.start()
    }

    @Synchronized
    private fun validToken(): String {
        if (System.currentTimeMillis() < expiration - 60000L && accessToken.isNotBlank()) return accessToken
        if (refreshToken.isBlank()) error("Spotify oturumu sona erdi. Yeniden bağlan.")
        val res = tokenRequest(mapOf("grant_type" to "refresh_token",
            "refresh_token" to refreshToken, "client_id" to clientId))
        storeTokens(res)
        return accessToken
    }

    private fun storeTokens(json: JSONObject) {
        accessToken = json.getString("access_token")
        refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refreshToken
        expiration = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000L
        pref.edit().putString("access_token", accessToken).putString("refresh_token", refreshToken)
            .putLong("expires_at", expiration).apply()
    }

    fun get(path: String): JSONObject = api("GET", path)
    fun put(path: String, body: JSONObject? = null) = api("PUT", path, body)
    fun post(path: String) = api("POST", path)

    private fun api(method: String, path: String, body: JSONObject? = null): JSONObject {
        val c = URL(API + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("Authorization", "Bearer " + validToken())
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val raw = (if (status in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) error("Spotify API HTTP $status: ${raw.take(260)}")
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } finally { c.disconnect() }
    }

    private fun tokenRequest(params: Map<String, String>): JSONObject {
        val c = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 15000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(form(params).toByteArray(Charsets.UTF_8)) }
            val status = c.responseCode
            val raw = (if(status in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if(status !in 200..299) error("Spotify OAuth HTTP $status: ${raw.take(200)}")
            return JSONObject(raw)
        } finally { c.disconnect() }
    }

    private fun randomString(bytes: Int): String {
        val a = ByteArray(bytes)
        SecureRandom().nextBytes(a)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(a)
    }

    private fun form(m: Map<String, String>) = m.entries.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }
    private fun parseParams(s: String): Map<String, String> =
        s.split("&").filter { it.contains("=") }.associate {
            URLDecoder.decode(it.substringBefore("="), "UTF-8") to
                URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }
}
