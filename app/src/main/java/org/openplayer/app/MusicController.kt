package org.openplayer.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Müzik çalarını yöneten ortak nokta: kuyruk kurma, ses efektleri, uyku zamanlayıcısı. */
object MusicController {

    const val EXTRA_MUSIC = "op_music"

    private val main = Handler(Looper.getMainLooper())
    private var fx: AudioFx? = null
    private var installedOn: ExoPlayer? = null

    var sleepEndsAtMs = 0L
        private set

    private val sleepRunnable = Runnable {
        sleepEndsAtMs = 0L
        PlayerHolder.player?.pause()
    }

    val fxOrNull: AudioFx? get() = fx

    fun isMusic(item: MediaItem?): Boolean =
        item?.mediaMetadata?.extras?.getBoolean(EXTRA_MUSIC, false) == true

    fun toItem(t: MusicTrack): MediaItem {
        val extras = Bundle().apply { putBoolean(EXTRA_MUSIC, true) }
        return MediaItem.Builder()
            .setUri(t.uri)
            .setMediaId(t.id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album)
                    .setArtworkUri(t.artworkUri)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    /** Verilen listeyi kuyruk yapıp [index]. şarkıdan çalmaya başlar. */
    fun play(context: Context, tracks: List<MusicTrack>, index: Int, shuffle: Boolean? = null) {
        if (tracks.isEmpty()) return
        val app = context.applicationContext
        val s = AppSettings.get(app)
        val p = PlayerHolder.get(app)
        ensureInstalled(app, p)
        if (shuffle != null) s.musicShuffle = shuffle
        p.setMediaItems(tracks.map { toItem(it) }, index.coerceIn(0, tracks.size - 1), 0L)
        p.setPlaybackSpeed(1f)
        p.shuffleModeEnabled = s.musicShuffle
        p.repeatMode = s.musicRepeat
        p.prepare()
        p.playWhenReady = true
        // Düz startService: Media3 oynatma başlayınca servisi kendisi ön plana alır.
        // startForegroundService süre içinde startForeground çağrılmazsa uygulamayı çökertir.
        runCatching { app.startService(Intent(app, PlaybackService::class.java)) }
        syncFx(app, p)
    }

    fun enqueue(context: Context, track: MusicTrack, next: Boolean) {
        val app = context.applicationContext
        val p = PlayerHolder.get(app)
        if (!isMusic(p.currentMediaItem) || p.mediaItemCount == 0) {
            play(app, listOf(track), 0)
            return
        }
        val at = if (next) p.currentMediaItemIndex + 1 else p.mediaItemCount
        p.addMediaItem(at, toItem(track))
    }

    fun setRepeat(context: Context, mode: Int) {
        AppSettings.get(context).musicRepeat = mode
        PlayerHolder.player?.repeatMode = mode
    }

    fun setShuffle(context: Context, on: Boolean) {
        AppSettings.get(context).musicShuffle = on
        PlayerHolder.player?.shuffleModeEnabled = on
    }

    // ------------------------------------------------------------ uyku zamanlayıcısı

    fun startSleep(minutes: Int) {
        main.removeCallbacks(sleepRunnable)
        if (minutes <= 0) {
            sleepEndsAtMs = 0L
            return
        }
        sleepEndsAtMs = System.currentTimeMillis() + minutes * 60_000L
        main.postDelayed(sleepRunnable, minutes * 60_000L)
    }

    // ------------------------------------------------------------ ses efektleri

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            PlayerHolder.player?.let { p -> appCtx?.let { syncFx(it, p) } }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            PlayerHolder.player?.let { p -> appCtx?.let { syncFx(it, p) } }
        }
    }

    private var appCtx: Context? = null

    private fun ensureInstalled(app: Context, p: ExoPlayer) {
        appCtx = app
        if (installedOn === p) return
        installedOn?.removeListener(listener)
        p.addListener(listener)
        installedOn = p
    }

    /** Ekolayzır yalnızca müzik çalarken bu denetleyiciye bağlıdır; video ekranı kendisininkini kullanır. */
    private fun syncFx(app: Context, p: ExoPlayer) {
        if (isMusic(p.currentMediaItem)) {
            val f = fx ?: AudioFx(app).also { fx = it }
            f.attach(p.audioSessionId)
        } else {
            fx?.release()
            fx = null
        }
    }

    /** Ses ayarları paneli (ses artırma + ekolayzır). */
    fun showFxPanel(a: android.app.Activity) {
        val f = fx
        val root = a.vertical {}
        val boostLabel = a.label("Ses artırma: %${AppSettings.get(a).volumeBoostPercent}")
        root.addView(boostLabel)
        root.addView(a.seekBar(100, 200, AppSettings.get(a).volumeBoostPercent) { v ->
            if (f != null) f.setBoostPercent(v) else AppSettings.get(a).volumeBoostPercent = v
            boostLabel.text = "Ses artırma: %$v"
        })
        if (f != null && f.available && f.bandCount > 0) {
            val sw = androidx.appcompat.widget.SwitchCompat(a).apply {
                text = "Ekolayzır"
                setTextColor(android.graphics.Color.WHITE)
                isChecked = AppSettings.get(a).eqEnabled
                setOnCheckedChangeListener { _, on -> f.setEnabled(on) }
            }
            root.addView(sw, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(16) })
            val (min, max) = f.levelRange
            for (band in 0 until f.bandCount) {
                val freq = f.centerFreqHz(band)
                root.addView(a.label(if (freq >= 1000) "${freq / 1000} kHz" else "$freq Hz", 12f))
                root.addView(a.seekBar(min, max, f.level(band)) { v -> f.setLevel(band, v) })
            }
        } else {
            root.addView(
                a.label("Ekolayzır için önce bir şarkı çalın.", 13f, UiColors.textSecondary),
                android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(12) }
            )
        }
        PanelBuilder(a)
            .setTitle("Ses ayarları")
            .setView(android.widget.ScrollView(a).apply { addView(root) })
            .setPositiveButton("Tamam", null)
            .setNeutralButton("Sıfırla") { _, _ ->
                f?.reset()
                f?.setBoostPercent(100)
            }
            .show()
    }
}
