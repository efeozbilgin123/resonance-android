package com.resonance.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Media3 custom datasource:
 *
 * resonance://youtube/{videoId} -> fresh playable audio URL -> HTTP DataSource.
 *
 * Resolution happens on ExoPlayer's background loading thread, never on Compose.
 * The Media3 queue contains stable IDs, not signed CDN URLs that expire while
 * a queued item waits. This also permits seek/reopen and headset next/previous.
 */
class ResonanceAudioDataSource(private val delegateFactory: DataSource.Factory) : DataSource {
    private var current: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        current?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = delegateFactory.createDataSource()
        current = source
        listeners.forEach { source.addTransferListener(it) }
        val url = dataSpec.uri
        val resolved = if (url.scheme == "resonance") {
            if (url.host != "youtube") throw IOException("Desteklenmeyen Resonance kaynak türü")
            val id = url.lastPathSegment ?: throw IOException("YouTube video kimliği eksik")
            val stream = YouTubeMusicProvider.resolveAudio(id)
            val uri = Uri.parse(stream.url)
            if (uri.scheme != "https") throw IOException("Geçersiz ses yayını adresi")
            dataSpec.withUri(uri)
        } else dataSpec
        return source.open(resolved)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (current ?: throw IOException("Ses kaynağı açık değil")).read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        current?.responseHeaders ?: emptyMap()

    override fun close() {
        try { current?.close() } finally { current = null }
    }

    class Factory(context: Context) : DataSource.Factory {
        private val baseFactory = DefaultDataSource.Factory(
            context.applicationContext,
            DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 13) Resonance/0.7")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12000)
                .setReadTimeoutMs(15000)
        )
        override fun createDataSource(): DataSource = ResonanceAudioDataSource(baseFactory)
    }
}
