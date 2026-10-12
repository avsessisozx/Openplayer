package org.openplayer.app

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * Oynatıcı bir etkinliğe değil, tüm uygulamaya aittir. Böylece arka planda
 * oynatma, PiP ve ekran döndürme sırasında oynatma kesilmez.
 */
object PlayerHolder {

    private var current: ExoPlayer? = null
    private val rebuildListeners = mutableListOf<(ExoPlayer) -> Unit>()

    val player: ExoPlayer? get() = current

    fun get(context: Context): ExoPlayer =
        current ?: create(context.applicationContext).also { current = it }

    fun addRebuildListener(listener: (ExoPlayer) -> Unit) {
        rebuildListeners += listener
    }

    fun removeRebuildListener(listener: (ExoPlayer) -> Unit) {
        rebuildListeners -= listener
    }

    /** Çözücü modu değişince oynatıcıyı aynı konumdan yeniden kurar. */
    fun rebuild(context: Context): ExoPlayer {
        val old = current
        val items: List<MediaItem> = old?.let { p -> List(p.mediaItemCount) { p.getMediaItemAt(it) } } ?: emptyList()
        val index = old?.currentMediaItemIndex ?: 0
        val position = old?.currentPosition ?: 0L
        val playWhenReady = old?.playWhenReady ?: true
        val speed = old?.playbackParameters?.speed ?: 1f
        val repeat = old?.repeatMode ?: androidx.media3.common.Player.REPEAT_MODE_OFF
        val shuffle = old?.shuffleModeEnabled ?: false
        old?.release()

        val fresh = create(context.applicationContext)
        current = fresh
        if (items.isNotEmpty()) {
            fresh.setMediaItems(items, index, position)
            fresh.setPlaybackSpeed(speed)
            fresh.repeatMode = repeat
            fresh.shuffleModeEnabled = shuffle
            fresh.prepare()
            fresh.playWhenReady = playWhenReady
        }
        rebuildListeners.toList().forEach { it(fresh) }
        return fresh
    }

    fun release() {
        current?.release()
        current = null
    }

    private fun create(app: Context): ExoPlayer {
        val settings = AppSettings.get(app)
        val renderers: DefaultRenderersFactory = when (settings.decoder) {
            DecoderMode.HW ->
                DefaultRenderersFactory(app)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            DecoderMode.HW_PLUS ->
                NextRenderersFactory(app)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            DecoderMode.SW ->
                NextRenderersFactory(app)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }
        renderers.setEnableDecoderFallback(true)

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("OpenPlayer/0.3")
        val authedHttp = NetworkDataSourceFactory.withWebDavAuth(app, http)
        val dataSources = NetworkDataSourceFactory(app, DefaultDataSource.Factory(app, authedHttp))
        val mediaSources = DefaultMediaSourceFactory(app)
            .setDataSourceFactory(dataSources)

        val exo = ExoPlayer.Builder(app, renderers)
            .setMediaSourceFactory(mediaSources)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        // Altyazıyı kendimiz yönettiğimiz için metin parçaları varsayılan olarak kapalı başlar.
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        exo.setPlaybackSpeed(settings.playbackSpeed)
        return exo
    }
}
