package org.openplayer.app

import android.content.Context
import android.net.Uri
import java.io.File

class LyricLine(val timeMs: Long, val text: String)

/** Şarkı sözleri: .lrc (zamanlı) veya düz metin. Ses dosyasının yanındaki aynı adlı .lrc otomatik bulunur. */
object Lyrics {

    private val stamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private const val MAX_BYTES = 200 * 1024

    private fun dir(c: Context) = File(c.applicationContext.filesDir, "lyrics").apply { mkdirs() }

    private fun userFile(c: Context, id: Long) = File(dir(c), "$id.lrc")

    fun hasUserFile(c: Context, id: Long) = userFile(c, id).exists()

    fun removeUserFile(c: Context, id: Long) {
        userFile(c, id).delete()
    }

    fun clearAll(c: Context) {
        dir(c).deleteRecursively()
    }

    /** Kullanıcının seçtiği söz dosyasını uygulamaya kopyalar. */
    fun importFile(c: Context, id: Long, source: Uri): Boolean = runCatching {
        val bytes = c.contentResolver.openInputStream(source)?.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() <= MAX_BYTES) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return false
        if (bytes.size > MAX_BYTES) return false
        userFile(c, id).writeBytes(bytes)
        true
    }.getOrDefault(false)

    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) String(bytes, charset("windows-1254")) else utf8.removePrefix("\uFEFF")
    }

    /** Söz satırlarını döndürür; zamansız metinde tüm satırların zamanı -1 olur. Bulunamazsa null. */
    fun load(c: Context, track: MusicTrack): List<LyricLine>? {
        val user = userFile(c, track.id)
        val file = when {
            user.exists() -> user
            track.path.isNotEmpty() -> File(track.path.substringBeforeLast('.') + ".lrc").takeIf { it.exists() }
            else -> null
        } ?: return null
        if (file.length() > MAX_BYTES) return null
        return runCatching { parse(decode(file.readBytes())) }.getOrNull()
    }

    fun parse(text: String): List<LyricLine> {
        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<LyricLine>()
        var offset = 0L
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[offset:", ignoreCase = true)) {
                offset = line.substringAfter(':').substringBefore(']').trim().toLongOrNull() ?: 0L
                continue
            }
            val stamps = stamp.findAll(line).toList()
            if (stamps.isEmpty()) {
                if (!line.startsWith("[") || !line.contains(':')) plain += LyricLine(-1, line)
                continue
            }
            val body = line.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3].let { f -> if (f.isEmpty()) 0L else (f.padEnd(3, '0').take(3)).toLong() }
                timed += LyricLine(min * 60_000 + sec * 1000 + frac - offset, body)
            }
        }
        return if (timed.isNotEmpty()) timed.sortedBy { it.timeMs } else plain
    }
}
