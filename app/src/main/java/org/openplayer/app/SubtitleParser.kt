package org.openplayer.app

import android.graphics.Color
import android.graphics.Typeface
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** [alignment]: ASS sayı tuşu düzeni (1-3 alt, 4-6 orta, 7-9 üst). Varsayılan 2 = alt orta. */
data class SubCue(val startMs: Long, val endMs: Long, val text: CharSequence, val alignment: Int = 2)

/** Harici altyazı dosyalarını okur: SRT, WebVTT, SSA/ASS, SAMI, MicroDVD, MPL2, TMPlayer. */
object SubtitleParser {

    val extensions = setOf("srt", "ass", "ssa", "vtt", "smi", "sami", "sub", "mpl", "txt")

    /** BOM'a, kullanıcı ayarına ve UTF-8 denemesine göre metni çözer. */
    fun decode(bytes: ByteArray, charset: String, fallbackCharset: String): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (charset != "auto") {
            return runCatching { String(bytes, Charset.forName(charset)) }.getOrElse { String(bytes, Charsets.UTF_8) }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            runCatching { String(bytes, Charset.forName(fallbackCharset)) }
                .getOrElse { String(bytes, Charsets.ISO_8859_1) }
        }
    }

    private const val MAX_CUES = 50_000
    private const val MAX_CUE_CHARS = 2_000

    fun parse(fileName: String, text: String): List<SubCue> {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val clean = text.replace("\r\n", "\n").replace('\r', '\n')
        val head = clean.take(600)
        val cues = when {
            head.trimStart().startsWith("WEBVTT") || ext == "vtt" -> parseTimed(clean, skipHeader = true)
            "[Script Info]" in head || ext == "ass" || ext == "ssa" -> parseAss(clean)
            "<SAMI" in head.uppercase() || ext == "smi" || ext == "sami" -> parseSami(clean)
            "-->" in clean -> parseTimed(clean, skipHeader = false)
            Regex("""^\{\d+\}\{\d+\}""", RegexOption.MULTILINE).containsMatchIn(head) -> parseMicroDvd(clean)
            Regex("""^\[\d+]\[\d+]""", RegexOption.MULTILINE).containsMatchIn(head) -> parseMpl2(clean)
            Regex("""^\d{1,2}:\d{2}:\d{2}[:=,]""", RegexOption.MULTILINE).containsMatchIn(head) -> parseTmPlayer(clean)
            else -> emptyList()
        }
        return cues.asSequence()
            .filter { it.endMs > it.startMs && it.text.isNotBlank() }
            .take(MAX_CUES)
            .map { if (it.text.length > MAX_CUE_CHARS) it.copy(text = it.text.take(MAX_CUE_CHARS)) else it }
            .toList()
            .sortedBy { it.startMs }
    }

    // ---------- SRT / WebVTT ----------

    private val timeLine = Regex(
        """(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})"""
    )

    private fun ms(h: String, m: String, s: String, frac: String): Long =
        (h.ifEmpty { "0" }.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 +
                frac.padEnd(3, '0').take(3).toLong()

    private fun parseTimed(text: String, skipHeader: Boolean): List<SubCue> {
        val out = ArrayList<SubCue>()
        val lines = text.split('\n')
        var i = 0
        if (skipHeader) {
            // WEBVTT başlığı ve NOTE/STYLE blokları ilk zaman satırına kadar atlanır.
            while (i < lines.size && !timeLine.containsMatchIn(lines[i])) i++
        }
        while (i < lines.size) {
            val m = timeLine.find(lines[i])
            if (m == null) {
                i++
                continue
            }
            val g = m.groupValues
            val start = ms(g[1], g[2], g[3], g[4])
            val end = ms(g[5], g[6], g[7], g[8])
            val body = StringBuilder()
            i++
            while (i < lines.size && lines[i].isNotBlank()) {
                if (timeLine.containsMatchIn(lines[i])) break
                body.append(lines[i]).append('\n')
                i++
            }
            out += SubCue(start, end, styled(body.toString().trimEnd()))
        }
        return out
    }

    /** <i>, <b>, <u>, <font color> gibi basit etiketleri biçime çevirir; diğerlerini temizler. */
    private fun styled(raw: String): CharSequence {
        val noAss = raw.replace(Regex("""\{\\[^\}]*\}"""), "")
        if ('<' !in noAss) return noAss
        val html = noAss
            .replace("\n", "<br>")
            .replace(Regex("""<(?!/?(?:i|b|u|font|br)\b)[^>]*>""", RegexOption.IGNORE_CASE), "")
        return runCatching {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).trimEnd()
        }.getOrDefault(noAss.replace(Regex("<[^>]*>"), ""))
    }

    // ---------- SSA / ASS ----------

    private fun assTime(t: String): Long {
        val m = Regex("""(\d+):(\d{2}):(\d{2})[.:](\d{1,3})""").find(t.trim()) ?: return 0
        val (h, mi, s, f) = m.destructured
        return (h.toLong() * 3600 + mi.toLong() * 60 + s.toLong()) * 1000 + f.padEnd(2, '0').take(2).toLong() * 10
    }

    private data class AssStyle(
        val color: Int? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val alignment: Int = 2,
    )

    /** "&H00BBGGRR" (stil satırı), "&HBBGGRR&" (satır içi etiket) ya da ondalık sayıyı renge çevirir. */
    private fun assColor(raw: String): Int? {
        val t = raw.trim()
        val value: Long = if (t.startsWith("&H", ignoreCase = true)) {
            t.substring(2).trimEnd('&').toLongOrNull(16) ?: return null
        } else {
            t.toLongOrNull() ?: return null
        }
        val color = Color.rgb((value and 0xFF).toInt(), ((value shr 8) and 0xFF).toInt(), ((value shr 16) and 0xFF).toInt())
        // Beyaz zaten varsayılan; kullanıcının altyazı rengi ayarı geçerli kalsın.
        return color.takeIf { it != Color.WHITE }
    }

    private fun assFlag(raw: String?): Boolean = raw?.trim()?.let { it != "0" && it.isNotEmpty() } ?: false

    /** Eski (v4) hizalama kodunu sayı tuşu düzenine çevirir. */
    private fun legacyAlignment(a: Int): Int = when (a) {
        1, 2, 3 -> a
        5, 6, 7 -> a + 2
        9, 10, 11 -> a - 5
        else -> 2
    }

    private fun parseAssStyles(text: String): Map<String, AssStyle> {
        val styles = HashMap<String, AssStyle>()
        var inStyles = false
        var fields = emptyList<String>()
        for (line in text.split('\n')) {
            val l = line.trim()
            if (l.startsWith("[")) {
                inStyles = l.contains("Styles", ignoreCase = true)
                continue
            }
            if (!inStyles) continue
            if (l.startsWith("Format:", ignoreCase = true)) {
                fields = l.substringAfter(':').split(',').map { it.trim().lowercase() }
            } else if (l.startsWith("Style:", ignoreCase = true) && fields.isNotEmpty()) {
                val parts = l.substringAfter(':').trimStart().split(',')
                fun field(name: String) = fields.indexOf(name).takeIf { it >= 0 }?.let { parts.getOrNull(it) }
                val name = field("name")?.trim() ?: continue
                val alignment = field("alignment")?.trim()?.toIntOrNull()
                styles[name] = AssStyle(
                    color = field("primarycolour")?.let { assColor(it) },
                    bold = assFlag(field("bold")),
                    italic = assFlag(field("italic")),
                    underline = assFlag(field("underline")),
                    strike = assFlag(field("strikeout")),
                    // v4+ biçiminde sayı tuşu düzeni, v4 biçiminde eski kod kullanılır.
                    alignment = when {
                        alignment == null -> 2
                        "tertiarycolour" in fields -> legacyAlignment(alignment)
                        else -> alignment.coerceIn(1, 9)
                    },
                )
            }
        }
        return styles
    }

    private fun appendStyled(sb: SpannableStringBuilder, text: String, st: AssStyle) {
        if (text.isEmpty()) return
        val start = sb.length
        sb.append(text)
        val end = sb.length
        st.color?.let { sb.setSpan(ForegroundColorSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        when {
            st.bold && st.italic -> sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            st.bold -> sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            st.italic -> sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (st.underline) sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (st.strike) sb.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** ASS metnini biçimli metne çevirir. Sonuç: (metin, hizalama). */
    private fun assStyledText(raw: String, base: AssStyle): Pair<CharSequence, Int> {
        val sb = SpannableStringBuilder()
        var cur = base
        var alignment = base.alignment
        var i = 0
        fun plain(t: String) = t.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
        while (i < raw.length) {
            val open = raw.indexOf('{', i)
            if (open < 0) {
                appendStyled(sb, plain(raw.substring(i)), cur)
                break
            }
            appendStyled(sb, plain(raw.substring(i, open)), cur)
            val close = raw.indexOf('}', open)
            if (close < 0) break
            val block = raw.substring(open + 1, close)
            for (tag in block.split('\\').drop(1)) {
                val t = tag.trim()
                when {
                    t.startsWith("an") -> t.substring(2).toIntOrNull()?.takeIf { it in 1..9 }?.let { alignment = it }
                    t.length > 1 && t[0] == 'a' && t[1].isDigit() -> t.substring(1).toIntOrNull()?.let { alignment = legacyAlignment(it) }
                    t == "r" || (t.startsWith("r") && t.length > 1 && !t[1].isDigit()) -> cur = base
                    t.startsWith("i") && t.length >= 2 && t[1].isDigit() -> cur = cur.copy(italic = assFlag(t.substring(1)))
                    t.startsWith("b") && t.length >= 2 && t[1].isDigit() ->
                        cur = cur.copy(bold = t.substring(1).toIntOrNull()?.let { it != 0 } ?: false)
                    t.startsWith("u") && t.length >= 2 && t[1].isDigit() -> cur = cur.copy(underline = assFlag(t.substring(1)))
                    t.startsWith("s") && t.length >= 2 && t[1].isDigit() -> cur = cur.copy(strike = assFlag(t.substring(1)))
                    t.startsWith("1c") -> cur = cur.copy(color = assColor(t.substring(2)))
                    t.startsWith("c&") || t.startsWith("c") && t.length > 1 && t[1] == '&' -> cur = cur.copy(color = assColor(t.substring(1)))
                }
            }
            i = close + 1
        }
        return sb.trimEnd() to alignment
    }

    private fun parseAss(text: String): List<SubCue> {
        val styles = parseAssStyles(text)
        val defaultStyle = styles["Default"] ?: styles.values.firstOrNull() ?: AssStyle()
        val out = ArrayList<SubCue>()
        var inEvents = false
        var fields = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")
        for (line in text.split('\n')) {
            val l = line.trim()
            if (l.startsWith("[")) {
                inEvents = l.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (l.startsWith("Format:", ignoreCase = true)) {
                fields = l.substringAfter(':').split(',').map { it.trim() }
            } else if (l.startsWith("Dialogue:", ignoreCase = true)) {
                val parts = l.substringAfter(':').trimStart().split(',', limit = fields.size)
                if (parts.size < fields.size) continue
                val si = fields.indexOfFirst { it.equals("Start", true) }
                val ei = fields.indexOfFirst { it.equals("End", true) }
                val ti = fields.indexOfFirst { it.equals("Text", true) }
                val yi = fields.indexOfFirst { it.equals("Style", true) }
                if (si < 0 || ei < 0 || ti < 0) continue
                val body = parts[ti]
                if (Regex("""\\p[1-9]""").containsMatchIn(body)) continue // çizim komutları
                val style = styles[parts.getOrNull(yi)?.trim() ?: ""] ?: defaultStyle
                val (styled, alignment) = assStyledText(body, style)
                out += SubCue(assTime(parts[si]), assTime(parts[ei]), styled, alignment)
            }
        }
        return out
    }

    // ---------- SAMI ----------

    private fun parseSami(text: String): List<SubCue> {
        val syncs = Regex("""<SYNC[^>]*?Start\s*=\s*"?(\d+)"?[^>]*>(.*?)(?=<SYNC|</BODY|\z)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(text).toList()
        val out = ArrayList<SubCue>()
        for ((idx, m) in syncs.withIndex()) {
            val start = m.groupValues[1].toLong()
            val end = syncs.getOrNull(idx + 1)?.groupValues?.get(1)?.toLong() ?: (start + 3000)
            val body = m.groupValues[2]
                .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]*>"), "")
                .replace("&nbsp;", " ")
                .trim()
            if (body.isNotEmpty()) out += SubCue(start, end, body)
        }
        return out
    }

    // ---------- MicroDVD / MPL2 / TMPlayer ----------

    private fun parseMicroDvd(text: String): List<SubCue> {
        var fps = 23.976
        val out = ArrayList<SubCue>()
        val re = Regex("""^\{(\d+)\}\{(\d+)\}(.*)$""")
        for ((n, line) in text.split('\n').withIndex()) {
            val m = re.find(line.trim()) ?: continue
            val a = m.groupValues[1].toLong()
            val b = m.groupValues[2].toLong()
            val body = m.groupValues[3]
            if (n == 0 && a == 1L && b == 1L) {
                body.trim().toDoubleOrNull()?.let { fps = it }
                continue
            }
            out += SubCue((a * 1000 / fps).toLong(), (b * 1000 / fps).toLong(),
                body.replace("|", "\n").replace(Regex("""\{[^\}]*\}"""), ""))
        }
        return out
    }

    private fun parseMpl2(text: String): List<SubCue> {
        val re = Regex("""^\[(\d+)]\[(\d+)](.*)$""")
        return text.split('\n').mapNotNull { line ->
            val m = re.find(line.trim()) ?: return@mapNotNull null
            SubCue(m.groupValues[1].toLong() * 100, m.groupValues[2].toLong() * 100,
                m.groupValues[3].replace("|", "\n").replace(Regex("""^/"""), ""))
        }
    }

    private fun parseTmPlayer(text: String): List<SubCue> {
        val re = Regex("""^(\d{1,2}):(\d{2}):(\d{2})[:=,](.*)$""")
        val raw = text.split('\n').mapNotNull { line ->
            val m = re.find(line.trim()) ?: return@mapNotNull null
            val start = (m.groupValues[1].toLong() * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()) * 1000
            start to m.groupValues[4].replace("|", "\n")
        }
        return raw.mapIndexed { i, (start, body) ->
            val end = raw.getOrNull(i + 1)?.first ?: (start + 3000)
            SubCue(start, minOf(end, start + 8000), body)
        }
    }
}
