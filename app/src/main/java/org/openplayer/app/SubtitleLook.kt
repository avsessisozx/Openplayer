package org.openplayer.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.util.Locale

/**
 * Altyazı görünümü. Asıl altyazıyı mpv/libass çizer; burada kullanıcı ayarları mpv özelliklerine
 * çevrilir ([mpvProperties]) ve kaydırma/vurgu/360° için kullanılan düz katman ([SubtitleOverlayView]) durur.
 *
 * Yazı tipi kararı (emülatörde doğrulandı): AAR'ın `assets/subfont.ttf` dosyası "Droid Sans Fallback"tir
 * (42.000+ glif: Latin/Türkçe, Kiril, Yunan, Arapça, İbranice, Tay, Devanagari, Çince, Japonca, Korece).
 * Motor `config-dir` = filesDir olduğundan mpv onu varsayılan yazı tipi olarak kullanır; Arapça
 * HarfBuzz/FriBidi ile doğru birleşir. libass'ın Android'de yazı tipi sağlayıcısı yoktur ("can't find
 * selected font provider" yalnız uyarıdır), bu yüzden `sub-font-provider=none`. Kullanıcı yazı tipi
 * seçerse tek dosya [mpvFontDir] klasörüne bağlanır ve `sub-fonts-dir` + `sub-font` oynarken
 * değiştirilir; o yazı tipinde olmayan glifler yine subfont.ttf'ten gelir.
 */
object SubtitleLook {

    // Değerler eski CaptionStyleCompat sabitleriyle aynı: kayıtlı ayarlar geçerli kalır.
    const val EDGE_NONE = 0
    const val EDGE_OUTLINE = 1
    const val EDGE_SHADOW = 2
    const val EDGE_RAISED = 3
    const val EDGE_DEPRESSED = 4

    val edges = listOf(
        EDGE_NONE to Tx.s(R.string.subtitlelo_006),
        EDGE_OUTLINE to Tx.s(R.string.playermenu_033),
        EDGE_SHADOW to Tx.s(R.string.subtitlelo_001),
        EDGE_RAISED to Tx.s(R.string.subtitlelo_007),
        EDGE_DEPRESSED to Tx.s(R.string.subtitlelo_002),
    )

    val builtInFonts = listOf(
        "default" to Tx.s(R.string.subtitlelo_003),
        "sans" to "Sans",
        "serif" to "Serif",
        "mono" to Tx.s(R.string.subtitlelo_004),
        "condensed" to Tx.s(R.string.subtitlelo_008),
        "medium" to Tx.s(R.string.subtitlelo_005),
    )

    val alignLabels = listOf(Tx.s(R.string.gesturecon_017), Tx.s(R.string.gesturecon_018), Tx.s(R.string.gesturecon_007))

    private const val MAX_FONT_BYTES = 30L * 1024 * 1024
    private var cachedName: String? = null
    private var cachedFace: Typeface? = null

    fun fontDir(context: Context): File = File(context.filesDir, "fonts").apply { mkdirs() }

    fun importedFonts(context: Context): List<String> =
        fontDir(context).listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()

    fun fontLabel(context: Context, value: String): String =
        if (value.startsWith("file:")) value.removePrefix("file:")
        else builtInFonts.firstOrNull { it.first == value }?.second ?: Tx.s(R.string.subtitlelo_003)

    /** Katman (kaydırma/360°) için Android yazı tipi. */
    fun typeface(context: Context, value: String, bold: Boolean): Typeface {
        val base: Typeface = when {
            value.startsWith("file:") -> loadFile(context, value.removePrefix("file:")) ?: Typeface.DEFAULT
            value == "sans" -> Typeface.SANS_SERIF
            value == "serif" -> Typeface.SERIF
            value == "mono" -> Typeface.MONOSPACE
            value == "condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            value == "medium" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
        return if (bold) Typeface.create(base, Typeface.BOLD) else base
    }

    private fun loadFile(context: Context, name: String): Typeface? {
        if (cachedName == name) return cachedFace
        val f = File(fontDir(context), File(name).name)
        val face = if (f.isFile) runCatching { Typeface.createFromFile(f) }.getOrNull() else null
        cachedName = name
        cachedFace = face
        return face
    }

    /** Seçilen .ttf/.otf dosyasını uygulama klasörüne kopyalar; başarılıysa ayar değerini döndürür. */
    fun importFont(context: Context, uri: Uri): String? {
        val raw = SubtitleFiles.displayName(context, uri)
        val name = UriGuard.safeFileName(raw)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("ttf", "otf", "ttc")) return null
        val target = File(fontDir(context), name)
        val tmp = File(fontDir(context), "$name.part")
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_FONT_BYTES) return@use false
                        out.write(buf, 0, n)
                    }
                    true
                }
            } ?: false
        }.getOrDefault(false)
        if (!ok) {
            tmp.delete()
            return null
        }
        // Geçerli bir yazı tipi mi?
        val valid = runCatching { Typeface.createFromFile(tmp) }.getOrNull() != null
        if (!valid) {
            tmp.delete()
            return null
        }
        target.delete()
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return null
        }
        cachedName = null
        return "file:$name"
    }

    fun deleteFont(context: Context, name: String) {
        File(fontDir(context), File(name).name).delete()
        cachedName = null
    }

    /** "/" ile başlayan satırları italik yapar (MX'teki gibi). */
    fun italicSlash(text: CharSequence): CharSequence {
        if (!text.contains('/')) return text
        val lines = text.split('\n')
        if (lines.none { it.trimStart().startsWith("/") }) return text
        val out = SpannableStringBuilder()
        var start = 0
        lines.forEachIndexed { i, line ->
            val end = start + line.length
            val sub = text.subSequence(start, end)
            val trimmed = line.trimStart()
            if (trimmed.startsWith("/")) {
                val cut = line.length - trimmed.length + 1
                val from = out.length
                out.append(sub.subSequence(cut, sub.length))
                out.setSpan(StyleSpan(Typeface.ITALIC), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                out.append(sub)
            }
            if (i < lines.size - 1) out.append('\n')
            start = end + 1
        }
        return out
    }

    // ------------------------------------------------------------------ mpv eşlemesi

    /** mpv'nin metin altyazı ölçeği: yazı boyutu 720 piksel yükseklikli pencereye göre. */
    private const val MPV_REF_HEIGHT = 720f

    /**
     * libass yazı boyutu satır yüksekliğidir (yükselen + alçalan), Android textSize ise em'dir.
     * Droid Sans Fallback/Roboto için oran ~1,17 (emülatörde 44 → ~59 px em ölçüldü).
     */
    private const val ASS_EM_RATIO = 1.17f

    /**
     * Kullanıcı ayarlarını mpv özelliklerine çevirir. [heightPx]: altyazı alanının (oynatıcı görünümü)
     * yüksekliği, [textPx]: kullanıcı boyutunun piksel karşılığı. [fontFamily] null ise varsayılan yazı tipi.
     *
     * `sub-ass-override=yes`: ASS/SSA parçaları kendi stiliyle (libass) çizilir; boyut/renk/kenar/yazı tipi
     * yalnız düz metin altyazılara (SRT, WebVTT, mov_text...) uygulanır. `scale` ya da `force` ASS
     * yerleşimini bozabildiği için seçilmedi. `sub-pos` konumu belirtilmemiş ASS satırlarına da uygulanır.
     */
    fun mpvProperties(s: SubtitleStyle, heightPx: Int, textPx: Float, fontFamily: String?): List<Pair<String, String>> {
        val h = heightPx.coerceAtLeast(1)
        val size = (textPx * MPV_REF_HEIGHT / h * ASS_EM_RATIO).coerceIn(8f, 200f)
        val (outline, shadow) = when (s.edge) {
            EDGE_NONE -> 0f to 0f
            EDGE_SHADOW -> 0f to size * 0.05f
            EDGE_RAISED -> size * 0.02f to size * 0.04f
            EDGE_DEPRESSED -> size * 0.03f to 0f
            else -> size * 0.06f to 0f
        }
        val box = (s.background ushr 24) > 0
        val list = ArrayList<Pair<String, String>>()
        list += "sub-ass-override" to "yes"
        list += "sub-font-provider" to "none"
        list += "sub-scale-with-window" to "yes"
        list += "sub-scale" to "1"
        list += "sub-margin-y" to "0"
        list += "sub-font-size" to fmt(size)
        list += "sub-color" to color(s.color)
        list += "sub-bold" to if (s.bold) "yes" else "no"
        list += "sub-italic" to "no"
        list += "sub-font" to (fontFamily ?: "sans-serif")
        list += "sub-align-x" to when (s.align) { 0 -> "left"; 2 -> "right"; else -> "center" }
        list += "sub-justify" to "auto"
        // background-box + sub-back-color emülatörde doğrulandı (yarı saydam kutu, metin ve kenar korunur).
        list += "sub-border-style" to if (box) "background-box" else "outline-and-shadow"
        list += "sub-back-color" to color(if (box) s.background else withAlpha(s.edgeColor, 0xB0))
        list += "sub-outline-color" to color(if (s.edge == EDGE_DEPRESSED) darker(s.edgeColor) else s.edgeColor)
        list += "sub-shadow-color" to color(withAlpha(s.edgeColor, 0xB0))
        list += "sub-outline-size" to fmt(outline)
        list += "sub-shadow-offset" to fmt(shadow)
        // Konum en sona: mpv bazı stil değişikliklerini ancak sıradaki yeniden çizimde gösteriyor;
        // sub-pos değişimi çizimi tetikler (emülatörde gözlendi).
        list += "sub-pos" to fmt((100f - s.bottomFraction * 100f).coerceIn(0f, 150f))
        return list
    }

    /** mpv renk biçimi: `#AARRGGBB`. */
    fun color(argb: Int): String = String.format(Locale.ROOT, "#%08X", argb)

    private fun fmt(f: Float) = String.format(Locale.ROOT, "%.2f", f)
    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
    private fun darker(c: Int) = (c and 0xFF000000.toInt()) or ((c and 0x00FEFEFE) ushr 1)

    // ------------------------------------------------------------------ mpv yazı tipi

    /** mpv'nin yazı tipi klasörü: yalnız seçili yazı tipinin kopyası/bağlantısı durur. */
    fun mpvFontDir(context: Context): File = File(context.filesDir, "mpv-fonts").apply { mkdirs() }

    private val SYSTEM_CANDIDATES = mapOf(
        "sans" to listOf("Roboto-Regular.ttf", "RobotoStatic-Regular.ttf", "DroidSans.ttf"),
        "serif" to listOf("NotoSerif-Regular.ttf", "DroidSerif-Regular.ttf"),
        "mono" to listOf("DroidSansMono.ttf", "CutiveMono.ttf"),
        "condensed" to listOf("RobotoCondensed-Regular.ttf", "Roboto-Regular.ttf"),
        "medium" to listOf("Roboto-Medium.ttf", "Roboto-Regular.ttf"),
    )

    /**
     * Ayar değerine göre mpv yazı tipi: (klasör, aile adı). Varsayılan için (null, null): mpv subfont.ttf kullanır.
     * Dosya sistemi işi yapar; sonuç değer başına önbellekte tutulur.
     */
    @Synchronized
    fun mpvFont(context: Context, value: String): Pair<String?, String?> {
        if (value == mpvFontKey) return mpvFontCache
        val src: File? = when {
            value.startsWith("file:") -> File(fontDir(context), File(value.removePrefix("file:")).name).takeIf { it.isFile }
            else -> SYSTEM_CANDIDATES[value]?.map { File("/system/fonts", it) }?.firstOrNull { it.isFile }
        }
        val result: Pair<String?, String?> = if (src == null) null to null else runCatching {
            val dir = mpvFontDir(context)
            dir.listFiles()?.forEach { it.delete() }
            val link = File(dir, src.name)
            val linked = runCatching { android.system.Os.symlink(src.absolutePath, link.absolutePath); true }.getOrDefault(false)
            if (!linked) src.copyTo(link, overwrite = true)
            val family = src.inputStream().use { FontNames.family(it.readNBytesCompat(4 shl 20)) }
            if (family == null) null to null else dir.absolutePath to family
        }.getOrDefault(null to null)
        mpvFontKey = value
        mpvFontCache = result
        return result
    }

    private var mpvFontKey: String? = null
    private var mpvFontCache: Pair<String?, String?> = null to null

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

/** Ayarlardan derlenen altyazı görünümü (Android'e bağlı değil). */
data class SubtitleStyle(
    val sizeSp: Float = 20f,
    val color: Int = 0xFFFFFFFF.toInt(),
    val background: Int = 0,
    val edge: Int = SubtitleLook.EDGE_OUTLINE,
    val edgeColor: Int = 0xFF000000.toInt(),
    val font: String = "default",
    val bold: Boolean = false,
    /** 0 sol, 1 orta, 2 sağ. */
    val align: Int = 1,
    val italicSlash: Boolean = false,
    /** Alt kenardan uzaklık, görünüm yüksekliğinin oranı (0..0,6). */
    val bottomFraction: Float = 0.08f,
) {
    companion object {
        fun from(s: AppSettings) = SubtitleStyle(
            sizeSp = s.subtitleSizeSp,
            color = s.subtitleColor,
            background = s.subtitleBackground,
            edge = s.subtitleEdge,
            edgeColor = s.subtitleEdgeColor,
            font = s.subtitleFont,
            bold = s.subtitleBold,
            align = s.subtitleAlign,
            italicSlash = s.subtitleItalicSlash,
            bottomFraction = s.subtitleBottomFraction,
        )
    }
}

/** TrueType/OpenType `name` tablosundan aile adı (TTC'de ilk yazı tipi). */
object FontNames {
    fun family(d: ByteArray): String? {
        fun u16(o: Int) = if (o + 2 <= d.size) ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF) else -1
        fun u32(o: Int) = if (o + 4 <= d.size) (u16(o).toLong() shl 16) or u16(o + 2).toLong() else -1L
        var base = 0
        if (d.size >= 16 && String(d, 0, 4, Charsets.ISO_8859_1) == "ttcf") base = u32(12).toInt()
        val numTables = u16(base + 4)
        if (numTables <= 0) return null
        var nameOff = -1
        for (i in 0 until numTables) {
            val rec = base + 12 + i * 16
            if (rec + 16 > d.size) return null
            if (String(d, rec, 4, Charsets.ISO_8859_1) == "name") nameOff = u32(rec + 8).toInt()
        }
        if (nameOff < 0) return null
        val count = u16(nameOff + 2)
        val strings = nameOff + u16(nameOff + 4)
        var best: String? = null
        var bestScore = -1
        for (i in 0 until count) {
            val r = nameOff + 6 + i * 12
            val platform = u16(r); val lang = u16(r + 4); val id = u16(r + 6)
            val len = u16(r + 8); val off = u16(r + 10)
            if (id != 1 && id != 16) continue
            val start = strings + off
            if (len <= 0 || start + len > d.size) continue
            val text = when (platform) {
                3, 0 -> String(d, start, len, Charsets.UTF_16BE)
                1 -> String(d, start, len, Charsets.ISO_8859_1)
                else -> continue
            }.trim()
            if (text.isEmpty()) continue
            // libass aile adını nameID 1 ile eşler; İngilizce Windows kaydı en iyisi.
            val score = (if (id == 1) 4 else 0) + (if (platform == 3) 2 else 0) + (if (lang == 0x409 || platform != 3) 1 else 0)
            if (score > bestScore) { best = text; bestScore = score }
        }
        return best
    }
}

/**
 * Altyazı katmanı: mpv'nin çizdiği altyazının üstünde duran düz metin. Yalnız şu anlarda görünür:
 * kaydırma (metin parmakla gelir), vurgu kutusu (parmak altyazıdayken) ve 360° kipi (mpv'nin altyazısı
 * küre dokusuna gömülmesin diye). Dokunmaları almaz.
 */
class SubtitleOverlayView(context: Context) : FrameLayout(context) {

    private val text = TextView(context).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        includeFontPadding = false
    }

    /** Boyut değişince (altyazı ölçeği yeniden hesaplansın diye). */
    var onResized: (() -> Unit)? = null

    init {
        isClickable = false
        isFocusable = false
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        text.visibility = GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) post { onResized?.invoke() }
    }

    /**
     * [line] null/boşsa gizler. [ghostText] true ise metin saydam çizilir (yalnız kutu: mpv'nin metni
     * altta görünür). [box] kutu rengi (0 = kutusuz). [assAlignment] ASS sayı tuşu (7-9 üst, 4-6 orta).
     */
    fun show(line: CharSequence?, style: SubtitleStyle, textPx: Float, box: Int, ghostText: Boolean, assAlignment: Int = 2) {
        if (line.isNullOrBlank()) {
            text.visibility = GONE
            return
        }
        val shown = if (style.italicSlash) SubtitleLook.italicSlash(line) else line
        text.text = shown
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
        text.typeface = SubtitleLook.typeface(context, style.font, style.bold)
        text.setTextColor(if (ghostText) Color.TRANSPARENT else style.color)
        when {
            ghostText || style.edge == SubtitleLook.EDGE_NONE -> text.setShadowLayer(0f, 0f, 0f, 0)
            style.edge == SubtitleLook.EDGE_SHADOW || style.edge == SubtitleLook.EDGE_RAISED ->
                text.setShadowLayer(textPx * 0.08f, textPx * 0.05f, textPx * 0.05f, style.edgeColor)
            // Kenar çizgisi: ötelenmemiş gölgeyle yaklaşık (geçici katman için yeterli).
            else -> text.setShadowLayer(textPx * 0.12f, 0f, 0f, style.edgeColor)
        }
        val bg = when {
            box != 0 -> box
            ghostText -> 0
            else -> style.background
        }
        text.setBackgroundColor(bg)
        val pad = (textPx * 0.25f).toInt()
        text.setPadding(pad, pad / 3, pad, pad / 3)
        val h = height
        val lp = text.layoutParams as LayoutParams
        lp.gravity = when (assAlignment) {
            7, 8, 9 -> Gravity.TOP
            4, 5, 6 -> Gravity.CENTER_VERTICAL
            else -> Gravity.BOTTOM
        } or when (style.align) {
            0 -> Gravity.START
            2 -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }
        text.gravity = when (style.align) { 0 -> Gravity.START; 2 -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL }
        val side = (width * 0.04f).toInt()
        lp.leftMargin = side
        lp.rightMargin = side
        lp.topMargin = (h * 0.06f).toInt()
        lp.bottomMargin = (h * style.bottomFraction).toInt()
        text.layoutParams = lp
        text.visibility = VISIBLE
    }

    fun hide() {
        text.visibility = GONE
    }

    val isShowing: Boolean get() = text.visibility == VISIBLE
}
