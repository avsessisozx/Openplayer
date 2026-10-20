package org.openplayer.app

import java.io.File
import java.io.RandomAccessFile

/**
 * MP3 dosyalarına gerçek ID3v2.4 etiketi yazar (dış bağımlılık yok). Dosyanın başındaki
 * mevcut ID3v2 başlığı varsa değiştirilir, ses verisi aynen korunur. Yalnızca metin
 * alanları yazılır (başlık, sanatçı, albüm, albüm sanatçısı, tür, yıl, parça no); boş
 * bırakılan alan için kare yazılmaz. Diğer biçimler (FLAC/M4A/OGG) henüz desteklenmez.
 */
object TagWriter {

    data class Fields(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val genre: String? = null,
        val year: Int? = null,
        val trackNo: Int? = null,
    )

    fun isSupported(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() == "mp3"

    /**
     * Etiketleri dosyaya yazar. Önce bir geçici dosyaya yazıp özgün dosyanın yerine koyar;
     * bir hata olursa özgün dosya bozulmaz (Result.failure döner).
     */
    fun write(path: String, f: Fields): Result<Unit> = runCatching {
        val src = File(path)
        require(src.isFile) { "Dosya bulunamadı" }
        require(isSupported(path)) { "Yalnızca MP3 destekleniyor" }

        val audioStart = existingTagSize(src)
        val tag = buildTag(f)

        val tmp = File(src.parentFile, src.name + ".optag.tmp")
        try {
            RandomAccessFile(src, "r").use { raf ->
                raf.seek(audioStart.toLong())
                tmp.outputStream().buffered().use { out ->
                    out.write(tag)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = raf.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            // Özgün dosyanın yerine koy.
            if (!replace(tmp, src)) throw java.io.IOException("Dosya değiştirilemedi")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        Unit
    }

    /** [tmp]'yi [dst]'nin yerine koyar; üstüne yazamazsa sil-dene, o da olmazsa kopyalar. */
    private fun replace(tmp: File, dst: File): Boolean {
        if (tmp.renameTo(dst)) return true
        if (dst.delete() && tmp.renameTo(dst)) return true
        return runCatching {
            tmp.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            true
        }.getOrDefault(false)
    }

    /** Dosyanın başındaki ID3v2 etiketinin toplam bayt boyutu (yoksa 0). */
    private fun existingTagSize(file: File): Int {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 10) return 0
            val h = ByteArray(10)
            raf.readFully(h)
            if (h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return 0
            val footer = (h[5].toInt() and 0x10) != 0
            val size = syncsafeToInt(h, 6)
            return 10 + size + (if (footer) 10 else 0)
        }
    }

    private fun buildTag(f: Fields): ByteArray {
        val frames = ArrayList<ByteArray>()
        f.title?.trim()?.ifEmpty { null }?.let { frames += textFrame("TIT2", it) }
        f.artist?.trim()?.ifEmpty { null }?.let { frames += textFrame("TPE1", it) }
        f.album?.trim()?.ifEmpty { null }?.let { frames += textFrame("TALB", it) }
        f.albumArtist?.trim()?.ifEmpty { null }?.let { frames += textFrame("TPE2", it) }
        f.genre?.trim()?.ifEmpty { null }?.let { frames += textFrame("TCON", it) }
        f.year?.takeIf { it > 0 }?.let { frames += textFrame("TDRC", it.toString()) }
        f.trackNo?.takeIf { it > 0 }?.let { frames += textFrame("TRCK", it.toString()) }

        var body = ByteArray(0)
        for (fr in frames) body += fr

        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 4 // v2.4
        header[4] = 0
        header[5] = 0 // flags
        intToSyncsafe(body.size, header, 6)
        return header + body
    }

    /** ID3v2.4 metin karesi; kodlama baytı 0x03 = UTF-8. Kare boyutu da syncsafe'tir. */
    private fun textFrame(id: String, text: String): ByteArray {
        val payload = byteArrayOf(0x03) + text.toByteArray(Charsets.UTF_8)
        val head = ByteArray(10)
        for (i in 0 until 4) head[i] = id[i].code.toByte()
        intToSyncsafe(payload.size, head, 4)
        head[8] = 0
        head[9] = 0
        return head + payload
    }

    private fun syncsafeToInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7f) shl 21) or
            ((b[off + 1].toInt() and 0x7f) shl 14) or
            ((b[off + 2].toInt() and 0x7f) shl 7) or
            (b[off + 3].toInt() and 0x7f)

    private fun intToSyncsafe(v: Int, b: ByteArray, off: Int) {
        b[off] = ((v shr 21) and 0x7f).toByte()
        b[off + 1] = ((v shr 14) and 0x7f).toByte()
        b[off + 2] = ((v shr 7) and 0x7f).toByte()
        b[off + 3] = (v and 0x7f).toByte()
    }
}
