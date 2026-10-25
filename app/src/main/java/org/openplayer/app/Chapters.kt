package org.openplayer.app

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

data class Chapter(val startMs: Long, val title: String)

/** Videonun içindeki bölüm (chapter) bilgisini okur: MKV (Matroska Chapters) ve MP4 (Nero "chpl"). */
object Chapters {

    fun load(context: Context, uri: Uri): List<Chapter> {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "content" && scheme != "file") return emptyList()
        return runCatching {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
            pfd.use { p ->
                FileInputStream(p.fileDescriptor).channel.use { ch -> parse(ch) }
            }
        }.getOrDefault(emptyList()).sortedBy { it.startMs }
    }

    private fun parse(ch: FileChannel): List<Chapter> {
        val head = read(ch, 0, 12) ?: return emptyList()
        return when {
            head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> mkv(ch)
            head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte() -> mp4(ch)
            else -> emptyList()
        }
    }

    private fun read(ch: FileChannel, pos: Long, len: Int): ByteArray? {
        if (len < 0 || len > 8 * 1024 * 1024 || pos < 0 || pos + len > ch.size()) return null
        val buf = ByteBuffer.allocate(len)
        var p = pos
        while (buf.hasRemaining()) {
            val n = ch.read(buf, p)
            if (n <= 0) return null
            p += n
        }
        return buf.array()
    }

    // ------------------------------------------------------------------ Matroska

    private class Vint(val value: Long, val length: Int)

    private fun vint(ch: FileChannel, pos: Long, keepMarker: Boolean): Vint? {
        val first = read(ch, pos, 1)?.get(0)?.toInt()?.and(0xFF) ?: return null
        if (first == 0) return null
        val len = Integer.numberOfLeadingZeros(first) - 24 + 1
        val bytes = read(ch, pos, len) ?: return null
        var v = (if (keepMarker) bytes[0].toInt() and 0xFF else (bytes[0].toInt() and 0xFF) and ((1 shl (8 - len)) - 1)).toLong()
        var allOnes = (bytes[0].toInt() and 0xFF) and ((1 shl (8 - len)) - 1) == (1 shl (8 - len)) - 1
        for (i in 1 until len) {
            val b = bytes[i].toInt() and 0xFF
            if (b != 0xFF) allOnes = false
            v = (v shl 8) or b.toLong()
        }
        return Vint(if (!keepMarker && allOnes) -1L else v, len)
    }

    private class Element(val id: Long, val dataStart: Long, val size: Long)

    private fun element(ch: FileChannel, pos: Long): Element? {
        val id = vint(ch, pos, true) ?: return null
        val sz = vint(ch, pos + id.length, false) ?: return null
        return Element(id.value, pos + id.length + sz.length, sz.value)
    }

    private fun uint(ch: FileChannel, e: Element): Long {
        val b = read(ch, e.dataStart, e.size.toInt().coerceIn(0, 8)) ?: return 0
        var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
        return v
    }

    private fun mkv(ch: FileChannel): List<Chapter> {
        val size = ch.size()
        var pos = 0L
        val header = element(ch, pos) ?: return emptyList()
        pos = header.dataStart + header.size
        val seg = element(ch, pos) ?: return emptyList()
        if (seg.id != 0x18538067L) return emptyList()
        val segStart = seg.dataStart
        val end = if (seg.size < 0) size else minOf(size, segStart + seg.size)
        pos = segStart
        var guard = 0
        while (pos < end && guard++ < 100_000) {
            val e = element(ch, pos) ?: return emptyList()
            when (e.id) {
                0x1043A770L -> return atoms(ch, e.dataStart, e.dataStart + e.size)
                0x114D9B74L -> {
                    val at = seekHeadChapters(ch, e)
                    if (at >= 0) {
                        val c = element(ch, segStart + at)
                        if (c != null && c.id == 0x1043A770L) return atoms(ch, c.dataStart, c.dataStart + c.size)
                    }
                }
            }
            if (e.size < 0) return emptyList()
            pos = e.dataStart + e.size
        }
        return emptyList()
    }

    private fun seekHeadChapters(ch: FileChannel, head: Element): Long {
        var pos = head.dataStart
        val end = head.dataStart + head.size
        while (pos < end) {
            val seek = element(ch, pos) ?: return -1
            if (seek.id == 0x4DBBL) {
                var id = -1L
                var position = -1L
                var p = seek.dataStart
                val sEnd = seek.dataStart + seek.size
                while (p < sEnd) {
                    val c = element(ch, p) ?: break
                    if (c.id == 0x53ABL) {
                        val b = read(ch, c.dataStart, c.size.toInt().coerceIn(0, 8)) ?: break
                        id = 0
                        for (x in b) id = (id shl 8) or (x.toLong() and 0xFF)
                    } else if (c.id == 0x53ACL) position = uint(ch, c)
                    p = c.dataStart + c.size
                }
                if (id == 0x1043A770L) return position
            }
            pos = seek.dataStart + seek.size
        }
        return -1
    }

    private fun atoms(ch: FileChannel, start: Long, end: Long): List<Chapter> {
        val out = ArrayList<Chapter>()
        var pos = start
        while (pos < end) {
            val e = element(ch, pos) ?: break
            when (e.id) {
                0x45B9L -> {
                    out += atoms(ch, e.dataStart, e.dataStart + e.size)
                    if (out.isNotEmpty()) return out
                }
                0xB6L -> atom(ch, e, out)
            }
            if (e.size < 0) break
            pos = e.dataStart + e.size
        }
        return out
    }

    private fun atom(ch: FileChannel, atom: Element, out: MutableList<Chapter>) {
        var timeNs = -1L
        var title = ""
        val nested = ArrayList<Chapter>()
        var pos = atom.dataStart
        val end = atom.dataStart + atom.size
        while (pos < end) {
            val e = element(ch, pos) ?: break
            when (e.id) {
                0x91L -> timeNs = uint(ch, e)
                0x80L -> if (title.isEmpty()) {
                    var p = e.dataStart
                    val dEnd = e.dataStart + e.size
                    while (p < dEnd) {
                        val c = element(ch, p) ?: break
                        if (c.id == 0x85L) {
                            read(ch, c.dataStart, c.size.toInt())?.let { title = String(it, Charsets.UTF_8).trimEnd('\u0000') }
                        }
                        p = c.dataStart + c.size
                    }
                }
                0xB6L -> atom(ch, e, nested)
            }
            pos = e.dataStart + e.size
        }
        if (timeNs >= 0) out += Chapter(timeNs / 1_000_000L, title.ifEmpty { Tx.s(R.string.chapters_001, out.size + 1) })
        out += nested
    }

    // ------------------------------------------------------------------ MP4

    private fun mp4(ch: FileChannel): List<Chapter> {
        val size = ch.size()
        var pos = 0L
        var guard = 0
        while (pos + 8 <= size && guard++ < 10_000) {
            val h = read(ch, pos, 8) ?: break
            var boxSize = ByteBuffer.wrap(h, 0, 4).int.toLong() and 0xFFFFFFFFL
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var hdr = 8
            if (boxSize == 1L) {
                val big = read(ch, pos + 8, 8) ?: break
                boxSize = ByteBuffer.wrap(big).long
                hdr = 16
            } else if (boxSize == 0L) boxSize = size - pos
            if (boxSize < hdr) break
            if (type == "moov") {
                val r = chplInside(ch, pos + hdr, pos + boxSize, 0)
                if (r.isNotEmpty()) return r
            }
            pos += boxSize
        }
        return emptyList()
    }

    private fun chplInside(ch: FileChannel, start: Long, end: Long, depth: Int): List<Chapter> {
        if (depth > 3) return emptyList()
        var pos = start
        while (pos + 8 <= end) {
            val h = read(ch, pos, 8) ?: break
            val boxSize = ByteBuffer.wrap(h, 0, 4).int.toLong() and 0xFFFFFFFFL
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            if (boxSize < 8) break
            when (type) {
                "udta" -> chplInside(ch, pos + 8, pos + boxSize, depth + 1).let { if (it.isNotEmpty()) return it }
                "chpl" -> return chpl(ch, pos + 8, pos + boxSize)
            }
            pos += boxSize
        }
        return emptyList()
    }

    private fun chpl(ch: FileChannel, start: Long, end: Long): List<Chapter> {
        val data = read(ch, start, (end - start).toInt()) ?: return emptyList()
        if (data.size < 9) return emptyList()
        val version = data[0].toInt()
        var p = 4 + (if (version == 1) 4 else 0)
        val count = data[p++].toInt() and 0xFF
        val out = ArrayList<Chapter>()
        for (i in 0 until count) {
            if (p + 9 > data.size) break
            val t = ByteBuffer.wrap(data, p, 8).long
            p += 8
            val len = data[p++].toInt() and 0xFF
            if (p + len > data.size) break
            val title = String(data, p, len, Charsets.UTF_8)
            p += len
            out += Chapter(t / 10_000L, title.ifEmpty { Tx.s(R.string.chapters_001, i + 1) })
        }
        return out
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
