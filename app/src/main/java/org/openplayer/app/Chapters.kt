package org.openplayer.app

import android.content.Context
import org.json.JSONArray
import kotlin.math.roundToLong

data class Chapter(val startMs: Long, val title: String)

/**
 * Bölümler (chapter) mpv'den okunur: dosya açıldıktan sonra ([MpvEvent.FileLoaded]) `chapter-list`.
 * mpv MKV, MP4 (Nero ve QuickTime), Ogg vb. biçimlerin bölümlerini kendisi çözer.
 */
object Chapters {

    /** Açık dosyanın bölümleri; yoksa boş. */
    fun read(engine: MpvEngine): List<Chapter> = parse(engine.getPropertyJson("chapter-list"))

    /** Çalan bölümün sırası; ilk bölümden önceyse ya da bölüm yoksa -1. */
    fun currentIndex(engine: MpvEngine): Int = engine.getProperty("chapter")?.toIntOrNull() ?: -1

    /** [index]. bölümün başına gider (mpv `chapter` özelliği). */
    fun seekTo(engine: MpvEngine, index: Int) = engine.setProperty("chapter", index.toString())

    /** Geçici uyumluluk: video ekranı dosyayı kendisi ayrıştırmayı bekliyor; artık [read] kullanılır. */
    @Suppress("UNUSED_PARAMETER")
    fun load(context: Context, uri: android.net.Uri): List<Chapter> = emptyList() // MPV-REMOVE

    /**
     * `chapter-list` JSON'u: `[{"title": "...", "time": 12.5}, ...]` (saniye). Başlığı olmayan
     * bölüme sıra numaralı ad verilir ([fallbackTitle] 1'den başlar).
     */
    fun parse(json: String?, fallbackTitle: (Int) -> String = { Tx.s(R.string.chapters_001, it) }): List<Chapter> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Chapter>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sec = o.optDouble("time", Double.NaN)
            if (sec.isNaN() || sec.isInfinite()) continue
            val title = if (o.isNull("title")) "" else o.optString("title", "").trim()
            out += Chapter((sec * 1000).roundToLong().coerceAtLeast(0L), title.ifEmpty { fallbackTitle(out.size + 1) })
        }
        return out.sortedBy { it.startMs }
    }

    /** [positionMs] konumundaki bölümün sırası; ilk bölümden önceyse -1. */
    fun indexAt(chapters: List<Chapter>, positionMs: Long): Int {
        var idx = -1
        for (i in chapters.indices) if (chapters[i].startMs <= positionMs) idx = i else break
        return idx
    }
}

/** Bölüm başlangıçlarını çubuk üzerinde ince çizgilerle gösteren arama çubuğu. */
class ChapterSeekBar(context: Context) : android.widget.SeekBar(context) {
    var startsSec: List<Int> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    private val paint = android.graphics.Paint().apply {
        color = 0xCCFFFFFF.toInt()
        strokeWidth = context.dp(2).toFloat()
    }

    @Synchronized
    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (startsSec.isEmpty() || max <= 0) return
        val left = paddingLeft.toFloat()
        val w = (width - paddingLeft - paddingRight).toFloat()
        val cy = height / 2f
        val half = context.dp(7).toFloat()
        for (s in startsSec) {
            if (s <= 0) continue
            val x = left + w * s / max
            canvas.drawLine(x, cy - half, x, cy + half, paint)
        }
    }
}
