package org.openplayer.app

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import java.io.File

/** Altyazı görünümü: yazı tipi, kenar türü, hizalama ve satır işleme. */
object SubtitleLook {

    const val EDGE_NONE = CaptionStyleCompat.EDGE_TYPE_NONE
    const val EDGE_OUTLINE = CaptionStyleCompat.EDGE_TYPE_OUTLINE
    const val EDGE_SHADOW = CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
    const val EDGE_RAISED = CaptionStyleCompat.EDGE_TYPE_RAISED
    const val EDGE_DEPRESSED = CaptionStyleCompat.EDGE_TYPE_DEPRESSED

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
        // Mevcut biçimlendirmeyi korumak için satır satır alt dizileri kopyala.
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

    /** Hizalama ayarını satıra uygular. Konumu dosyada belirtilmiş satırlara dokunmaz. */
    fun align(cue: Cue, align: Int, italic: Boolean): Cue {
        if (align == 1 && !italic) return cue
        val b = cue.buildUpon()
        if (italic && cue.text != null) b.setText(italicSlash(cue.text!!))
        if (align != 1 && cue.position == Cue.DIMEN_UNSET && cue.bitmap == null) {
            if (align == 0) {
                b.setTextAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                    .setPosition(0.04f).setPositionAnchor(Cue.ANCHOR_TYPE_START)
            } else {
                b.setTextAlignment(android.text.Layout.Alignment.ALIGN_OPPOSITE)
                    .setPosition(0.96f).setPositionAnchor(Cue.ANCHOR_TYPE_END)
            }
        }
        return b.build()
    }
}
