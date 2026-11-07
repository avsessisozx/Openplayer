package org.openplayer.app

import kotlin.math.abs
import kotlin.math.roundToInt

/** Motora açılacak parça. [clipEndMs] 0 ise dosyanın sonuna kadar çalar (.cue sanal parçaları için aralık). */
data class DeckEntry(
    val key: String,
    val uri: String,
    val clipStartMs: Long = 0L,
    val clipEndMs: Long = 0L,
    val durationMs: Long = 0L,
) {
    val isClip: Boolean get() = clipStartMs > 0L || clipEndMs > clipStartMs
}

/**
 * [MusicQueue] sırasını motorda çalar: parça sonunda sıradakine geçer, .cue aralıklarını konumla
 * yönetir, açılamayan parçayı atlar. Yalnız ana iş parçacığından kullanılır; olaylar [onEvent] ile gelir.
 * Konumlar parçaya görelidir: .cue parçasında 0, parçanın başıdır.
 */
class MusicDeck<T>(
    private val engine: () -> MpvEngine,
    private val entryOf: (T) -> DeckEntry,
    val queue: MusicQueue<T> = MusicQueue(),
) {
    companion object {
        /** Bu kadar çaldıktan sonra "önceki" düğmesi parçanın başına döner. */
        const val RESTART_THRESHOLD_MS = 3_000L
        private const val SETTLE_TOLERANCE_MS = 2_000L
    }

    var onTrackChanged: (() -> Unit)? = null
    var onStateChanged: (() -> Unit)? = null
    /** "Parça bitince dur" uygulandı. */
    var onPausedAtEnd: (() -> Unit)? = null

    /** Çalan parça bitince dur. */
    var pauseAtEnd = false

    /** Sıra bitti; [play] baştan başlatır. */
    var ended = false
        private set
    /** Kullanıcı niyeti: parça geçişlerinde de true kalır. */
    var playIntent = false
        private set

    private var openUri: String? = null
    private var fileOpen = false
    /** open çağrıldı, motorun yeni dosyaya başladığı henüz bildirilmedi: gelen bitiş olayları eskiye aittir. */
    private var awaitingStart = false
    /** Sarma sürüyor: motorun konumu henüz eskidir, .cue sonu sınanmaz. */
    private var settling = false
    private var settleTargetMs = 0L
    private var failures = 0
    /** FileEnded(ERROR) işlendi; aynı hatanın Error olayı bir kez daha sayılmasın. */
    private var swallowError = false

    val current: T? get() = queue.current
    private val entry: DeckEntry? get() = queue.current?.let(entryOf)

    val durationMs: Long
        get() {
            val e = entry ?: return 0L
            if (e.clipEndMs > e.clipStartMs) return e.clipEndMs - e.clipStartMs
            val whole = if (fileOpen) engine().durationMs else 0L
            return if (whole > e.clipStartMs) whole - e.clipStartMs else e.durationMs
        }

    val positionMs: Long
        get() {
            val e = entry ?: return 0L
            if (ended) return durationMs
            val pos = if (settling || awaitingStart) settleTargetMs else engine().positionMs
            return (pos - e.clipStartMs).coerceAtLeast(0L)
        }

    val isPlaying: Boolean get() = !ended && queue.size > 0 && engine().isPlaying

    fun start(tracks: List<T>, index: Int) {
        queue.set(tracks, index)
        failures = 0
        pauseAtEnd = false
        load(queue.index)
    }

    fun clear() {
        queue.clear()
        openUri = null
        fileOpen = false
        awaitingStart = false
        settling = false
        ended = false
        playIntent = false
        pauseAtEnd = false
        swallowError = false
        failures = 0
    }

    fun play() {
        if (queue.size == 0) return
        if (ended) {
            load(queue.index)
            return
        }
        playIntent = true
        engine().play()
        onStateChanged?.invoke()
    }

    fun pause() {
        playIntent = false
        engine().pause()
        onStateChanged?.invoke()
    }

    fun seekTo(ms: Long) {
        val e = entry ?: return
        val dur = durationMs
        val target = if (dur > 0) ms.coerceIn(0L, dur) else ms.coerceAtLeast(0L)
        if (ended || !fileOpen) {
            load(queue.index, target, paused = !ended && !playIntent)
            return
        }
        settle(e.clipStartMs + target)
        engine().seekTo(e.clipStartMs + target)
    }

    fun playAt(i: Int) {
        if (i in 0 until queue.size) load(i)
    }

    fun next() {
        queue.peekNext(auto = false)?.let { load(it, paused = !playIntent) }
    }

    fun previous() {
        val prev = queue.peekPrevious()
        if (positionMs > RESTART_THRESHOLD_MS || prev == null) seekTo(0L)
        else load(prev, paused = !playIntent)
    }

    fun onEvent(e: MpvEvent) {
        when (e) {
            is MpvEvent.FileStarted -> awaitingStart = false
            MpvEvent.FileLoaded -> {
                fileOpen = true
                failures = 0
                swallowError = false
            }
            MpvEvent.PlaybackRestarted -> settling = false
            is MpvEvent.Position -> onPosition(e.ms)
            is MpvEvent.FileEnded -> {
                // keep-open açık: doğal sonda dosya motorda açık kalır ama duraklar; yeniden açmak en güvenlisi.
                fileOpen = false
                if (awaitingStart) return
                when (e.reason) {
                    EndReason.EOF -> trackFinished()
                    EndReason.ERROR -> {
                        swallowError = true
                        trackFailed()
                    }
                    else -> Unit
                }
            }
            // Error, FileStarted olmadan da gelebilir (adres çözülemedi), bu yüzden awaitingStart'a bakılmaz.
            is MpvEvent.Error -> if (swallowError) swallowError = false else trackFailed()
            else -> Unit
        }
    }

    private fun settle(fileMs: Long) {
        settling = true
        settleTargetMs = fileMs
    }

    private fun onPosition(ms: Long) {
        if (settling && abs(ms - settleTargetMs) < SETTLE_TOLERANCE_MS) settling = false
        val e = entry ?: return
        if (settling || awaitingStart || ended || e.clipEndMs <= e.clipStartMs || ms < e.clipEndMs) return
        val nextIndex = queue.peekNext(auto = true)
        if (!pauseAtEnd && nextIndex != null && nextIndex != queue.index) {
            val n = queue.items.getOrNull(nextIndex)?.let(entryOf)
            if (n != null && n.uri == e.uri && n.clipStartMs == e.clipEndMs) {
                // Bitişik .cue parçası: motora dokunmadan akış sürer, boşluk olmaz.
                queue.moveTo(nextIndex)
                onTrackChanged?.invoke()
                return
            }
        }
        trackFinished()
    }

    private fun trackFinished() {
        val hold = pauseAtEnd
        if (hold) {
            pauseAtEnd = false
            onPausedAtEnd?.invoke()
        }
        val next = queue.peekNext(auto = true)
        if (next == null) finish() else load(next, paused = hold)
    }

    private fun trackFailed() {
        failures++
        val next = if (failures >= queue.size) null else queue.peekNext(auto = false)
        if (next == null) finish() else load(next, paused = !playIntent)
    }

    private fun finish() {
        ended = true
        playIntent = false
        engine().pause()
        onStateChanged?.invoke()
    }

    private fun load(i: Int, startMs: Long = 0L, paused: Boolean = false) {
        queue.moveTo(i)
        val e = entry ?: return
        ended = false
        playIntent = !paused
        val fileMs = e.clipStartMs + startMs
        settle(fileMs)
        if (e.isClip && fileOpen && !awaitingStart && e.uri == openUri) {
            engine().seekTo(fileMs)
            if (paused) engine().pause() else engine().play()
        } else {
            openUri = e.uri
            fileOpen = false
            awaitingStart = true
            engine().open(e.uri, fileMs, paused)
        }
        onTrackChanged?.invoke()
        onStateChanged?.invoke()
    }
}

/** Müzik ses düzeyi (yüzde): artırma, ReplayGain, geçiş yumuşatması ve kısma çarpımı. */
fun musicVolumePercent(boostPercent: Int, replay: Float, fade: Float, duck: Float): Int =
    (boostPercent * replay * fade * duck).roundToInt().coerceIn(0, 200)
