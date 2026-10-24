package org.openplayer.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/**
 * Altyazıyı tek bir SubtitleView üzerinden gösterir.
 * - Harici dosyalar kendi ayrıştırıcımızla okunur; gecikme ve satır atlama bunlarda tam çalışır.
 * - Gömülü parçalar ExoPlayer'dan gelir; gecikme sadece pozitif yönde (geç göster) desteklenir.
 */
class SubtitleController(private val host: PlayerActivity, private val view: SubtitleView) {

    enum class Mode { OFF, EMBEDDED, EXTERNAL }

    var mode = Mode.OFF
        private set
    var externalName: String? = null
        private set
    var delayMs = 0L

    /** Altyazı hız çarpanı (kare hızı dönüşümü için; 1.0 = değişmez). */
    var speed = 1.0

    private var external: List<SubCue> = emptyList()
    private var maxDurationMs = 0L
    private var lastKey: List<Int> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val pendingEmbedded = ArrayList<Runnable>()

    private var align = 1
    private var italicSlash = false

    private fun styled(cues: List<Cue>): List<Cue> =
        if (align == 1 && !italicSlash) cues else cues.map { SubtitleLook.align(it, align, italicSlash) }

    val hasExternal: Boolean get() = mode == Mode.EXTERNAL && external.isNotEmpty()

    /** Çeviri için: yüklü harici altyazı satırları (gömülü veya kapalıyken boş). */
    val externalCues: List<SubCue> get() = if (mode == Mode.EXTERNAL) external else emptyList()

    fun applyStyle() {
        val s = host.settings
        view.setStyle(
            CaptionStyleCompat(
                s.subtitleColor,
                s.subtitleBackground,
                Color.TRANSPARENT,
                s.subtitleEdge,
                s.subtitleEdgeColor,
                SubtitleLook.typeface(host, s.subtitleFont, s.subtitleBold)
            )
        )
        align = s.subtitleAlign
        italicSlash = s.subtitleItalicSlash
        // Görünüm değişince ekrandaki satırlar yeni ayarla yeniden çizilsin.
        if (mode == Mode.EXTERNAL) lastKey = listOf(-1)
        view.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, s.subtitleSizeSp)
        view.setBottomPaddingFraction(s.subtitleBottomFraction)
    }

    fun setOff() {
        mode = Mode.OFF
        external = emptyList()
        externalName = null
        clearCues()
        val p = host.player
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun useEmbedded(group: Tracks.Group) {
        mode = Mode.EMBEDDED
        external = emptyList()
        externalName = null
        clearCues()
        val p = host.player
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    fun useExternal(cues: List<SubCue>, name: String) {
        val p = host.player
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        mode = Mode.EXTERNAL
        external = cues
        externalName = name
        maxDurationMs = cues.maxOfOrNull { it.endMs - it.startMs } ?: 0L
        lastKey = listOf(-1)
        clearCues()
        tick(p.currentPosition)
    }

    private fun clearCues() {
        pendingEmbedded.forEach { handler.removeCallbacks(it) }
        pendingEmbedded.clear()
        lastKey = emptyList()
        view.setCues(emptyList())
    }

    fun onEmbeddedCues(cues: List<Cue>) {
        if (mode != Mode.EMBEDDED) return
        if (delayMs > 0) {
            val r = Runnable { view.setCues(styled(cues)) }
            pendingEmbedded += r
            handler.postDelayed(r, delayMs)
        } else {
            view.setCues(styled(cues))
        }
    }

    /** Oynatma konumuna göre harici altyazıyı günceller. Yaklaşık 100 ms'de bir çağrılır. */
    fun tick(positionMs: Long) {
        if (mode != Mode.EXTERNAL) return
        val t = ((positionMs - delayMs) * speed).toLong()
        val active = activeIndexes(t)
        if (active != lastKey) {
            lastKey = active
            view.setCues(styled(active.map { toCue(external[it]) }))
        }
    }

    /** ASS hizalamasına göre üst/orta konumlu satır oluşturur; alt satırlar varsayılan konumda kalır. */
    private fun toCue(sub: SubCue): Cue {
        val b = Cue.Builder().setText(sub.text)
        when (sub.alignment) {
            7, 8, 9 -> b.setLine(0.06f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
            4, 5, 6 -> b.setLine(0.5f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        }
        return b.build()
    }

    private fun activeIndexes(t: Long): List<Int> {
        var lo = 0
        var hi = external.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (external[mid].startMs <= t) lo = mid + 1 else hi = mid
        }
        val result = ArrayList<Int>()
        var i = lo - 1
        var steps = 0
        while (i >= 0 && steps < 12) {
            if (external[i].endMs > t) result.add(0, i)
            if (t - external[i].startMs > maxDurationMs) break
            i--
            steps++
        }
        return result
    }

    /** Konumdan sonraki altyazı satırının video zamanı (yoksa null). */
    fun nextCueMs(positionMs: Long): Long? =
        external.firstOrNull { videoMs(it) > positionMs + 400 }?.let { videoMs(it) }

    fun previousCueMs(positionMs: Long): Long? =
        external.lastOrNull { videoMs(it) < positionMs - 1200 }?.let { videoMs(it) }

    private fun videoMs(c: SubCue): Long = (c.startMs / speed).toLong() + delayMs
}
