package org.openplayer.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
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
        val b = MediaItem.Builder()
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
        // .cue sanal parçası: aynı dosyayı belirtilen aralıkta kırp (ExoPlayer sınırları yönetir).
        if (t.clipStartMs > 0 || t.clipEndMs > t.clipStartMs) {
            b.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(t.clipStartMs.coerceAtLeast(0L))
                    .apply { if (t.clipEndMs > t.clipStartMs) setEndPositionMs(t.clipEndMs) }
                    .build()
            )
        }
        return b.build()
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

    var sleepAtEnd = false
        private set

    /** Çalan şarkı bitince duraklatır. */
    fun sleepAtEndOfTrack(on: Boolean) {
        main.removeCallbacks(sleepRunnable)
        sleepEndsAtMs = 0L
        sleepAtEnd = on
        PlayerHolder.player?.pauseAtEndOfMediaItems = on
    }

    fun startSleep(minutes: Int) {
        if (sleepAtEnd) sleepAtEndOfTrack(false)
        main.removeCallbacks(sleepRunnable)
        if (minutes <= 0) {
            sleepEndsAtMs = 0L
            return
        }
        sleepEndsAtMs = System.currentTimeMillis() + minutes * 60_000L
        main.postDelayed(sleepRunnable, minutes * 60_000L)
    }

    // ------------------------------------------------------------ ses efektleri

    private var lastCountedId: String? = null
    private var lastTracks: Tracks? = null
    private var replayFactor = 1f
    private var fadeFactor = 1f

    private fun parseGain(value: String?): Float? =
        value?.let { Regex("-?\\d+(\\.\\d+)?").find(it)?.value?.toFloatOrNull() }

    private fun replayGainDb(tracks: Tracks, mode: Int): Float? {
        var track: Float? = null
        var album: Float? = null
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) {
                val md = g.getTrackFormat(i).metadata ?: continue
                for (j in 0 until md.length()) {
                    val e = md.get(j)
                    val key: String
                    val value: String?
                    when (e) {
                        is VorbisComment -> { key = e.key; value = e.value }
                        is TextInformationFrame -> { key = e.description ?: ""; value = e.values.firstOrNull() }
                        else -> continue
                    }
                    when (key.uppercase()) {
                        "REPLAYGAIN_TRACK_GAIN" -> track = parseGain(value)
                        "REPLAYGAIN_ALBUM_GAIN" -> album = parseGain(value)
                    }
                }
            }
        }
        return if (mode == 2) album ?: track else track ?: album
    }

    /** ReplayGain (yalnızca ses kısma) ve geçiş yumuşatmasını birleştirip oynatıcı sesine uygular. */
    private fun applyVolume() {
        val p = PlayerHolder.player ?: return
        // Video oynarken ses düzeyine dokunma: video ekranının "sessiz" ayarını bozmasın.
        if (!isMusic(p.currentMediaItem)) return
        p.volume = (replayFactor * fadeFactor).coerceIn(0f, 1f)
    }

    private fun updateReplayGain() {
        val c = appCtx ?: return
        val mode = AppSettings.get(c).replayGainMode
        val db = if (mode == 0) null else lastTracks?.let { replayGainDb(it, mode) }
        replayFactor = if (db == null) 1f else Math.pow(10.0, db / 20.0).toFloat().coerceAtMost(1f)
        applyVolume()
    }

    private val fadeTicker = object : Runnable {
        override fun run() {
            val p = PlayerHolder.player
            val c = appCtx
            if (p == null || c == null || !isMusic(p.currentMediaItem)) {
                fadeFactor = 1f
                applyVolume()
                return
            }
            val ms = AppSettings.get(c).crossfadeSec * 1000L
            var f = 1f
            if (ms > 0) {
                val pos = p.currentPosition
                val dur = p.duration
                if (pos < ms) f = minOf(f, Math.sqrt(pos.coerceAtLeast(0L).toDouble() / ms).toFloat())
                if (dur > 0 && dur - pos < ms) f = minOf(f, Math.sqrt((dur - pos).coerceAtLeast(0L).toDouble() / ms).toFloat())
            }
            fadeFactor = f.coerceIn(0f, 1f)
            applyVolume()
            if (p.isPlaying) main.postDelayed(this, 120)
        }
    }

    /** Ses ayarları panelinden: ReplayGain veya geçiş süresi değişince çağrılır. */
    fun refreshAudioSettings() {
        updateReplayGain()
        main.removeCallbacks(fadeTicker)
        main.post(fadeTicker)
    }

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            lastTracks = tracks
            updateReplayGain()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            appCtx?.let { MusicWidget.update(it) }
            main.removeCallbacks(fadeTicker)
            if (isPlaying) main.post(fadeTicker)
            val item = PlayerHolder.player?.currentMediaItem
            if (isPlaying && isMusic(item) && item?.mediaId != lastCountedId) {
                lastCountedId = item?.mediaId
                appCtx?.let { c -> item?.mediaId?.let { MusicStats.record(c, it) } }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepAtEnd) {
                sleepAtEnd = false
                PlayerHolder.player?.pauseAtEndOfMediaItems = false
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            appCtx?.let { MusicWidget.update(it) }
            main.post(fadeTicker)
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

    /** Ses ayarları paneli (ses artırma, ekolayzır, bas/tiz, denge, ReplayGain, geçiş). */
    fun showFxPanel(a: android.app.Activity) {
        val f = fx
        val st = AppSettings.get(a)
        val root = a.vertical {}
        val boostLabel = a.label(Tx.s(R.string.musiccontr_001, st.volumeBoostPercent))
        root.addView(boostLabel)
        root.addView(a.seekBar(100, 200, st.volumeBoostPercent) { v ->
            if (f != null) f.setBoostPercent(v) else st.volumeBoostPercent = v
            boostLabel.text = Tx.s(R.string.musiccontr_001, v)
        })
        if (f != null && f.available && f.bandCount > 0) {
            val sw = androidx.appcompat.widget.SwitchCompat(a).apply {
                text = Tx.s(R.string.musiccontr_002)
                setTextColor(android.graphics.Color.WHITE)
                isChecked = st.eqEnabled
                setOnCheckedChangeListener { _, on -> f.setEnabled(on) }
            }
            root.addView(sw, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(16) })
            val presets = f.presetNames
            if (presets.isNotEmpty()) {
                root.addView(a.label(Tx.s(R.string.musiccontr_003), 14f, UiColors.accent).apply {
                    setPadding(0, a.dp(8), 0, a.dp(8))
                    setOnClickListener {
                        a.showChoice(Tx.s(R.string.musiccontr_004), presets, -1) { i ->
                            f.usePreset(i)
                            sw.isChecked = true
                            android.widget.Toast.makeText(a, Tx.s(R.string.musiccontr_005), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
            val (min, max) = f.levelRange
            for (band in 0 until f.bandCount) {
                val freq = f.centerFreqHz(band)
                root.addView(a.label(if (freq >= 1000) "${freq / 1000} kHz" else "$freq Hz", 12f))
                root.addView(a.seekBar(min, max, f.level(band)) { v -> f.setLevel(band, v) })
            }
            val bassLabel = a.label(Tx.s(R.string.musiccontr_006, st.bassStrength / 10), 14f)
            root.addView(bassLabel, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(16) })
            root.addView(a.seekBar(0, 1000, st.bassStrength) { v ->
                f.setBass(v)
                bassLabel.text = Tx.s(R.string.musiccontr_006, v / 10)
            })
            val virtLabel = a.label(Tx.s(R.string.musiccontr_007, st.virtualizerStrength / 10), 14f)
            root.addView(virtLabel, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
            root.addView(a.seekBar(0, 1000, st.virtualizerStrength) { v ->
                f.setVirtualizer(v)
                virtLabel.text = Tx.s(R.string.musiccontr_007, v / 10)
            })
            root.addView(a.label(Tx.s(R.string.musiccontr_008), 11f, UiColors.textSecondary))
        } else {
            root.addView(
                a.label(Tx.s(R.string.musiccontr_009), 13f, UiColors.textSecondary),
                android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(12) }
            )
        }

        val balLabel = a.label(Tx.s(R.string.musiccontr_010, balanceText(st.audioBalance)), 14f)
        root.addView(balLabel, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(16) })
        root.addView(a.seekBar(-100, 100, st.audioBalance) { v ->
            st.audioBalance = v
            AudioBalance.update(st)
            balLabel.text = Tx.s(R.string.musiccontr_010, balanceText(v))
        })
        root.addView(androidx.appcompat.widget.SwitchCompat(a).apply {
            text = Tx.s(R.string.musiccontr_011)
            setTextColor(android.graphics.Color.WHITE)
            isChecked = st.monoMix
            setOnCheckedChangeListener { _, on ->
                st.monoMix = on
                AudioBalance.update(st)
            }
        }, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })

        val rgNames = listOf(Tx.s(R.string.audiofx_004), Tx.s(R.string.musiccontr_012), Tx.s(R.string.mediainfo_009))
        root.addView(a.label(Tx.s(R.string.musiccontr_013, rgNames[st.replayGainMode]), 14f, UiColors.accent).apply {
            setPadding(0, a.dp(14), 0, a.dp(6))
            setOnClickListener {
                a.showChoice("ReplayGain", rgNames, st.replayGainMode) { i ->
                    st.replayGainMode = i
                    refreshAudioSettings()
                    text = Tx.s(R.string.musiccontr_013, rgNames[i])
                }
            }
        })
        val cfLabel = a.label(if (st.crossfadeSec == 0) Tx.s(R.string.musiccontr_015) else Tx.s(R.string.musiccontr_014, st.crossfadeSec), 14f)
        root.addView(cfLabel, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
        root.addView(a.seekBar(0, 12, st.crossfadeSec) { v ->
            st.crossfadeSec = v
            refreshAudioSettings()
            cfLabel.text = if (v == 0) Tx.s(R.string.musiccontr_015) else Tx.s(R.string.musiccontr_014, v)
        })
        root.addView(a.label(Tx.s(R.string.musiccontr_016), 11f, UiColors.textSecondary))

        PanelBuilder(a)
            .setTitle(Tx.s(R.string.musiccontr_017))
            .setView(android.widget.ScrollView(a).apply { addView(root) })
            .setPositiveButton(Tx.s(R.string.mediainfo_022), null)
            .setNeutralButton(Tx.s(R.string.musiccontr_018)) { _, _ ->
                f?.reset()
                f?.setBoostPercent(100)
                st.audioBalance = 0
                st.monoMix = false
                AudioBalance.update(st)
            }
            .show()
    }

    private fun balanceText(v: Int) = when {
        v == 0 -> "orta"
        v < 0 -> "sol %${-v}"
        else -> Tx.s(R.string.musiccontr_019, v)
    }
}
