package org.openplayer.app

import android.util.TypedValue
import android.view.View
import java.io.File
import java.util.Locale

/**
 * Altyazı denetçisi (mpv). Görseli mpv/libass çizer (ASS/SSA tam stil); bu sınıf parça seçer,
 * harici dosyayı mpv'ye verir, ayarları mpv özelliklerine yazar ve hareketleri sürdürür:
 * - Yatay kaydırma: [prepareGhost] mpv'nin altyazısını gizler, şu anki satırı ana katmanda, komşu satırı
 *   ([ghostView]) yan katmanda gösterir (PlayerActivity katmanları kaydırır); [clearGhost] geri açar.
 * - Vurgu kutusu: [setGrab] satırın üstüne yarı saydam kutu koyar (metin mpv'den görünmeye devam eder).
 * - Dikey sürükleme: ayar değişir, [applyStyle] `sub-pos` yazar.
 * - Satır atlama/eşleştirme: harici dosyada kendi ayrıştırdığımız satırlar ([externalCues]); gömülüde
 *   mpv `sub-seek` ([stepEmbedded]).
 * - 360° kipinde [overlayOnly]: mpv altyazısı küre dokusuna gömülmesin diye katmanda gösterilir.
 */
class SubtitleController internal constructor(
    private val engineProvider: () -> MpvEngine?,
    private val styleProvider: () -> SubtitleStyle,
    private val env: Env,
) {

    /** Android'e bağlı işler; birim testte sahtesi kullanılır. */
    interface Env {
        /** Altyazı alanının yüksekliği (piksel). */
        val heightPx: Int
        fun spToPx(sp: Float): Float
        /** Ayar değerine göre (sub-fonts-dir, sub-font); varsayılan yazı tipinde aile null. */
        fun font(value: String): Pair<String?, String?>
        /** mpv'ye verilen harici altyazı kopyalarının klasörü. */
        val tempDir: File
        val main: Overlay?
        val ghost: Overlay?
    }

    /** Düz metin katmanı ([SubtitleOverlayView]). */
    interface Overlay {
        fun show(line: CharSequence?, style: SubtitleStyle, textPx: Float, box: Int, ghostText: Boolean, assAlignment: Int)
        fun hide()
    }

    /** PlayerActivity için: [view] ana katman ([SubtitleOverlayView] olmalı). */
    constructor(host: PlayerActivity, view: View) : this(
        { MpvEngines.current?.takeIf { !it.isReleased } ?: kotlin.runCatching { MpvEngines.get(host) }.getOrNull() },
        { SubtitleStyle.from(host.settings) },
        AndroidEnv(host, view),
    )

    init {
        // Oynatıcı alanı boyut değiştirince (döndürme, PiP) yazı ölçeği yeniden hesaplanır.
        (env as? AndroidEnv)?.onResized { applyStyle() }
    }

    enum class Mode { OFF, EMBEDDED, EXTERNAL }

    var mode = Mode.OFF
        private set
    var externalName: String? = null
        private set

    /** Altyazı gecikmesi (ms); artı = geç göster. mpv `sub-delay`; gömülüde eksi değer de çalışır. */
    var delayMs = 0L
        set(value) {
            field = value
            engine()?.setProperty("sub-delay", seconds(value))
        }

    /** Altyazı hız çarpanı (kare hızı dönüşümü; 1.0 = değişmez). mpv `sub-speed` = 1 / speed. */
    var speed = 1.0
        set(value) {
            field = if (value > 0) value else 1.0
            engine()?.setProperty("sub-speed", String.format(Locale.ROOT, "%.6f", 1.0 / field))
        }

    /** Komşu satır önizlemesi için ikinci katman; PlayerActivity atar. */
    var ghostView: View? = null
        set(value) {
            field = value
            (env as? AndroidEnv)?.ghostView = value
        }

    /** 360°/VR kipi: altyazı mpv yerine katmanda gösterilir. */
    var overlayOnly = false
        set(value) {
            if (field == value) return
            field = value
            refreshOverlay()
        }

    /** Ekranda şu an altyazı satırı var mı (dokunma hareketleri için). */
    var showing = false
        private set

    private var external: List<SubCue> = emptyList()
    private var externalText: String? = null
    private var externalFile: File? = null
    private var pendingExternal = false
    private var embeddedImage = false
    private var fileLoaded = false
    private var grab = false
    private var swiping = false
    private var currentText: String? = null
    private var lastItalic: Boolean? = null
    private var mpvVisible: Boolean? = null

    private var bound: MpvEngine? = null
    private var watch: AutoCloseable? = null
    private val listener: (MpvEvent) -> Unit = { onEvent(it) }

    val hasExternal: Boolean get() = mode == Mode.EXTERNAL && external.isNotEmpty()

    /** Çeviri ve satır eşleştirme için: yüklü harici altyazı satırları (gömülü veya kapalıyken boş). */
    val externalCues: List<SubCue> get() = if (mode == Mode.EXTERNAL) external else emptyList()

    // ------------------------------------------------------------------ motor bağlantısı

    private fun engine(): MpvEngine? {
        val e = engineProvider() ?: return null
        if (e !== bound) bind(e)
        return e
    }

    private fun bind(e: MpvEngine) {
        unbind()
        bound = e
        mpvVisible = null
        fileLoaded = e.tracks().isNotEmpty()
        e.addListener(listener)
        e.setProperty("sub-delay", seconds(delayMs))
        e.setProperty("sub-speed", String.format(Locale.ROOT, "%.6f", 1.0 / speed))
        watch = e.observe("sub-text") { t ->
            currentText = t
            updateShowing()
            if (swiping || grab || overlayOnly) refreshOverlay()
        }
    }

    private fun unbind() {
        bound?.removeListener(listener)
        runCatching { watch?.close() }
        watch = null
        bound = null
    }

    private fun onEvent(ev: MpvEvent) {
        when (ev) {
            is MpvEvent.FileStarted -> fileLoaded = false
            is MpvEvent.FileLoaded -> {
                fileLoaded = true
                if (pendingExternal && mode == Mode.EXTERNAL) addExternalToMpv()
            }
            else -> Unit
        }
    }

    /** Etkinlik kapanırken: gözlemciyi bırakır, mpv altyazısını görünür bırakır (motor yaşamaya devam eder). */
    fun release() {
        bound?.setProperty("sub-visibility", "yes")
        unbind()
        env.main?.hide()
        env.ghost?.hide()
    }

    // ------------------------------------------------------------------ görünüm

    fun applyStyle() {
        val e = engine()
        val s = styleProvider()
        if (e != null) {
            val (dir, family) = env.font(s.font)
            if (dir != null) e.setProperty("sub-fonts-dir", dir)
            for ((k, v) in SubtitleLook.mpvProperties(s, env.heightPx, env.spToPx(s.sizeSp), family)) e.setProperty(k, v)
        }
        // "/" italik ayarı harici dosyanın mpv kopyasını değiştirir.
        if (lastItalic != null && lastItalic != s.italicSlash && mode == Mode.EXTERNAL && externalText != null) {
            lastItalic = s.italicSlash
            writeExternal()
            if (fileLoaded) addExternalToMpv() else pendingExternal = true
        }
        lastItalic = s.italicSlash
        refreshOverlay()
    }

    /** Parmak altyazıya değince vurgu kutusunu açar/kapatır. */
    fun setGrab(on: Boolean) {
        if (grab == on) return
        grab = on
        refreshOverlay()
    }

    /**
     * Kaydırma başlar ya da yön değişir: sonraki/önceki harici satır ikinci katmanda gösterilir.
     * Satır yoksa (ya da gömülü altyazıda) yan katman boş kalır; true dönerse gösterilecek satır var.
     */
    fun prepareGhost(next: Boolean): Boolean {
        engine()
        swiping = true
        val pos = bound?.positionMs ?: 0L
        val cue = if (mode != Mode.EXTERNAL) null else {
            if (next) external.firstOrNull { videoMs(it) > pos + 400 } else external.lastOrNull { videoMs(it) < pos - 1200 }
        }
        val s = styleProvider()
        if (cue == null) env.ghost?.hide()
        else env.ghost?.show(cue.text, s, env.spToPx(s.sizeSp), 0, false, cue.alignment)
        refreshOverlay()
        return cue != null
    }

    /** Kaydırma bitti (geri dönüş animasyonu sonunda): katmanlar kapanır, mpv altyazısı geri gelir. */
    fun clearGhost() {
        swiping = false
        env.ghost?.hide()
        refreshOverlay()
    }

    private fun refreshOverlay() {
        val s = styleProvider()
        val px = env.spToPx(s.sizeSp)
        val t = currentText
        val main = env.main
        when {
            overlayOnly || swiping -> {
                val box = if (grab && (s.background ushr 24) < 0x60) GRAB_BACKGROUND else 0
                main?.show(t, s, px, box, false, 2)
            }
            grab -> main?.show(t, s, px, GRAB_BACKGROUND, true, 2)
            else -> main?.hide()
        }
        setMpvVisible(!(overlayOnly || (swiping && !t.isNullOrBlank())))
    }

    private fun setMpvVisible(on: Boolean) {
        if (mpvVisible == on) return
        val e = bound ?: return
        mpvVisible = on
        e.setProperty("sub-visibility", if (on) "yes" else "no")
    }

    private fun updateShowing() {
        showing = mode != Mode.OFF && (!currentText.isNullOrBlank() || (mode == Mode.EMBEDDED && embeddedImage))
    }

    // ------------------------------------------------------------------ parça seçimi

    fun setOff() {
        mode = Mode.OFF
        external = emptyList()
        externalName = null
        externalText = null
        pendingExternal = false
        embeddedImage = false
        engine()?.let { e ->
            removeExternalTracks(e)
            e.setProperty("sid", "no")
        }
        updateShowing()
    }

    /** Gömülü parçayı seçer; [trackId] mpv `sid` değeridir ([MpvTrack.id], [EmbeddedTrack.id]). */
    fun useEmbedded(trackId: Int) {
        mode = Mode.EMBEDDED
        external = emptyList()
        externalName = null
        externalText = null
        pendingExternal = false
        val e = engine()
        if (e != null) {
            removeExternalTracks(e)
            embeddedImage = e.tracks().firstOrNull { it.type == MpvTrackType.SUB && it.id == trackId }?.isImageSubtitle == true
            e.setProperty("sid", trackId.toString())
        }
        updateShowing()
    }

    /**
     * Harici altyazıyı açar. [cues]: kendi ayrıştırıcımızın satırları (atlama, eşleştirme, çeviri için).
     * [text]: dosyanın çözülmüş metni (kullanıcının karakter kümesi ayarıyla); mpv'ye UTF-8 kopyası verilir.
     * SRT/ASS/SSA/WebVTT olduğu gibi, diğer biçimler (SAMI, MicroDVD, MPL2, TMPlayer...) SRT'ye çevrilerek.
     * [text] null ise satırlardan SRT yazılır (ASS stili kaybolur).
     */
    fun useExternal(cues: List<SubCue>, name: String, text: String? = null) {
        mode = Mode.EXTERNAL
        external = cues
        externalName = name
        externalText = text
        embeddedImage = false
        lastItalic = styleProvider().italicSlash
        writeExternal()
        if (engine() != null && fileLoaded) addExternalToMpv() else pendingExternal = true
        updateShowing()
    }

    private fun writeExternal() {
        val name = externalName ?: return
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val dir = env.tempDir.apply { mkdirs() }
        externalFile?.delete()
        val raw = externalText
        val italic = styleProvider().italicSlash
        val (outExt, content) = when {
            raw != null && ext in setOf("ass", "ssa") -> ext to raw
            raw != null && (ext == "srt" || ext == "vtt") -> ext to if (italic) SubtitleParser.italicSlashMarkup(raw) else raw
            else -> {
                val srt = SrtWriter.write(external)
                "srt" to if (italic) SubtitleParser.italicSlashMarkup(srt) else srt
            }
        }
        val f = File(dir, "external_${System.nanoTime()}.$outExt")
        f.writeText(content, Charsets.UTF_8)
        externalFile = f
    }

    private fun addExternalToMpv() {
        val e = bound ?: engine() ?: return
        val f = externalFile ?: return
        pendingExternal = false
        removeExternalTracks(e)
        e.command("sub-add", f.absolutePath, "select", externalName ?: f.name)
    }

    private fun removeExternalTracks(e: MpvEngine) {
        for (t in e.tracks()) if (t.type == MpvTrackType.SUB && t.isExternal) e.command("sub-remove", t.id.toString())
    }

    // ------------------------------------------------------------------ satır atlama

    /** Konumdan sonraki harici altyazı satırının video zamanı (yoksa null). */
    fun nextCueMs(positionMs: Long): Long? =
        external.firstOrNull { videoMs(it) > positionMs + 400 }?.let { videoMs(it) }

    fun previousCueMs(positionMs: Long): Long? =
        external.lastOrNull { videoMs(it) < positionMs - 1200 }?.let { videoMs(it) }

    /** Gömülü parçada sonraki/önceki satıra mpv `sub-seek` ile gider; gömülü değilse false. */
    fun stepEmbedded(next: Boolean): Boolean {
        if (mode != Mode.EMBEDDED) return false
        val e = engine() ?: return false
        e.command("sub-seek", if (next) "1" else "-1")
        return true
    }

    private fun videoMs(c: SubCue): Long = (c.startMs / speed).toLong() + delayMs

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.3f", ms / 1000.0)

    internal companion object {
        /** Dokunma vurgusu: yarı saydam gri kutu. */
        const val GRAB_BACKGROUND = 0x66808080
    }

    /** Gerçek Android ortamı. */
    private class AndroidEnv(private val host: PlayerActivity, private val mainView: View) : Env {
        var ghostView: View? = null
        fun onResized(block: () -> Unit) {
            (mainView as? SubtitleOverlayView)?.onResized = block
        }
        override val heightPx: Int
            get() = mainView.height.takeIf { it > 0 } ?: host.resources.displayMetrics.heightPixels
        override fun spToPx(sp: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, host.resources.displayMetrics)
        override fun font(value: String): Pair<String?, String?> {
            val (dir, family) = SubtitleLook.mpvFont(host, value)
            return (dir ?: SubtitleLook.mpvFontDir(host).absolutePath) to family
        }
        override val tempDir: File get() = File(host.cacheDir, "mpv-subs")
        override val main: Overlay? get() = (mainView as? SubtitleOverlayView)?.let { ViewOverlay(it) }
        override val ghost: Overlay? get() = (ghostView as? SubtitleOverlayView)?.let { ViewOverlay(it) }
    }

    private class ViewOverlay(private val v: SubtitleOverlayView) : Overlay {
        override fun show(line: CharSequence?, style: SubtitleStyle, textPx: Float, box: Int, ghostText: Boolean, assAlignment: Int) =
            v.show(line, style, textPx, box, ghostText, assAlignment)
        override fun hide() = v.hide()
    }
}
