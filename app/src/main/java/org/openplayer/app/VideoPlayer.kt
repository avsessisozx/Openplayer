package org.openplayer.app

import android.net.Uri

/** Oynatma kuyruğundaki video. */
data class QueueItem(val uri: Uri, val title: String)

enum class RepeatMode { OFF, ALL, ONE }

/**
 * Video ekranının oynatıcısı: [MpvEngine] üstünde kuyruk, tekrar, karıştırma ve "bitince dur".
 * mpv'nin kendi listesi kullanılmaz; böylece kaldığı yerden devam ve geçiş kararları ekranda kalır.
 */
class VideoPlayer(val engine: MpvEngine) {

    /** Kuyrukta öğe değişti. [auto]: video bitip sıradakine kendiliğinden geçildi. */
    var onItemChanged: ((item: QueueItem?, repeat: Boolean) -> Unit)? = null
    /** Kuyruk bitti; son kare ekranda kalır. */
    var onEnded: (() -> Unit)? = null
    /** "Bitince dur" uygulandı. */
    var onStoppedAtEnd: (() -> Unit)? = null

    private var queue: List<QueueItem> = emptyList()
    private var index = 0
    private var shuffleBag = ArrayDeque<Int>()

    var isEnded = false
        private set
    var repeatMode = RepeatMode.OFF
    var shuffleModeEnabled = false
        set(value) {
            field = value
            shuffleBag.clear()
        }
    var pauseAtEndOfItems = false
    /** Tam konuma sarma; kapalıysa en yakın anahtar kare. */
    var exactSeek = true

    var videoWidth = 0
        private set
    var videoHeight = 0
        private set

    val mediaItemCount: Int get() = queue.size
    val currentIndex: Int get() = index
    val currentItem: QueueItem? get() = queue.getOrNull(index)
    fun itemAt(i: Int): QueueItem? = queue.getOrNull(i)

    val currentPosition: Long get() = engine.positionMs
    /** Bilinmiyorsa 0. */
    val duration: Long get() = engine.durationMs
    val bufferedPosition: Long get() = ((engine as? MpvPlayer)?.bufferedSec ?: 0.0).times(1000).toLong()
    val isPlaying: Boolean get() = engine.isPlaying
    val playWhenReady: Boolean get() = engine.playWhenReady && !isEnded
    val isIdle: Boolean get() = queue.isEmpty()
    val speed: Float get() = engine.speed

    /** 0..1 */
    var volume = 1f
        set(value) {
            field = value
            engine.setVolume((value * 100).toInt())
        }

    private val listener: (MpvEvent) -> Unit = { e ->
        when (e) {
            is MpvEvent.FileEnded -> if (e.reason == EndReason.EOF) onEof()
            is MpvEvent.VideoSizeChanged -> {
                videoWidth = e.width
                videoHeight = e.height
            }
            is MpvEvent.FileStarted -> isEnded = false
            else -> Unit
        }
    }

    init {
        engine.addListener(listener)
    }

    fun detach() {
        engine.removeListener(listener)
    }

    fun setItems(items: List<QueueItem>, startIndex: Int, startMs: Long, paused: Boolean) {
        queue = items
        index = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        shuffleBag.clear()
        isEnded = false
        videoWidth = 0
        videoHeight = 0
        currentItem?.let { engine.open(it.uri.toString(), startMs, paused) }
    }

    /** Aynı öğeyi aynı konumdan yeniden açar (çözücü değişimi sonrası gibi). */
    fun reopen(paused: Boolean = !engine.playWhenReady) {
        val pos = currentPosition
        currentItem?.let { engine.open(it.uri.toString(), pos, paused) }
    }

    fun play() {
        if (isEnded) seekTo(0)
        engine.play()
    }

    fun pause() = engine.pause()

    fun seekTo(positionMs: Long) {
        isEnded = false
        engine.seekTo(positionMs, exactSeek)
    }

    /** Kuyrukta [i]. öğeye gider. */
    fun seekTo(i: Int, positionMs: Long) {
        if (queue.getOrNull(i) == null) return
        if (i == index) {
            seekTo(positionMs)
            return
        }
        moveTo(i, positionMs, repeat = false)
    }

    fun setSpeed(value: Float) = engine.setSpeed(value)

    fun hasNext(): Boolean = nextIndex(peek = true) != null
    fun hasPrevious(): Boolean = index > 0 || repeatMode == RepeatMode.ALL

    fun seekToNext() {
        nextIndex(peek = false)?.let { moveTo(it, 0L, repeat = false) }
    }

    fun seekToPrevious() {
        val i = when {
            index > 0 -> index - 1
            repeatMode == RepeatMode.ALL -> queue.size - 1
            else -> return
        }
        moveTo(i, 0L, repeat = false)
    }

    private fun moveTo(i: Int, positionMs: Long, repeat: Boolean) {
        index = i
        isEnded = false
        videoWidth = 0
        videoHeight = 0
        val item = queue[i]
        engine.open(item.uri.toString(), positionMs, paused = false)
        onItemChanged?.invoke(item, repeat)
    }

    private fun onEof() {
        if (pauseAtEndOfItems) {
            pauseAtEndOfItems = false
            isEnded = true
            engine.pause()
            onStoppedAtEnd?.invoke()
            return
        }
        if (repeatMode == RepeatMode.ONE) {
            engine.seekTo(0L, true)
            engine.play()
            return
        }
        val next = nextIndex(peek = false)
        if (next != null) {
            moveTo(next, 0L, repeat = next == index)
        } else {
            isEnded = true
            onEnded?.invoke()
        }
    }

    private fun nextIndex(peek: Boolean): Int? {
        if (queue.isEmpty()) return null
        if (shuffleModeEnabled && queue.size > 1) {
            if (shuffleBag.isEmpty()) {
                if (peek) return if (repeatMode == RepeatMode.ALL) 0 else nextInShuffleOrNull()
                shuffleBag.addAll(queue.indices.filter { it != index }.shuffled())
            }
            return if (peek) shuffleBag.firstOrNull() else shuffleBag.removeFirstOrNull()
        }
        return when {
            index + 1 < queue.size -> index + 1
            repeatMode == RepeatMode.ALL -> 0
            else -> null
        }
    }

    private fun nextInShuffleOrNull(): Int? = queue.indices.firstOrNull { it != index }

    companion object {
        private var shared: VideoPlayer? = null

        /** Kuyruk ekran ömründen uzun yaşar: arka plandan dönünce yeni ekran aynı kuyruğu bulur. */
        @Synchronized
        fun shared(engine: MpvEngine): VideoPlayer {
            shared?.takeIf { it.engine === engine }?.let { return it }
            shared?.detach()
            return VideoPlayer(engine).also { shared = it }
        }

        @Synchronized
        fun releaseShared() {
            shared?.let {
                it.detach()
                it.engine.release()
            }
            shared = null
        }
    }
}
