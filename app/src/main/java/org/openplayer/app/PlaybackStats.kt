package org.openplayer.app

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Oynatma ölçümleri: hangi çözücünün çalıştığı, video biçimi ve atlanan kare oranı.
 * Hata raporuna girer; akıcılık bekçisi ([SmoothnessWatchdog]) bu verilerle karar verir.
 */
@UnstableApi
object PlaybackStats {

    @Volatile var decoderName: String = ""
        private set
    @Volatile var videoDesc: String = ""
        private set

    /** Son oynatma özeti (bellekte); uygulama kapanırken [BugReport.notePlayback] ile kalıcılaştırılır. */
    @Volatile var lastReport: String = ""

    private var attachedTo: ExoPlayer? = null

    private val listener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            PlaybackStats.decoderName = decoderName
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            videoDesc = describe(format)
        }
    }

    /** Oynatıcı her (yeniden) kurulduğunda çağrılır; eskisinden ayrılır. */
    fun attach(player: ExoPlayer) {
        if (attachedTo === player) return
        attachedTo?.removeAnalyticsListener(listener)
        attachedTo = player
        decoderName = ""
        player.addAnalyticsListener(listener)
    }

    /** FFmpeg (nextlib) çözücüsü adı "ffmpeg<sürüm>-<kodek>" biçimindedir; Google'ın yazılım çözücüleri c2.android / OMX.google. */
    fun isSoftwareDecoder(): Boolean {
        val n = decoderName.lowercase()
        return n.startsWith("ffmpeg") || n.startsWith("c2.android.") || n.startsWith("omx.google.")
    }

    /** FFmpeg çözücüsü mü (bekçi yalnız bunda devreye girer; MediaCodec yazılım çözücüsünde değil). */
    fun isFfmpegDecoder(): Boolean = decoderName.lowercase().startsWith("ffmpeg")

    fun describe(f: Format): String {
        val sb = StringBuilder()
        if (f.width > 0 && f.height > 0) sb.append(f.width).append('x').append(f.height)
        f.sampleMimeType?.let { sb.append(' ').append(it.substringAfter('/')) }
        f.codecs?.let { sb.append(" (").append(it).append(')') }
        if (f.frameRate > 0f) sb.append(' ').append(String.format(java.util.Locale.ROOT, "%.3f", f.frameRate)).append(" fps")
        val br = if (f.averageBitrate > 0) f.averageBitrate else f.bitrate
        if (br > 0) sb.append(' ').append(br / 1000).append(" kbps")
        val depth = f.colorInfo?.lumaBitdepth ?: -1
        if (depth > 0) sb.append(' ').append(depth).append("-bit")
        return sb.toString()
    }

    fun report(player: ExoPlayer?, dog: SmoothnessWatchdog?): String {
        val sb = StringBuilder()
        val fmt = player?.videoFormat?.let { describe(it) }?.takeIf { it.isNotBlank() } ?: videoDesc
        sb.append("Video: ").append(fmt.ifBlank { "?" }).append('\n')
        sb.append("Çözücü: ").append(decoderName.ifBlank { "?" })
        sb.append(if (isSoftwareDecoder()) " (yazılım)" else " (donanım)").append('\n')
        runCatching {
            val c = player?.videoDecoderCounters
            if (c != null) {
                c.ensureUpdated()
                sb.append("Çizilen kare: ").append(c.renderedOutputBufferCount)
                    .append(" · atlanan: ").append(c.droppedBufferCount)
                    .append(" · anahtar kareye atlama: ").append(c.droppedToKeyframeCount)
                    .append(" · en uzun ardışık atlama: ").append(c.maxConsecutiveDroppedBufferCount).append('\n')
            }
        }
        dog?.let { sb.append("Akıcılık (son pencere): ").append(it.summary()).append('\n') }
        return sb.toString()
    }
}

/**
 * Yazılım (FFmpeg) çözücüyle oynarken saniyede bir örnek alır; son birkaç saniyede kareler
 * sürekli atlanıyor ya da kare hızı videonun hızının çok altındaysa "akıcı değil" der.
 * Yalnızca çağıran uygun koşulları (yerel dosya, 1x hız, oynuyor) sağlıyorsa örnekler.
 */
@UnstableApi
class SmoothnessWatchdog {
    private var haveBase = false
    private var lastRendered = 0
    private var lastDropped = 0
    private val renderedWin = ArrayDeque<Int>()
    private val droppedWin = ArrayDeque<Int>()
    private var badStreak = 0
    private var lastSummary = "-"

    fun reset() {
        haveBase = false
        renderedWin.clear()
        droppedWin.clear()
        badStreak = 0
    }

    fun summary(): String = lastSummary

    /** Saniyede bir çağrılır. true: son saniyeler sürekli akıcı değil. */
    fun sample(player: ExoPlayer, nominalFps: Float): Boolean {
        val c = player.videoDecoderCounters ?: return false
        c.ensureUpdated()
        val rendered = c.renderedOutputBufferCount
        val dropped = c.droppedBufferCount
        if (!haveBase || rendered < lastRendered || dropped < lastDropped) {
            // Çözücü yeniden kuruldu ya da ilk örnek: taban al, karar verme.
            haveBase = true
            lastRendered = rendered
            lastDropped = dropped
            return false
        }
        renderedWin.addLast(rendered - lastRendered)
        droppedWin.addLast(dropped - lastDropped)
        lastRendered = rendered
        lastDropped = dropped
        while (renderedWin.size > WINDOW) {
            renderedWin.removeFirst()
            droppedWin.removeFirst()
        }
        val r = renderedWin.sum()
        val d = droppedWin.sum()
        val secs = renderedWin.size
        val effFps = r.toFloat() / secs
        lastSummary = String.format(java.util.Locale.ROOT, "%.1f/%.1f fps çizildi, atlanan %d/%d", effFps, nominalFps, d, r + d)
        if (secs < WINDOW) return false
        val total = r + d
        val tooManyDrops = total > 0 && d.toFloat() / total > MAX_DROP_RATIO
        // Kare hızı bilinmiyorsa (değişken hızlı videolar) yalnız atlanan kare oranına bakılır.
        val tooSlow = nominalFps > 0f && effFps < nominalFps * MIN_FPS_RATIO
        if (tooManyDrops || tooSlow) badStreak++ else badStreak = 0
        return badStreak >= BAD_STREAK_LIMIT
    }

    companion object {
        private const val WINDOW = 6            // saniye
        private const val MAX_DROP_RATIO = 0.25f
        private const val MIN_FPS_RATIO = 0.7f
        private const val BAD_STREAK_LIMIT = 3  // art arda kötü değerlendirme (toplam ~9 sn)
    }
}
