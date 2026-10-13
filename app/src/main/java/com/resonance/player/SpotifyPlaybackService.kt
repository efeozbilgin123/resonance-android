package com.resonance.player

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.IBinder

/**
 * Android mediaPlayback foreground service for native Spotify audio.
 * It preserves the Rust/AudioTrack session while the screen is not visible.
 * Media3 continues to own YouTube/Audius/local audio independently.
 */
class SpotifyPlaybackService : Service() {
    companion object {
        private const val CHANNEL = "spotify_native_playback"
        private const val ID = 9016
        private const val START = "resonance.spotify.START"
        private const val FINISH = "resonance.spotify.FINISH"
        private const val TOGGLE = "resonance.spotify.TOGGLE"
        private const val NEXT = "resonance.spotify.NEXT"
        private const val PREVIOUS = "resonance.spotify.PREVIOUS"
        @Volatile private var active: SpotifyPlaybackService? = null

        fun begin(context: Context) {
            val intent = Intent(context, SpotifyPlaybackService::class.java).setAction(START)
            context.startForegroundService(intent)
        }
        fun finish(context: Context) {
            // Do not start a stopped service just to finish it.
            active?.stopSelf() ?: context.stopService(
                Intent(context, SpotifyPlaybackService::class.java)
            )
        }
        fun refresh() {
            active?.refreshNotification()
        }
    }

    private lateinit var audioManager: AudioManager
    private var focus: AudioFocusRequest? = null
    private var noisyReceiver: BroadcastReceiver? = null
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        active = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        mediaSession = MediaSession(this, "ResonanceNativeSpotify").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = SpotifyAudioEngine.resume()
                override fun onPause() = SpotifyAudioEngine.pause()
                override fun onSkipToNext() {
                    SpotifyAudioEngine.onNext?.invoke()
                }
                override fun onSkipToPrevious() {
                    SpotifyAudioEngine.onPrevious?.invoke()
                }
                override fun onSeekTo(pos: Long) {
                    SpotifyAudioEngine.seek(pos.toInt())
                }
                override fun onStop() = SpotifyAudioEngine.stop()
            }, Handler(Looper.getMainLooper()))
            isActive = true
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL,
                "Spotify Premium oynatma", NotificationManager.IMPORTANCE_LOW))
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { state ->
                if (state != AudioManager.AUDIOFOCUS_GAIN) {
                    SpotifyAudioEngine.pause()
                }
            }.build()
        noisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                    SpotifyAudioEngine.pause()
            }
        }
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            FINISH -> { stopSelf(); return START_NOT_STICKY }
            TOGGLE -> SpotifyAudioEngine.toggle()
            NEXT -> SpotifyAudioEngine.onNext?.invoke()
            PREVIOUS -> SpotifyAudioEngine.onPrevious?.invoke()
            else -> {
                // Foreground notification must appear immediately, before network auth.
                startForeground(ID, notification())
                focus?.let { audioManager.requestAudioFocus(it) }
            }
        }
        refreshNotification()
        return START_NOT_STICKY
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, SpotifyPlaybackService::class.java).setAction(action)
        val request = when (action) {
            TOGGLE -> 1
            NEXT -> 2
            PREVIOUS -> 3
            else -> 4
        }
        return PendingIntent.getService(this, request, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateMediaSession() {
        if (!::mediaSession.isInitialized) return
        mediaSession.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE,
                SpotifyAudioEngine.title.ifBlank { "Resonance Spotify Premium" })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, SpotifyAudioEngine.artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION,
                SpotifyAudioEngine.durationMs.toLong())
            .build())
        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_STOP)
            .setState(
                if (SpotifyAudioEngine.playing) PlaybackState.STATE_PLAYING
                else PlaybackState.STATE_PAUSED,
                SpotifyAudioEngine.positionMs.toLong(),
                if (SpotifyAudioEngine.playing) 1.0f else 0.0f)
            .build()
        mediaSession.setPlaybackState(state)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        updateMediaSession()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(SpotifyAudioEngine.title.ifBlank { "Resonance · Spotify Premium" })
            .setContentText(SpotifyAudioEngine.artist.ifBlank { SpotifyAudioEngine.status })
            .setContentIntent(open)
            .setOngoing(SpotifyAudioEngine.playing)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this,
                    android.R.drawable.ic_media_previous),
                "Önceki", actionIntent(PREVIOUS)).build())
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this,
                    if (SpotifyAudioEngine.playing) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play),
                if (SpotifyAudioEngine.playing) "Duraklat" else "Çal",
                actionIntent(TOGGLE)).build())
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this,
                    android.R.drawable.ic_media_next),
                "Sonraki", actionIntent(NEXT)).build())
            .setStyle(Notification.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    fun refreshNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(ID, notification())
    }

    override fun onDestroy() {
        if (active === this) active = null
        noisyReceiver?.let { runCatching { unregisterReceiver(it) } }
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
