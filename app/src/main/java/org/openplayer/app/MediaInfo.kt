package org.openplayer.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

class InfoSection(val title: String?, val rows: List<Pair<String, String>>)

/** Videonun/şarkının dosya ve akış bilgilerini oynatmadan okur. */
object MediaInfo {

    private const val UNKNOWN = "bilinmiyor"

    private fun size(bytes: Long): String {
        if (bytes <= 0) return UNKNOWN
        val gb = bytes / 1073741824.0
        val mb = bytes / 1048576.0
        val human = if (gb >= 1) "%.1f GB".format(gb) else "%.1f MB".format(mb)
        return "$human (${"%,d".format(bytes)} bayt)"
    }

    private fun bitrate(bps: Long): String = when {
        bps <= 0 -> UNKNOWN
        bps >= 1_000_000 -> "%.1f Mbit/sn".format(bps / 1_000_000.0)
        else -> "%.0f kbit/sn".format(bps / 1000.0)
    }

    private fun ratio(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return UNKNOWN
        val r = w.toDouble() / h
        val known = listOf(
            4.0 / 3 to "4:3", 16.0 / 10 to "16:10", 16.0 / 9 to "16:9", 1.85 to "1.85:1",
            2.0 to "2:1", 2.2 to "2.20:1", 2.35 to "2.35:1", 2.39 to "2.39:1", 2.4 to "2.40:1",
            21.0 / 9 to "21:9", 1.0 to "1:1", 9.0 / 16 to "9:16", 3.0 / 4 to "3:4",
        )
        val near = known.minByOrNull { abs(it.first - r) }
        return if (near != null && abs(near.first - r) < 0.02) "${near.second} (%.2f)".format(r) else "%.2f:1".format(r)
    }

    private fun codecName(mime: String?): String = when (mime?.lowercase()) {
        null -> UNKNOWN
        "video/avc" -> "H.264 / AVC"
        "video/hevc" -> "H.265 / HEVC"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/mp4v-es" -> "MPEG-4 Visual"
        "video/mpeg2" -> "MPEG-2"
        "video/3gpp" -> "H.263 / 3GP"
        "video/dolby-vision" -> "Dolby Vision"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/ac3" -> "AC-3 (Dolby Digital)"
        "audio/eac3" -> "E-AC-3 (Dolby Digital Plus)"
        "audio/eac3-joc" -> "E-AC-3 JOC (Dolby Atmos)"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/raw" -> "PCM"
        "audio/true-hd" -> "Dolby TrueHD"
        "audio/alac" -> "ALAC"
        "text/vtt" -> "WebVTT"
        "application/x-subrip" -> "SubRip (SRT)"
        "text/x-ssa" -> "SSA / ASS"
        "application/dvbsubs", "application/pgs", "application/vobsub" -> Tx.s(R.string.mediainfo_001)
        else -> mime
    }

    private fun channels(n: Int): String = when (n) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1 kanal"
        8 -> "7.1 kanal"
        else -> "$n kanal"
    }

    private fun profileName(mime: String?, p: Int): String? {
        if (p <= 0) return null
        return when (mime) {
            "video/avc" -> when (p) {
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
                MediaCodecInfo.CodecProfileLevel.AVCProfileExtended -> "Extended"
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "High 10"
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422 -> "High 4:2:2"
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444 -> "High 4:4:4"
                else -> null
            }
            "video/hevc" -> when (p) {
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main"
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main 10"
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill -> "Main Still"
                else -> null
            }
            "audio/mp4a-latm" -> when (p) {
                MediaCodecInfo.CodecProfileLevel.AACObjectLC -> "LC"
                MediaCodecInfo.CodecProfileLevel.AACObjectHE -> "HE-AAC"
                MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS -> "HE-AAC v2"
                MediaCodecInfo.CodecProfileLevel.AACObjectMain -> "Main"
                MediaCodecInfo.CodecProfileLevel.AACObjectELD -> "ELD"
                else -> null
            }
            else -> null
        }
    }

    private fun fmtDate(ms: Long): String =
        if (ms <= 0) UNKNOWN else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    /** Arka planda çağrılmalı; dosya içeriğini okur. */
    fun read(context: Context, uri: Uri, title: String, sizeBytes: Long, dateMs: Long): List<InfoSection> {
        val sections = ArrayList<InfoSection>()
        val path = SubtitleFiles.pathOf(context, uri)
        val name = path?.substringAfterLast('/') ?: title
        val file = ArrayList<Pair<String, String>>()
        file += Tx.s(R.string.mediainfo_025) to name
        if (path != null) file += Tx.s(R.string.mainactivi_076) to path.substringBeforeLast('/', "/")
        val realSize = if (sizeBytes > 0) sizeBytes else path?.let { File(it).length() } ?: 0L
        file += Tx.s(R.string.listpanel_022) to size(realSize)
        file += Tx.s(R.string.listpanel_023) to fmtDate(dateMs)
        sections += InfoSection(Tx.s(R.string.mediainfo_025), file)

        val mr = MediaMetadataRetriever()
        val media = ArrayList<Pair<String, String>>()
        try {
            mr.setDataSource(context, uri)
            val mime = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            media += Tx.s(R.string.mediainfo_002) to (mime ?: UNKNOWN)
            val w = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (w > 0) {
                media += Tx.s(R.string.listpanel_011) to "$w x $h"
                media += Tx.s(R.string.mediainfo_003) to ratio(w, h)
            }
            val rot = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot != 0) media += Tx.s(R.string.mediainfo_004) to "$rot°"
            val dur = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (dur > 0) media += Tx.s(R.string.listpanel_020) to formatDuration(dur)
            val br = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            if (br > 0) media += Tx.s(R.string.mediainfo_005) to bitrate(br)
            mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.let {
                if (it.isNotBlank()) media += Tx.s(R.string.mediainfo_006) to it
            }
            mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { media += Tx.s(R.string.mediainfo_007) to it }
            mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { media += Tx.s(R.string.mediainfo_008) to it }
            mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { media += Tx.s(R.string.mediainfo_009) to it }
            mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.takeIf { it.isNotBlank() }?.let { media += Tx.s(R.string.mediainfo_010) to it }
            mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.takeIf { it.isNotBlank() }?.let { media += Tx.s(R.string.mediainfo_011) to it }
            mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)?.takeIf { it.isNotBlank() }?.let { media += Tx.s(R.string.mediainfo_012) to it }
        } catch (_: Exception) {
        } finally {
            runCatching { mr.release() }
        }
        if (media.isNotEmpty()) sections += InfoSection(Tx.s(R.string.mediainfo_026), media)

        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME)
                val rows = ArrayList<Pair<String, String>>()
                val kind = when {
                    mime?.startsWith("video/") == true -> Tx.s(R.string.mediainfo_013)
                    mime?.startsWith("audio/") == true -> Tx.s(R.string.gesturecon_015)
                    mime?.startsWith("text/") == true || mime?.startsWith("application/") == true -> Tx.s(R.string.mediainfo_014)
                    else -> Tx.s(R.string.mediainfo_015)
                }
                rows += "Codec" to codecName(mime)
                if (Build.VERSION.SDK_INT >= 23 && f.containsKey(MediaFormat.KEY_PROFILE)) {
                    profileName(mime, f.getInteger(MediaFormat.KEY_PROFILE))?.let { rows += Tx.s(R.string.mediainfo_027) to it }
                }
                if (f.containsKey("language")) f.getString("language")?.takeIf { it.isNotBlank() && it != "und" }?.let { rows += Tx.s(R.string.mediainfo_028) to it }
                if (kind == Tx.s(R.string.mediainfo_013)) {
                    if (f.containsKey(MediaFormat.KEY_WIDTH)) rows += Tx.s(R.string.listpanel_011) to "${f.getInteger(MediaFormat.KEY_WIDTH)} x ${f.getInteger(MediaFormat.KEY_HEIGHT)}"
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        val fr = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                            .getOrElse { runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0f) }
                        if (fr > 0) rows += Tx.s(R.string.mediainfo_016) to "%.3f".format(fr).trimEnd('0').trimEnd('.')
                    }
                    if (Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                        when (f.getInteger(MediaFormat.KEY_COLOR_TRANSFER)) {
                            MediaFormat.COLOR_TRANSFER_ST2084 -> rows += "HDR" to "HDR10 (PQ)"
                            MediaFormat.COLOR_TRANSFER_HLG -> rows += "HDR" to "HLG"
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_COLOR_STANDARD) &&
                        f.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT2020
                    ) rows += Tx.s(R.string.mediainfo_017) to "BT.2020"
                }
                if (kind == Tx.s(R.string.gesturecon_015)) {
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rows += Tx.s(R.string.mediainfo_018) to "${f.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz"
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) rows += Tx.s(R.string.mediainfo_029) to channels(f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                }
                if (f.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    val b = f.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                    if (b > 0) rows += Tx.s(R.string.mediainfo_019) to bitrate(b)
                }
                sections += InfoSection(Tx.s(R.string.mediainfo_020, i + 1, kind), rows)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { ex.release() }
        }
        return sections
    }

    fun toText(title: String, sections: List<InfoSection>): String = buildString {
        appendLine(title)
        for (s in sections) {
            appendLine()
            s.title?.let { appendLine("[$it]") }
            for ((k, v) in s.rows) appendLine("$k: $v")
        }
    }.trim()

    fun sha256(context: Context, uri: Uri): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        } ?: return null
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}

private fun Context.infoView(sections: List<InfoSection>): LinearLayout {
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }
    for (s in sections) {
        s.title?.let {
            root.addView(TextView(this).apply {
                text = it
                setTextColor(UiColors.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(14), 0, dp(4))
            })
        }
        for ((k, v) in s.rows) {
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
                addView(TextView(context).apply {
                    text = k
                    setTextColor(UiColors.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                }, LinearLayout.LayoutParams(0, -2, 0.38f))
                addView(TextView(context).apply {
                    text = v
                    setTextColor(Color_WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextIsSelectable(true)
                }, LinearLayout.LayoutParams(0, -2, 0.62f))
            })
        }
    }
    return root
}

private const val Color_WHITE = 0xFFFFFFFF.toInt()

private fun Context.copyText(text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("OpenPlayer", text))
    android.widget.Toast.makeText(this, Tx.s(R.string.mediainfo_021), android.widget.Toast.LENGTH_SHORT).show()
}

/** Bilgi paneli: arka planda okur, sonra gösterir. Kopyala, paylaş ve (isteğe bağlı) SHA-256 düğmeleri vardır. */
fun android.app.Activity.showMediaInfo(title: String, uri: Uri, sizeBytes: Long, dateMs: Long) {
    val app = applicationContext
    val loading = android.widget.Toast.makeText(this, Tx.s(R.string.mediainfo_030), android.widget.Toast.LENGTH_SHORT)
    loading.show()
    Thread {
        val sections = MediaInfo.read(app, uri, title, sizeBytes, dateMs)
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val body = ScrollView(this).apply { addView(infoView(sections)) }
            PanelBuilder(this)
                .setTitle(title)
                .setView(body)
                .setPositiveButton(Tx.s(R.string.mediainfo_022), null)
                .setNeutralButton(Tx.s(R.string.mainactivi_005)) { _, _ -> copyText(MediaInfo.toText(title, sections)) }
                .setNegativeButton("SHA-256") { _, _ ->
                    android.widget.Toast.makeText(this, Tx.s(R.string.mediainfo_023), android.widget.Toast.LENGTH_SHORT).show()
                    Thread {
                        val h = MediaInfo.sha256(app, uri)
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                PanelBuilder(this).setTitle("SHA-256")
                                    .setMessage(h ?: Tx.s(R.string.mediainfo_024))
                                    .setPositiveButton(Tx.s(R.string.mediainfo_022), null)
                                    .setNeutralButton(Tx.s(R.string.mainactivi_005)) { _, _ -> h?.let { copyText(it) } }
                                    .show()
                            }
                        }
                    }.start()
                }
                .show()
        }
    }.start()
}

/** Çoklu seçim veya klasör için toplam bilgi. */
fun android.app.Activity.showSummaryInfo(title: String, lines: List<Pair<String, String>>) {
    PanelBuilder(this)
        .setTitle(title)
        .setView(ScrollView(this).apply { addView(infoView(listOf(InfoSection(null, lines)))) })
        .setPositiveButton(Tx.s(R.string.mediainfo_022), null)
        .setNeutralButton(Tx.s(R.string.mainactivi_005)) { _, _ -> copyText(lines.joinToString("\n") { "${it.first}: ${it.second}" }) }
        .show()
}
