package org.openplayer.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale
import java.util.zip.InflaterInputStream

/*
 * Gömülü altyazı parçalarını listeler ve metin parçalarını SubCue listesine çıkarır (çeviri için).
 *
 * Yöntem seçimi (emülatörde, API 37 x86_64, mpv 0.41 AAR ile sınandı):
 * - mpv `sub-text` akışı doğru metni ve `sub-start`/`sub-end` ile doğru bitiş zamanını verir ama
 *   gerçek zamanlıdır: 2 saatlik filmi baştan sona oynatmak gerekir. Kullanılmadı.
 * - Android MediaExtractor MKV/WebM altyazı parçalarını HİÇ göstermez (yalnız görüntü + ses).
 *   MP4'teki `tx3g` (mov_text) parçasını gösterir; boş örnekler satır bitişini işaretlediği için
 *   bitiş zamanları kesin çıkar (1,000-2,500 / 4,250-4,900 ... dosyadakiyle birebir).
 * - Bu yüzden: MKV/WebM için aşağıdaki saf Kotlin Matroska okuyucu (BlockDuration ile kesin bitiş,
 *   tek geçiş, yalnız ileri okuma: yerel dosya, content:// ve http(s) aynı yoldan), MP4/MOV/3GP için
 *   MediaExtractor. Diğer kapsayıcılar (AVI, TS...) "desteklenmiyor" döner.
 */

/** Videodaki bir altyazı parçası (mpv `track-list` kaydı). */
data class EmbeddedTrack(
    /** mpv `sid` değeri. */
    val id: Int,
    /** Kapsayıcıdaki parça numarası (MKV'de TrackNumber); bilinmiyorsa null. */
    val srcId: Int?,
    val title: String?,
    /** mpv'nin verdiği dil kodu (çoğunlukla ISO 639-2, örn. `eng`). */
    val language: String?,
    val codec: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
    val isImage: Boolean,
    /** Aynı codec türündeki gömülü parçalar arasındaki sıra (MP4 eşlemesi için). */
    val ordinal: Int,
) {
    /** Metni okunabilir mi (çevrilebilir mi). */
    val isText: Boolean get() = !isImage && codec in TEXT_CODECS

    /** ISO 639-1 kodu (`en`, `tr`...); bilinmiyorsa null. Çeviri kaynak dili için. */
    val languageCode: String? get() = EmbeddedSubtitles.iso1(language)

    /** Menü etiketi: "English · Forced · başlık (subrip)". */
    fun label(): String {
        val parts = ArrayList<String>()
        parts += EmbeddedSubtitles.languageName(language) ?: "#$id"
        if (isForced) parts += "Forced"
        title?.takeIf { it.isNotBlank() && !it.equals(language, true) }?.let { parts += it }
        return parts.joinToString(" · ") + (codec?.let { " ($it)" } ?: "")
    }

    companion object {
        val TEXT_CODECS = setOf("subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text")
    }
}

/** Çıkarma sonucu. */
sealed class EmbeddedResult {
    data class Ok(val cues: List<SubCue>) : EmbeddedResult()
    data class Failed(val reason: Reason, val detail: String? = null) : EmbeddedResult()

    enum class Reason {
        /** VobSub/PGS gibi görüntü parça: çevrilemez. */
        IMAGE,
        /** Parça metin ama bu biçim okunamıyor (ör. USF, şifreli/sıkıştırılmış). */
        UNSUPPORTED_CODEC,
        /** Kapsayıcı MKV/WebM/MP4 değil. */
        UNSUPPORTED_CONTAINER,
        /** Parçada hiç satır bulunamadı. */
        EMPTY,
        /** Dosya okunamadı. */
        READ_ERROR,
        CANCELLED,
    }
}

object EmbeddedSubtitles {

    /** Oynayan dosyanın gömülü altyazı parçaları (harici olanlar hariç), mpv sırasıyla. */
    fun list(engine: MpvEngine): List<EmbeddedTrack> = parseTrackList(engine.getPropertyJson("track-list"))

    /** `track-list` JSON'undan gömülü altyazı parçaları. Saf işlev (birim testli). */
    fun parseTrackList(json: String?): List<EmbeddedTrack> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<EmbeddedTrack>()
        val perCodec = HashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "sub" || o.optBoolean("external", false)) continue
            val codec = o.optString("codec").takeIf { it.isNotEmpty() }
            val image = o.optBoolean("image", false) ||
                MpvTrack(0, MpvTrackType.SUB, null, null, codec, false, false, false, false).isImageSubtitle
            val key = codec ?: ""
            val ordinal = perCodec[key] ?: 0
            perCodec[key] = ordinal + 1
            out += EmbeddedTrack(
                id = o.optInt("id"),
                srcId = if (o.has("src-id")) o.optInt("src-id") else null,
                title = o.optString("title").takeIf { it.isNotEmpty() },
                language = o.optString("lang").takeIf { it.isNotEmpty() },
                codec = codec,
                isDefault = o.optBoolean("default", false),
                isForced = o.optBoolean("forced", false),
                isImage = image,
                ordinal = ordinal,
            )
        }
        return out
    }

    /**
     * Parçayı çıkarır. Uzun sürebilir (MKV'de dosyanın tamamı okunur): arka plan iş parçacığında
     * çağırın. [source]: `content://`, `file://`, düz yol ya da `http(s)://` (SMB/FTP/WebDAV için
     * yerel vekil adresi). [progress] 0..1 arası, bilinmiyorsa -1.
     */
    fun extract(
        context: Context,
        source: String,
        track: EmbeddedTrack,
        fallbackCharset: String = "windows-1254",
        cancelled: () -> Boolean = { false },
        progress: (Float) -> Unit = {},
    ): EmbeddedResult {
        if (track.isImage) return EmbeddedResult.Failed(EmbeddedResult.Reason.IMAGE)
        if (!track.isText) return EmbeddedResult.Failed(EmbeddedResult.Reason.UNSUPPORTED_CODEC, track.codec)
        return try {
            val kind = openStream(context, source).use { Container.sniff(it) }
            when (kind) {
                Container.MATROSKA -> {
                    val num = track.srcId ?: return EmbeddedResult.Failed(EmbeddedResult.Reason.UNSUPPORTED_CODEC, "src-id")
                    val size = sizeOf(context, source)
                    val r = MatroskaSubtitles.extract({ openStream(context, source) }, num, size, fallbackCharset, cancelled, progress)
                    finish(r)
                }
                Container.MP4 -> finish(extractMp4(context, source, track, cancelled, progress))
                else -> EmbeddedResult.Failed(EmbeddedResult.Reason.UNSUPPORTED_CONTAINER)
            }
        } catch (_: TranslateCancelled) {
            EmbeddedResult.Failed(EmbeddedResult.Reason.CANCELLED)
        } catch (e: UnsupportedSubtitleException) {
            EmbeddedResult.Failed(EmbeddedResult.Reason.UNSUPPORTED_CODEC, e.message)
        } catch (e: Exception) {
            EmbeddedResult.Failed(EmbeddedResult.Reason.READ_ERROR, e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun finish(cues: List<SubCue>): EmbeddedResult =
        if (cues.isEmpty()) EmbeddedResult.Failed(EmbeddedResult.Reason.EMPTY) else EmbeddedResult.Ok(cues)

    // ------------------------------------------------------------------ MP4 (MediaExtractor)

    private fun extractMp4(
        context: Context, source: String, track: EmbeddedTrack, cancelled: () -> Boolean, progress: (Float) -> Unit,
    ): List<SubCue> {
        val x = MediaExtractor()
        try {
            val u = Uri.parse(source)
            when (u.scheme) {
                "content", "file" -> x.setDataSource(context, u, null)
                "http", "https" -> x.setDataSource(source, emptyMap())
                else -> x.setDataSource(source)
            }
            // mpv'nin mov_text sırası = MediaExtractor'daki text/3gpp-tt sırası.
            val textTracks = (0 until x.trackCount).filter { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "text/3gpp-tt" }
            val index = textTracks.getOrNull(track.ordinal) ?: throw UnsupportedSubtitleException("tx3g #${track.ordinal}")
            val format = x.getTrackFormat(index)
            val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(-1L)
            x.selectTrack(index)
            val buf = ByteBuffer.allocate(256 * 1024)
            val samples = ArrayList<Pair<Long, String>>()
            while (true) {
                if (cancelled()) throw TranslateCancelled()
                buf.clear()
                val n = x.readSampleData(buf, 0)
                if (n < 0) break
                val bytes = ByteArray(n)
                buf.get(bytes, 0, n)
                samples += x.sampleTime to Tx3g.text(bytes)
                if (durationUs > 0) progress((x.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                x.advance()
            }
            return Tx3g.cues(samples, durationUs)
        } finally {
            x.release()
        }
    }

    // ------------------------------------------------------------------ giriş akışı

    private const val USER_AGENT = "OpenPlayer"

    fun openStream(context: Context, source: String): InputStream {
        val u = Uri.parse(source)
        return when (u.scheme) {
            "content" -> context.contentResolver.openInputStream(u) ?: throw IOException("openInputStream null")
            "file" -> FileInputStream(File(u.path ?: throw IOException("path")))
            "http", "https" -> {
                val c = URL(source).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", USER_AGENT)
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                c.inputStream
            }
            null, "" -> FileInputStream(File(source))
            else -> throw IOException("scheme ${u.scheme}")
        }
    }

    private fun sizeOf(context: Context, source: String): Long = runCatching {
        val u = Uri.parse(source)
        when (u.scheme) {
            "content" -> context.contentResolver.openFileDescriptor(u, "r")?.use { it.statSize } ?: -1L
            "file" -> File(u.path!!).length()
            "http", "https" -> -1L
            else -> File(source).length()
        }
    }.getOrDefault(-1L)

    // ------------------------------------------------------------------ dil adları

    private val iso3to1: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (code in Locale.getISOLanguages()) {
            runCatching { m[Locale(code).isO3Language] = code }
        }
        // ISO 639-2/B (bibliyografik) kodları, MKV'de sık görülür.
        mapOf("ger" to "de", "fre" to "fr", "chi" to "zh", "dut" to "nl", "cze" to "cs", "gre" to "el", "rum" to "ro",
            "per" to "fa", "slo" to "sk", "ice" to "is", "mac" to "mk", "may" to "ms", "alb" to "sq", "arm" to "hy",
            "baq" to "eu", "bur" to "my", "geo" to "ka", "mao" to "mi", "tib" to "bo", "wel" to "cy", "scc" to "sr", "scr" to "hr")
            .forEach { (k, v) -> m[k] = v }
        m
    }

    /** `eng`, `en`, `en-US`, `pt-BR` → `en`/`pt`; `und` ya da bilinmeyen → null. */
    fun iso1(code: String?): String? {
        val c = code?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_') ?: return null
        if (c.isEmpty() || c == "und" || c == "mul" || c == "zxx") return null
        return when (c.length) {
            2 -> c
            3 -> iso3to1[c]
            else -> null
        }
    }

    /** Görünen dil adı (arayüz dilinde); bilinmiyorsa ham kod, kod yoksa null. */
    fun languageName(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        val one = iso1(code) ?: return code
        val name = Locale(one).getDisplayLanguage(Locale.getDefault())
        return name.takeIf { it.isNotBlank() && it != one }?.replaceFirstChar { it.titlecase(Locale.getDefault()) } ?: code
    }
}

/** Parça okunabilir değil (sıkıştırma, şifreleme, desteklenmeyen codec). */
class UnsupportedSubtitleException(message: String) : IOException(message)

internal enum class Container {
    MATROSKA, MP4, OTHER;

    companion object {
        /** İlk baytlardan kapsayıcı türü. */
        fun sniff(input: InputStream): Container {
            val b = ByteArray(12)
            var n = 0
            while (n < b.size) {
                val r = input.read(b, n, b.size - n)
                if (r < 0) break
                n += r
            }
            if (n >= 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()) return MATROSKA
            if (n >= 8) {
                val box = String(b, 4, 4, Charsets.ISO_8859_1)
                if (box in setOf("ftyp", "moov", "mdat", "free", "wide", "skip")) return MP4
            }
            return OTHER
        }
    }
}

/** 3GPP zamanlı metin (tx3g / mov_text) örnekleri. */
internal object Tx3g {
    /** Örnek: 2 bayt uzunluk + UTF-8 (ya da BOM'lu UTF-16) metin + stil kutuları. */
    fun text(sample: ByteArray): String {
        if (sample.size < 2) return ""
        val len = ((sample[0].toInt() and 0xFF) shl 8) or (sample[1].toInt() and 0xFF)
        val n = len.coerceAtMost(sample.size - 2)
        if (n <= 0) return ""
        return if (n >= 2 && sample[2] == 0xFE.toByte() && sample[3] == 0xFF.toByte()) String(sample, 4, n - 2, Charsets.UTF_16BE)
        else String(sample, 2, n, Charsets.UTF_8)
    }

    /** Boş örnek bir önceki satırı bitirir; bir satır sonraki örneğe (ya da parça süresine) kadar sürer. */
    fun cues(samples: List<Pair<Long, String>>, durationUs: Long): List<SubCue> {
        val out = ArrayList<SubCue>()
        for ((i, s) in samples.withIndex()) {
            val text = s.second.replace("\r\n", "\n").trim()
            if (text.isEmpty()) continue
            val startMs = s.first / 1000
            val endUs = samples.getOrNull(i + 1)?.first ?: if (durationUs > s.first) durationUs else s.first + 3_000_000
            out += SubCue(startMs, endUs / 1000, text)
        }
        return out.filter { it.endMs > it.startMs }
    }
}

/**
 * Matroska/WebM altyazı okuyucusu. Yalnız ileri okur; kümelerin (Cluster) içine iner, istenen
 * parçanın bloklarını alır, diğerlerinin içeriğini atlar. Boyutu bilinmeyen Segment/Cluster desteklenir.
 * Zaman: (Cluster Timestamp + blok göreli zamanı) × TimestampScale; bitiş BlockDuration'dan, yoksa
 * DefaultDuration'dan, o da yoksa bir sonraki satırın başından (en fazla 5 sn).
 */
internal object MatroskaSubtitles {

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_DEFAULT_DURATION = 0x23E383L
    private const val ID_CONTENT_ENCODINGS = 0x6D80L
    private const val ID_CONTENT_ENCODING = 0x6240L
    private const val ID_CONTENT_COMPRESSION = 0x5034L
    private const val ID_COMP_ALGO = 0x4254L
    private const val ID_COMP_SETTINGS = 0x4255L
    private const val ID_CONTENT_ENCRYPTION = 0x5035L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CLUSTER_TIMESTAMP = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_DURATION = 0x9BL

    /** Segment düzeyindeki öğeler: boyutu bilinmeyen bir Cluster bunlardan biri gelince biter. */
    private val LEVEL1 = setOf(ID_CLUSTER, 0x114D9B74L, ID_INFO, ID_TRACKS, 0x1C53BB6BL, 0x1941A469L, 0x1043A770L, 0x1254C367L)

    private const val MAX_PAYLOAD = 1 shl 20
    private const val MAX_CUES = 50_000

    class TrackInfo(val number: Long) {
        var codec: String = ""
        var defaultDurationNs: Long = 0
        var compAlgo: Int = -1
        var compSettings: ByteArray = ByteArray(0)
        var encrypted = false
    }

    class Raw(val startTicks: Long, val durTicks: Long, val data: ByteArray)

    /**
     * [open] her çağrıda dosyanın başından yeni akış verir (Tracks kümelerden sonra gelirse ikinci
     * geçiş için). [totalSize] bilinmiyorsa -1.
     */
    fun extract(
        open: () -> InputStream,
        trackNumber: Int,
        totalSize: Long,
        fallbackCharset: String,
        cancelled: () -> Boolean,
        progress: (Float) -> Unit,
    ): List<SubCue> {
        var pass = Pass(trackNumber.toLong(), null, totalSize, cancelled, progress)
        open().use { pass.run(EbmlReader(it)) }
        if (pass.track == null && pass.tracksAfterClusters != null) {
            // Nadir: Tracks kümelerden sonra. Parça bilgisiyle baştan bir kez daha oku.
            pass = Pass(trackNumber.toLong(), pass.tracksAfterClusters, totalSize, cancelled, progress)
            open().use { pass.run(EbmlReader(it)) }
        }
        val track = pass.track ?: throw IOException("track $trackNumber not found")
        return build(track, pass.blocks, pass.scaleNs, fallbackCharset)
    }

    private class Pass(
        val wanted: Long,
        known: TrackInfo?,
        val totalSize: Long,
        val cancelled: () -> Boolean,
        val progress: (Float) -> Unit,
    ) {
        var track: TrackInfo? = known
        var tracksAfterClusters: TrackInfo? = null
        var scaleNs = 1_000_000L
        val blocks = ArrayList<Raw>()
        private var clusterTs = 0L
        private var sawCluster = false
        private var tracksSeen = known != null

        fun run(r: EbmlReader) {
            try {
                loop(r)
            } catch (_: EOFException) {
                // Kesik dosya: o ana kadar okunanlar kullanılır.
            }
        }

        private fun loop(r: EbmlReader) {
            while (true) {
                val id = r.readId() ?: return
                val size = r.readSize()
                when (id) {
                    ID_EBML -> r.skip(size)
                    ID_SEGMENT -> Unit // içine in
                    ID_CLUSTER -> {
                        sawCluster = true
                        if (cancelled()) throw TranslateCancelled()
                        if (totalSize > 0) progress((r.position.toFloat() / totalSize).coerceIn(0f, 1f))
                    }
                    ID_INFO -> parseInfo(r, size)
                    ID_TRACKS -> {
                        val t = parseTracks(r, size)
                        if (!tracksSeen) {
                            if (sawCluster) tracksAfterClusters = t else track = t
                            tracksSeen = true
                        }
                        if (t == null && !sawCluster) throw IOException("track $wanted not found")
                    }
                    ID_CLUSTER_TIMESTAMP -> clusterTs = r.readUInt(size)
                    ID_SIMPLE_BLOCK -> block(r, size, -1L)
                    ID_BLOCK_GROUP -> group(r, size)
                    else -> {
                        if (size < 0) return // boyutu bilinmeyen tanımsız öğe: güvenle atlanamaz
                        r.skip(size)
                    }
                }
            }
        }

        private fun parseInfo(r: EbmlReader, size: Long) {
            val end = r.position + size
            while (r.position < end) {
                val id = r.readId() ?: return
                val s = r.readSize()
                if (id == ID_TIMESTAMP_SCALE) scaleNs = r.readUInt(s).takeIf { it > 0 } ?: 1_000_000L else r.skip(s)
            }
        }

        private fun parseTracks(r: EbmlReader, size: Long): TrackInfo? {
            val end = r.position + size
            var found: TrackInfo? = null
            while (r.position < end) {
                val id = r.readId() ?: break
                val s = r.readSize()
                if (id == ID_TRACK_ENTRY) parseEntry(r, s)?.let { if (it.number == wanted) found = it } else r.skip(s)
            }
            return found
        }

        private fun parseEntry(r: EbmlReader, size: Long): TrackInfo? {
            val end = r.position + size
            val t = TrackInfo(-1)
            var number = -1L
            while (r.position < end) {
                val id = r.readId() ?: break
                val s = r.readSize()
                when (id) {
                    ID_TRACK_NUMBER -> number = r.readUInt(s)
                    ID_CODEC_ID -> t.codec = String(r.readBytes(s), Charsets.US_ASCII).trimEnd('\u0000')
                    ID_DEFAULT_DURATION -> t.defaultDurationNs = r.readUInt(s)
                    ID_CONTENT_ENCODINGS -> parseEncodings(r, s, t)
                    else -> r.skip(s)
                }
            }
            if (number < 0) return null
            return TrackInfo(number).also {
                it.codec = t.codec; it.defaultDurationNs = t.defaultDurationNs
                it.compAlgo = t.compAlgo; it.compSettings = t.compSettings; it.encrypted = t.encrypted
            }
        }

        private fun parseEncodings(r: EbmlReader, size: Long, t: TrackInfo) {
            val end = r.position + size
            while (r.position < end) {
                val id = r.readId() ?: break
                val s = r.readSize()
                when (id) {
                    ID_CONTENT_ENCODING -> Unit // içine in
                    ID_CONTENT_COMPRESSION -> {
                        t.compAlgo = 0 // varsayılan zlib
                        val e2 = r.position + s
                        while (r.position < e2) {
                            val i2 = r.readId() ?: break
                            val s2 = r.readSize()
                            when (i2) {
                                ID_COMP_ALGO -> t.compAlgo = r.readUInt(s2).toInt()
                                ID_COMP_SETTINGS -> t.compSettings = r.readBytes(s2)
                                else -> r.skip(s2)
                            }
                        }
                    }
                    ID_CONTENT_ENCRYPTION -> { t.encrypted = true; r.skip(s) }
                    else -> r.skip(s)
                }
            }
        }

        private fun group(r: EbmlReader, size: Long) {
            if (size < 0) throw IOException("BlockGroup without size")
            val end = r.position + size
            var data: ByteArray? = null
            var rel = 0L
            var dur = -1L
            while (r.position < end) {
                val id = r.readId() ?: break
                val s = r.readSize()
                when (id) {
                    ID_BLOCK -> {
                        val b = readBlock(r, s)
                        if (b != null) { rel = b.first; data = b.second }
                    }
                    ID_BLOCK_DURATION -> dur = r.readUInt(s)
                    else -> r.skip(s)
                }
            }
            data?.let { add(rel, dur, it) }
        }

        private fun block(r: EbmlReader, size: Long, dur: Long) {
            readBlock(r, size)?.let { add(it.first, dur, it.second) }
        }

        private fun add(rel: Long, dur: Long, data: ByteArray) {
            if (blocks.size < MAX_CUES) blocks += Raw(clusterTs + rel, dur, data)
        }

        /** İstenen parçaysa (göreli zaman, yük) döner; değilse içeriği atlar. */
        private fun readBlock(r: EbmlReader, size: Long): Pair<Long, ByteArray>? {
            if (size < 4) { r.skip(size); return null }
            val start = r.position
            val num = r.readVint()
            val headerLen = (r.position - start)
            val rest = size - headerLen
            if (num != wanted || track == null) {
                r.skip(rest)
                return null
            }
            val hi = r.readByte()
            val lo = r.readByte()
            val rel = ((hi shl 8) or lo).toShort().toLong()
            val flags = r.readByte()
            val payload = rest - 3
            if (flags and 0x06 != 0 || payload > MAX_PAYLOAD || payload < 0) { // örgülü (laced) altyazı pratikte yok
                r.skip(payload)
                return null
            }
            return rel to r.readBytes(payload)
        }
    }

    /** Ham blokları satırlara çevirir. */
    fun build(track: TrackInfo, raw: List<Raw>, scaleNs: Long, fallbackCharset: String): List<SubCue> {
        if (track.encrypted) throw UnsupportedSubtitleException("encrypted")
        if (track.compAlgo !in setOf(-1, 0, 3)) throw UnsupportedSubtitleException("compression ${track.compAlgo}")
        val kind = when {
            track.codec == "S_TEXT/UTF8" || track.codec == "S_TEXT/ASCII" -> 0
            track.codec == "S_TEXT/ASS" || track.codec == "S_TEXT/SSA" || track.codec == "S_ASS" || track.codec == "S_SSA" -> 1
            track.codec == "S_TEXT/WEBVTT" -> 0
            track.codec == "D_WEBVTT/SUBTITLES" || track.codec == "D_WEBVTT/CAPTIONS" -> 2
            else -> throw UnsupportedSubtitleException(track.codec)
        }
        val defDurMs = track.defaultDurationNs / 1_000_000
        val items = ArrayList<Triple<Long, Long, String>>()
        for (b in raw) {
            val bytes = when (track.compAlgo) {
                0 -> InflaterInputStream(b.data.inputStream()).use { inp ->
                    val o = ByteArrayOutputStream()
                    inp.copyTo(o)
                    o.toByteArray()
                }
                3 -> track.compSettings + b.data
                else -> b.data
            }
            val raw0 = SubtitleParser.decode(bytes, "auto", fallbackCharset).trimEnd('\u0000')
            val text = when (kind) {
                1 -> SubtitleParser.assEventText(raw0)
                2 -> SubtitleParser.plainText(raw0.replace("\r\n", "\n").split('\n').drop(2).joinToString("\n"))
                else -> SubtitleParser.plainText(raw0)
            } ?: continue
            if (text.isBlank()) continue
            val start = b.startTicks * scaleNs / 1_000_000
            val end = when {
                b.durTicks >= 0 -> (b.startTicks + b.durTicks) * scaleNs / 1_000_000
                defDurMs > 0 -> start + defDurMs
                else -> -1L
            }
            items += Triple(start, end, text)
        }
        items.sortBy { it.first }
        val starts = items.map { it.first }.distinct()
        return items.map { (s, e, t) ->
            val end = if (e > s) e else {
                val next = starts.firstOrNull { it > s }
                if (next != null) minOf(next, s + 5_000) else s + 3_000
            }
            SubCue(s, end, t)
        }
    }
}

/** EBML okuyucu: ileri okuma, tampon ile; atlamada `InputStream.skip` (yerel dosyada lseek). */
internal class EbmlReader(private val input: InputStream) {
    private val buf = ByteArray(16 * 1024)
    private var len = 0
    private var pos = 0
    /** Dosyadaki mutlak konum. */
    var position = 0L
        private set

    private fun fill(): Boolean {
        len = input.read(buf, 0, buf.size)
        pos = 0
        if (len <= 0) { len = 0; return false }
        return true
    }

    fun readByteOrEof(): Int {
        if (pos >= len && !fill()) return -1
        position++
        return buf[pos++].toInt() and 0xFF
    }

    fun readByte(): Int {
        val b = readByteOrEof()
        if (b < 0) throw EOFException()
        return b
    }

    /** Öğe kimliği (işaret bitleri dahil); dosya sonunda null. */
    fun readId(): Long? {
        val first = readByteOrEof()
        if (first < 0) return null
        var mask = 0x80
        var n = 1
        while (n <= 4 && first and mask == 0) { mask = mask shr 1; n++ }
        if (n > 4) throw IOException("bad id at ${position - 1}")
        var v = first.toLong()
        repeat(n - 1) { v = (v shl 8) or readByte().toLong() }
        return v
    }

    /** Öğe boyutu; bilinmeyen boyut için -1. */
    fun readSize(): Long {
        val first = readByte()
        var mask = 0x80
        var n = 1
        while (n <= 8 && first and mask == 0) { mask = mask shr 1; n++ }
        if (n > 8) throw IOException("bad size at ${position - 1}")
        var v = (first and (mask - 1)).toLong()
        var allOnes = v == (mask - 1).toLong()
        repeat(n - 1) {
            val b = readByte()
            if (b != 0xFF) allOnes = false
            v = (v shl 8) or b.toLong()
        }
        return if (allOnes) -1L else v
    }

    /** Blok başlığındaki parça numarası (boyut kodlamasıyla aynı). */
    fun readVint(): Long {
        val v = readSize()
        if (v < 0) throw IOException("bad track number")
        return v
    }

    fun readUInt(size: Long): Long {
        if (size !in 0..8) { skip(size); return 0 }
        var v = 0L
        repeat(size.toInt()) { v = (v shl 8) or readByte().toLong() }
        return v
    }

    fun readBytes(size: Long): ByteArray {
        if (size < 0 || size > (16 shl 20)) throw IOException("element too large: $size")
        val out = ByteArray(size.toInt())
        var off = 0
        while (off < out.size) {
            if (pos >= len && !fill()) throw EOFException()
            val n = minOf(len - pos, out.size - off)
            System.arraycopy(buf, pos, out, off, n)
            pos += n; off += n; position += n
        }
        return out
    }

    fun skip(size: Long) {
        if (size <= 0) return
        val inBuf = (len - pos).toLong()
        if (size <= inBuf) {
            pos += size.toInt(); position += size
            return
        }
        var left = size - inBuf
        position += inBuf
        pos = len
        while (left > 0) {
            val n = input.skip(left)
            if (n > 0) { left -= n; position += n; continue }
            // skip ilerlemediyse okuyarak atla (bazı ağ akışları); dosya sonuysa dur.
            if (!fill()) throw EOFException()
            val take = minOf(len.toLong(), left).toInt()
            pos = take; left -= take; position += take
        }
    }
}
