package org.openplayer.app

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * MPV-REMOVE: Geçici derleme köprüsü. Video mpv'ye geçti; bu nesne yalnız henüz taşınmamış müzik
 * ve bildirim dosyaları derlensin ve çalışsın diye duruyor. D ajanının işi birleşince silinir.
 */
object PlayerHolder {

    private var current: ExoPlayer? = null
    private val rebuildListeners = mutableListOf<(ExoPlayer) -> Unit>()

    val player: ExoPlayer? get() = current

    /** Harici ses dosyası eklemek için kullanılan kaynak fabrikası (son kurulan oynatıcınınki). */
    var mediaSourceFactory: androidx.media3.exoplayer.source.MediaSource.Factory? = null
        private set

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
        val delay = AudioDelayProcessor().also {
            it.delayMs = AudioDelay.effectiveDelayMs(app)
            AudioDelay.current = it
        }
        val balance = BalanceProcessor().also { AudioBalance.attach(it, settings) }
        val eq = EqProcessor().also { AudioEq.attach(it, settings) }
        fun sink(context: Context, floatOut: Boolean, playbackParams: Boolean) =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(floatOut)
                .setEnableAudioOutputPlaybackParameters(playbackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(delay, balance, eq))
                .build()
        val renderers = object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParameters: Boolean): AudioSink =
                sink(context, enableFloatOutput, enableAudioOutputPlaybackParameters)
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        renderers.setEnableDecoderFallback(true)

        // OkHttp: farklı bir sunucuya yönlendirmede Authorization başlığını otomatik düşürür;
        // https -> http düşürme yönlendirmesi izlenmez.
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
        val http = OkHttpDataSource.Factory(client).setUserAgent(settings.httpUserAgent.ifBlank { "OpenPlayer/1.0" })
        val authedHttp = NetworkDataSourceFactory.withWebDavAuth(app, http)
        val dataSources = NetworkDataSourceFactory(app, DefaultDataSource.Factory(app, authedHttp))
        val mediaSources = DefaultMediaSourceFactory(app)
            .setDataSourceFactory(dataSources)

        mediaSourceFactory = mediaSources
        // Ön bellek: geriye sarma anında olsun diye oynatılmış kısmın son 15 sn'si tutulur (anahtar
        // kareden itibaren); sarma/yeniden başlama eşikleri düşürülerek sarma sonrası bekleme kısaltılır.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 1_500, 3_000)
            .setBackBuffer(15_000, true)
            .build()
        val exo = ExoPlayer.Builder(app, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSources)
            .setAudioAttributes(AudioAttributes.DEFAULT, settings.audioFocus)
            .setHandleAudioBecomingNoisy(settings.pauseOnNoisy)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        // Altyazıyı kendimiz yönettiğimiz için metin parçaları varsayılan olarak kapalı başlar.
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        val audioLangs = settings.audioLanguages.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (audioLangs.isNotEmpty()) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguages(*audioLangs.toTypedArray())
                .build()
        }
        exo.setPlaybackSpeed(settings.playbackSpeed)
        return exo
    }
}
