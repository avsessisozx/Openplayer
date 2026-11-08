package org.openplayer.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import java.util.concurrent.CopyOnWriteArrayList

/** Çalan parçanın ses biçimi ("Çalıyor" ekranındaki kHz / kbps / biçim satırı için). */
data class MusicAudioFormat(val sampleRate: Int, val bitrate: Int, val codec: String?)

/**
 * Müzik çalarını yöneten ortak nokta: sıra, ses düzeyi (ReplayGain, geçiş), uyku zamanlayıcısı.
 * Motor tektir ([MpvEngines]); müzik çalarken [PlaybackHub] hedefi bu denetleyicidir. Video motoru
 * devralınca müzik sırası boşalır (eskiden de video oynatıcının listesini değiştiriyordu).
 * Yalnız ana iş parçacığından kullanılır.
 */
object MusicController {

    private val main = Handler(Looper.getMainLooper())
    private var fx: AudioFx? = null
    private var appCtx: Context? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    var sleepEndsAtMs = 0L
        private set

    private val sleepRunnable = Runnable {
        sleepEndsAtMs = 0L
        PlaybackHub.target?.pause()
    }

    private val deck = MusicDeck<MusicTrack>(
        engine = { MpvEngines.get(appCtx ?: error("MusicController bağlanmadı")) },
        entryOf = { t -> DeckEntry(t.id.toString(), t.uri.toString(), t.clipStartMs, t.clipEndMs, t.durationMs) },
    ).apply {
        onTrackChanged = { trackChanged() }
        onStateChanged = { stateChanged() }
        onPausedAtEnd = { sleepAtEnd = false }
    }

    /** Geçici uyumluluk: video ekranı henüz eski oynatıcı öğesi soruyor. */ // MPV-REMOVE
    @Suppress("UNUSED_PARAMETER") // MPV-REMOVE
    fun isMusic(item: androidx.media3.common.MediaItem?): Boolean = false // MPV-REMOVE

    // ------------------------------------------------------------ durum

    /** Müzik şu an motoru kullanıyor mu. */
    val isActive: Boolean get() = PlaybackHub.target === target && deck.queue.size > 0
    val current: MusicTrack? get() = if (isActive) deck.current else null
    val queue: List<MusicTrack> get() = if (isActive) deck.queue.items else emptyList()
    val queueIndex: Int get() = deck.queue.index
    val isPlaying: Boolean get() = isActive && deck.isPlaying
    /** Kullanıcı niyeti; parça geçişinde ve tamponda da true kalır. */
    val playWhenReady: Boolean get() = isActive && deck.playIntent && !deck.ended
    val positionMs: Long get() = if (isActive) deck.positionMs else 0L
    val durationMs: Long get() = if (isActive) deck.durationMs else 0L
    val shuffle: Boolean get() = deck.queue.shuffle
    /** [MusicQueue.REPEAT_OFF], [MusicQueue.REPEAT_ONE] ya da [MusicQueue.REPEAT_ALL]. */
    val repeatMode: Int get() = deck.queue.repeatMode
    val speed: Float get() = MpvEngines.current?.takeIf { isActive }?.speed ?: 1f

    /** Parça, çalma durumu ya da sıra değişince çalışır (ana iş parçacığı). onStop'ta kaldırın. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    // ------------------------------------------------------------ denetim

    /** Verilen listeyi sıra yapıp [index]. şarkıdan çalmaya başlar. */
    fun play(context: Context, tracks: List<MusicTrack>, index: Int, shuffle: Boolean? = null) {
        if (tracks.isEmpty()) return
        val app = context.applicationContext
        appCtx = app
        val s = AppSettings.get(app)
        if (shuffle != null) s.musicShuffle = shuffle
        PlaybackHub.setTarget(app, target)
        val engine = MpvEngines.get(app)
        // Gömülü kapak resmi görüntü parçası olarak çözülmesin; video devralınca geri alınır.
        engine.setProperty("audio-display", "no")
        engine.setSpeed(1f)
        deck.queue.repeatMode = s.musicRepeat
        deck.queue.shuffle = s.musicShuffle
        deck.start(tracks, index.coerceIn(0, tracks.size - 1))
        syncFx(app)
        ensureService(app)
    }

    fun enqueue(context: Context, track: MusicTrack, next: Boolean) {
        val app = context.applicationContext
        if (!isActive) {
            play(app, listOf(track), 0)
            return
        }
        if (next) deck.queue.insertNext(track) else deck.queue.append(track)
        notifyListeners()
    }

    fun resume() {
        if (!isActive) return
        deck.play()
        appCtx?.let { ensureService(it) }
    }

    fun pause() {
        if (isActive) deck.pause()
    }

    fun togglePlay() {
        if (playWhenReady) pause() else resume()
    }

    fun next() {
        if (isActive) deck.next()
    }

    fun previous() {
        if (isActive) deck.previous()
    }

    fun playAt(index: Int) {
        if (!isActive) return
        deck.playAt(index)
        appCtx?.let { ensureService(it) }
    }

    fun seekTo(ms: Long) {
        if (isActive) deck.seekTo(ms)
    }

    fun setSpeed(value: Float) {
        if (!isActive) return
        MpvEngines.current?.setSpeed(value)
        notifyListeners()
    }

    fun setRepeat(context: Context, mode: Int) {
        AppSettings.get(context).musicRepeat = mode
        deck.queue.repeatMode = mode
        notifyListeners()
    }

    fun setShuffle(context: Context, on: Boolean) {
        AppSettings.get(context).musicShuffle = on
        deck.queue.shuffle = on
        notifyListeners()
    }

    /** Çalan parçanın ses biçimi; motorda dosya yoksa boş değerler. */
    fun audioFormat(): MusicAudioFormat {
        val e = MpvEngines.current?.takeIf { isActive } ?: return MusicAudioFormat(0, 0, null)
        val track = e.tracks().firstOrNull { it.type == MpvTrackType.AUDIO && it.selected }
        val rate = track?.sampleRate?.takeIf { it > 0 } ?: e.getProperty("audio-params/samplerate")?.toDoubleOrNull()?.toInt() ?: 0
        // Ham değer bit/sn'dir (yalnız OSD biçimi kbps yazar).
        val bitrate = e.getProperty("audio-bitrate")?.toDoubleOrNull()?.toInt() ?: 0
        return MusicAudioFormat(rate, bitrate, track?.codec)
    }

    /** Kapak: klasördeki kapak dosyası, yoksa sistemin küçük resmi. Arka plan iş parçacığında çağrılır. */
    fun artwork(context: Context, t: MusicTrack, sizePx: Int): Bitmap? = runCatching {
        val cover = t.coverFile
        if (cover != null) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(cover, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= sizePx) sample *= 2
            return@runCatching BitmapFactory.decodeFile(cover, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(t.uri, Size(sizePx, sizePx), null)
        else context.contentResolver.openInputStream(t.artworkUri)?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun ensureService(app: Context) {
        if (!PlaybackService.running) PlaybackService.start(app)
    }

    // ------------------------------------------------------------ PlaybackHub hedefi

    private val target = object : PlaybackTarget {
        override val isMusic: Boolean get() = true
        override val title: String get() = deck.current?.title.orEmpty()
        override val artist: String get() = deck.current?.artist.orEmpty()
        override val album: String get() = deck.current?.album.orEmpty()
        override val positionMs: Long get() = deck.positionMs
        override val durationMs: Long get() = deck.durationMs
        override val isPlaying: Boolean get() = deck.isPlaying
        override val playWhenReady: Boolean get() = deck.playIntent && !deck.ended
        override val speed: Float get() = MpvEngines.current?.speed ?: 1f
        override val canSkipNext: Boolean get() = deck.queue.peekNext(auto = false) != null
        override val canSkipPrevious: Boolean get() = deck.queue.size > 0

        override fun play() = resume()
        override fun pause() = deck.pause()
        override fun next() = deck.next()
        override fun previous() = deck.previous()
        override fun seekTo(ms: Long) = deck.seekTo(ms)

        override fun openIntent(context: Context): Intent =
            Intent(context, NowPlayingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

        override val artworkKey: String get() = deck.current?.let { "${it.id}|${it.coverFile}|${it.albumId}" }.orEmpty()

        override fun artwork(context: Context, sizePx: Int): Bitmap? =
            deck.current?.let { artwork(context, it, sizePx) }

        override fun setDuck(factor: Float) {
            duckFactor = factor
            applyVolume()
        }

        override fun onEngineEvent(event: MpvEvent) {
            deck.onEvent(event)
            when (event) {
                is MpvEvent.PlayingChanged -> playingChanged(event.playing)
                MpvEvent.FileLoaded, MpvEvent.TracksChanged -> updateReplayGain()
                else -> Unit
            }
        }

        override fun onDetached() = detached()
    }

    private fun detached() {
        main.removeCallbacks(fadeTicker)
        sleepAtEnd = false
        deck.clear()
        replayFactor = 1f
        fadeFactor = 1f
        duckFactor = 1f
        lastVolume = null
        MpvEngines.current?.takeIf { !it.isReleased }?.let { e ->
            e.setProperty("audio-display", "embedded-first")
            e.setVolume(100)
        }
        fx?.release()
        fx = null
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
        appCtx?.let { MusicWidget.update(it) }
    }

    private fun trackChanged() {
        appCtx?.let { syncFx(it) }
        if (deck.isPlaying) countPlay()
        main.removeCallbacks(fadeTicker)
        main.post(fadeTicker)
        updateReplayGain()
        PlaybackHub.notifyChanged()
        notifyListeners()
    }

    private fun stateChanged() {
        PlaybackHub.notifyChanged()
        notifyListeners()
    }

    private fun playingChanged(playing: Boolean) {
        main.removeCallbacks(fadeTicker)
        if (playing) {
            main.post(fadeTicker)
            countPlay()
        }
        notifyListeners()
    }

    private var lastCountedId: String? = null

    private fun countPlay() {
        val id = deck.current?.id?.toString() ?: return
        if (id == lastCountedId) return
        lastCountedId = id
        appCtx?.let { MusicStats.record(it, id) }
    }

    // ------------------------------------------------------------ uyku zamanlayıcısı

    var sleepAtEnd = false
        private set

    /** Çalan şarkı bitince duraklatır. */
    fun sleepAtEndOfTrack(on: Boolean) {
        main.removeCallbacks(sleepRunnable)
        sleepEndsAtMs = 0L
        sleepAtEnd = on
        deck.pauseAtEnd = on
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

    // ------------------------------------------------------------ ses düzeyi

    private var replayFactor = 1f
    private var fadeFactor = 1f
    private var duckFactor = 1f
    private var lastVolume: String? = null

    /**
     * Müzik çalarken motorun `volume` özelliği burada yönetilir: ses artırma × ReplayGain ×
     * geçiş yumuşatması × kısma. Video çalarken dokunulmaz.
     */
    private fun applyVolume(force: Boolean = false) {
        val c = appCtx ?: return
        if (!isActive) return
        // Video ile aynı kübik eğri: %200 = +15 dB (MpvAudio).
        val value = MpvAudio.volumeFor(AppSettings.get(c).volumeBoostPercent, (replayFactor * fadeFactor * duckFactor).coerceIn(0f, 1f))
        if (!force && value == lastVolume) return
        lastVolume = value
        MpvEngines.current?.setProperty("volume", value)
    }

    private fun updateReplayGain() {
        val c = appCtx ?: return
        if (!isActive) return
        val mode = AppSettings.get(c).replayGainMode
        val e = MpvEngines.current
        val db = if (mode == 0 || e == null) null
        else MediaTags.replayGainDb(e.getPropertyJson("track-list"), e.getPropertyJson("metadata"), mode)
        replayFactor = if (db == null) 1f else Math.pow(10.0, db / 20.0).toFloat().coerceAtMost(1f)
        applyVolume()
    }

    private val fadeTicker = object : Runnable {
        override fun run() {
            val c = appCtx
            if (c == null || !isActive) {
                fadeFactor = 1f
                return
            }
            val ms = AppSettings.get(c).crossfadeSec * 1000L
            var f = 1f
            if (ms > 0) {
                val pos = deck.positionMs
                val dur = deck.durationMs
                if (pos < ms) f = minOf(f, Math.sqrt(pos.coerceAtLeast(0L).toDouble() / ms).toFloat())
                if (dur > 0 && dur - pos < ms) f = minOf(f, Math.sqrt((dur - pos).coerceAtLeast(0L).toDouble() / ms).toFloat())
            }
            fadeFactor = f.coerceIn(0f, 1f)
            applyVolume()
            if (deck.isPlaying) main.postDelayed(this, 120)
        }
    }

    /** Ses ayarları panelinden: ReplayGain, geçiş süresi ya da ses artırma değişince çağrılır. */
    fun refreshAudioSettings() {
        updateReplayGain()
        applyVolume(force = true)
        main.removeCallbacks(fadeTicker)
        main.post(fadeTicker)
    }

    /**
     * Ses zinciri (ekolayzır, denge, reverb...) yalnızca müzik çalarken bu denetleyiciye bağlıdır;
     * video ekranı kendisininkini kullanır.
     */
    private fun syncFx(app: Context) {
        if (isActive) {
            if (fx == null) fx = AudioFx(app)
            MpvAudio.volumeManagedElsewhere = true
            MpvAudio.apply(app)
            applyVolume(force = true)
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
            applyVolume(force = true)
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
                st.volumeBoostPercent = 100
                applyVolume(force = true)
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
