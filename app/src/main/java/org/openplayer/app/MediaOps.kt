package org.openplayer.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer

/** Kayıpsız kesme ve sesi çıkarma. Yeniden kodlama yapılmaz; örnekler olduğu gibi kopyalanır. */
object MediaOps {

    class Result(val ok: Boolean, val message: String)

    private fun newItem(context: Context, collection: Uri, name: String, mime: String, relPath: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return runCatching { context.contentResolver.insert(collection, values) }.getOrNull()
    }

    private fun finish(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
    }

    private fun discard(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun baseName(title: String) = title.substringBeforeLast('.', title).ifBlank { "video" }

    /** [startUs]..[endUs] aralığını yeni bir MP4 dosyasına kopyalar. Başlangıç, en yakın önceki anahtar kareye oturur. */
    fun cut(
        context: Context, src: Uri, title: String, startUs: Long, endUs: Long,
        progress: (Int) -> Unit, cancelled: () -> Boolean,
    ): Result {
        if (Build.VERSION.SDK_INT < 29) return Result(false, Tx.s(R.string.mediaops_001))
        if (endUs <= startUs) return Result(false, Tx.s(R.string.mediaops_002))
        val out = newItem(
            context, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            baseName(title) + " (kesit).mp4", "video/mp4", "Movies/OpenPlayer"
        ) ?: return Result(false, Tx.s(R.string.mediaops_003))
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        var started = false
        try {
            ex.setDataSource(context, src, null)
            var rotation = 0
            runCatching {
                val mr = MediaMetadataRetriever()
                mr.setDataSource(context, src)
                rotation = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                mr.release()
            }
            pfd = context.contentResolver.openFileDescriptor(out, "rw") ?: return Result(false, Tx.s(R.string.mediaops_004))
            val mx = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            if (rotation != 0) mx.setOrientationHint(rotation)
            val map = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                val dst = runCatching { mx.addTrack(f) }.getOrNull() ?: continue
                map[i] = dst
                ex.selectTrack(i)
            }
            if (map.isEmpty()) return Result(false, Tx.s(R.string.mediaops_005))
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            mx.start()
            started = true
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var base = -1L
            var lastPct = -1
            while (true) {
                if (cancelled()) {
                    return Result(false, Tx.s(R.string.mediaops_006))
                }
                val t = ex.sampleTime
                if (t < 0 || t > endUs) break
                val track = ex.sampleTrackIndex
                val dst = map[track]
                if (dst == null) {
                    ex.advance()
                    continue
                }
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                if (base < 0) base = t
                info.set(0, size, (t - base).coerceAtLeast(0), if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                mx.writeSampleData(dst, buf, info)
                val pct = (((t - startUs).toDouble() / (endUs - startUs)) * 100).toInt().coerceIn(0, 100)
                if (pct != lastPct) {
                    lastPct = pct
                    progress(pct)
                }
                ex.advance()
            }
            mx.stop()
            started = false
            mx.release()
            muxer = null
            pfd.close()
            pfd = null
            finish(context, out)
            return Result(true, Tx.s(R.string.mediaops_026))
        } catch (e: Exception) {
            discard(context, out)
            return Result(false, Tx.s(R.string.mediaops_cut_failed, e.message ?: Tx.s(R.string.playermenu_067)))
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { pfd?.close() }
            runCatching { ex.release() }
            if (cancelled()) discard(context, out)
        }
    }

    class AudioTrackInfo(val index: Int, val label: String, val supported: Boolean)

    /** Çıkarılabilecek ses akışlarını listeler. */
    fun audioTracks(context: Context, src: Uri): List<AudioTrackInfo> {
        val list = ArrayList<AudioTrackInfo>()
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, src, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val lang = if (f.containsKey("language")) f.getString("language") else null
                val ch = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
                val codec = mime.removePrefix("audio/")
                val ok = mime == "audio/mp4a-latm" || mime == "audio/mpeg" || (mime == "audio/opus" && Build.VERSION.SDK_INT >= 29)
                list += AudioTrackInfo(i, "${i + 1}. $codec${if (ch > 0) " · $ch kanal" else ""}${if (lang != null && lang != "und") " · $lang" else ""}", ok)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { ex.release() }
        }
        return list
    }

    /** Seçilen ses akışını yeniden kodlamadan müzik dosyası olarak kaydeder (AAC→m4a, MP3→mp3, Opus→ogg). */
    fun extractAudio(
        context: Context, src: Uri, title: String, trackIndex: Int,
        progress: (Int) -> Unit, cancelled: () -> Boolean,
    ): Result {
        if (Build.VERSION.SDK_INT < 29) return Result(false, Tx.s(R.string.mediaops_007))
        val ex = MediaExtractor()
        var out: Uri? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            ex.setDataSource(context, src, null)
            val f = ex.getTrackFormat(trackIndex)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: return Result(false, Tx.s(R.string.mediaops_008))
            val (ext, fileMime) = when (mime) {
                "audio/mp4a-latm" -> "m4a" to "audio/mp4"
                "audio/mpeg" -> "mp3" to "audio/mpeg"
                "audio/opus" -> "ogg" to "audio/ogg"
                else -> return Result(false, Tx.s(R.string.mediaops_009, mime))
            }
            out = newItem(
                context, MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                baseName(title) + ".$ext", fileMime, "Music/OpenPlayer"
            ) ?: return Result(false, Tx.s(R.string.mediaops_003))
            pfd = context.contentResolver.openFileDescriptor(out, "rw") ?: return Result(false, Tx.s(R.string.mediaops_004))
            ex.selectTrack(trackIndex)
            val dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            val buf = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var lastPct = -1
            if (ext == "mp3") {
                // MP3 kareleri bağımsızdır; ham akış olarak yazılır.
                java.io.FileOutputStream(pfd.fileDescriptor).use { os ->
                    val tmp = ByteArray(2 * 1024 * 1024)
                    while (true) {
                        if (cancelled()) return Result(false, Tx.s(R.string.mediaops_006))
                        buf.clear()
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        buf.get(tmp, 0, size)
                        os.write(tmp, 0, size)
                        if (dur > 0) {
                            val pct = (ex.sampleTime * 100 / dur).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; progress(pct) }
                        }
                        ex.advance()
                    }
                }
            } else {
                val mx = MediaMuxer(
                    pfd.fileDescriptor,
                    if (ext == "ogg") MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                muxer = mx
                val dst = mx.addTrack(f)
                mx.start()
                started = true
                var base = -1L
                while (true) {
                    if (cancelled()) return Result(false, Tx.s(R.string.mediaops_006))
                    buf.clear()
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) break
                    val t = ex.sampleTime
                    if (base < 0) base = t
                    info.set(0, size, (t - base).coerceAtLeast(0), 0)
                    mx.writeSampleData(dst, buf, info)
                    if (dur > 0) {
                        val pct = (t * 100 / dur).toInt().coerceIn(0, 100)
                        if (pct != lastPct) { lastPct = pct; progress(pct) }
                    }
                    ex.advance()
                }
                mx.stop()
                started = false
            }
            finish(context, out)
            return Result(true, Tx.s(R.string.mediaops_027))
        } catch (e: Exception) {
            out?.let { discard(context, it) }
            return Result(false, Tx.s(R.string.mediaops_010, e.message ?: "bilinmeyen hata"))
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { pfd?.close() }
            runCatching { ex.release() }
            if (cancelled()) out?.let { discard(context, it) }
        }
    }
}

// ---------------------------------------------------------------- arayüz

private fun parseTime(s: String): Long? {
    val parts = s.trim().replace(',', '.').split(':')
    if (parts.isEmpty() || parts.size > 3) return null
    var total = 0.0
    for (p in parts) total = total * 60 + (p.toDoubleOrNull() ?: return null)
    return (total * 1000).toLong()
}

/** Çalışan işi ilerleme penceresiyle yürütür. */
private fun android.app.Activity.runWithProgress(
    title: String,
    work: (progress: (Int) -> Unit, cancelled: () -> Boolean) -> MediaOps.Result,
    onDone: (MediaOps.Result) -> Unit,
) {
    val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
    val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
    val pctText = label("0%")
    val box = vertical { addView(pctText); addView(bar) }
    val dialog = PanelBuilder(this).setTitle(title).setView(box).setCancelable(false)
        .setNegativeButton(Tx.s(R.string.fileops_004)) { _, _ -> cancel.set(true) }.create()
    dialog.show()
    Thread {
        val r = work({ p -> runOnUiThread { bar.progress = p; pctText.text = "$p%" } }, { cancel.get() })
        runOnUiThread {
            dialog.dismiss()
            android.widget.Toast.makeText(this, r.message, android.widget.Toast.LENGTH_LONG).show()
            onDone(r)
        }
    }.start()
}

/** Kes penceresi: başlangıç ve bitiş zamanı. [currentPos] verilirse "şimdi" düğmeleri çıkar. */
fun android.app.Activity.showTrimDialog(uri: Uri, title: String, durationMs: Long, currentPos: (() -> Long)? = null, onDone: () -> Unit = {}) {
    if (Build.VERSION.SDK_INT < 29) {
        showMessage(Tx.s(R.string.mainactivi_047), Tx.s(R.string.mediaops_011))
        return
    }
    fun field(hint: String, initial: String) = android.widget.EditText(this).apply {
        setText(initial)
        this.hint = hint
        setSingleLine()
    }
    fun fmt(ms: Long) = formatDuration(ms)
    val start = field(Tx.s(R.string.mediaops_012), "0:00")
    val end = field(Tx.s(R.string.mediaops_013), if (durationMs > 0) fmt(durationMs) else "")
    val box = vertical {
        addView(label(Tx.s(R.string.mediaops_014, if (durationMs > 0) fmt(durationMs) else "bilinmiyor"), 13f, UiColors.textSecondary))
        addView(label(Tx.s(R.string.mediaops_015), 13f, UiColors.textSecondary))
        addView(start)
        if (currentPos != null) addView(android.widget.Button(this@showTrimDialog).apply {
            text = Tx.s(R.string.mediaops_016)
            setOnClickListener { start.setText(fmt(currentPos())) }
        })
        addView(label(Tx.s(R.string.mediaops_017), 13f, UiColors.textSecondary))
        addView(end)
        if (currentPos != null) addView(android.widget.Button(this@showTrimDialog).apply {
            text = Tx.s(R.string.mediaops_018)
            setOnClickListener { end.setText(fmt(currentPos())) }
        })
        addView(label(
            Tx.s(R.string.mediaops_019),
            12f, UiColors.textSecondary
        ), android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }
    PanelBuilder(this)
        .setTitle(Tx.s(R.string.mainactivi_047))
        .setView(android.widget.ScrollView(this).apply { addView(box) })
        .setPositiveButton(Tx.s(R.string.mainactivi_047)) { _, _ ->
            val s = parseTime(start.text.toString())
            val e = parseTime(end.text.toString()) ?: durationMs
            if (s == null || e <= s || (durationMs > 0 && s >= durationMs)) {
                android.widget.Toast.makeText(this, Tx.s(R.string.mediaops_020), android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val app = applicationContext
                runWithProgress(Tx.s(R.string.mediaops_028),
                    { p, c -> MediaOps.cut(app, uri, title, s * 1000, e * 1000, p, c) }) { onDone() }
            }
        }
        .setNegativeButton(Tx.s(R.string.fileops_004), null)
        .show()
}

/** Videodan sesi çıkarır; birden fazla ses akışı varsa seçtirir. */
fun android.app.Activity.showExtractAudio(uri: Uri, title: String, onDone: () -> Unit = {}) {
    if (Build.VERSION.SDK_INT < 29) {
        showMessage(Tx.s(R.string.mediaops_021), Tx.s(R.string.mediaops_011))
        return
    }
    val app = applicationContext
    Thread {
        val tracks = MediaOps.audioTracks(app, uri)
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val ok = tracks.filter { it.supported }
            when {
                tracks.isEmpty() -> android.widget.Toast.makeText(this, Tx.s(R.string.mediaops_022), android.widget.Toast.LENGTH_SHORT).show()
                ok.isEmpty() -> showMessage(
                    Tx.s(R.string.mediaops_021),
                    Tx.s(R.string.mediaops_023) + tracks.joinToString("\n") { it.label } +
                        "\n\nDesteklenenler: AAC (m4a), MP3, Opus (ogg)."
                )
                ok.size == 1 -> doExtract(uri, title, ok[0].index, onDone)
                else -> showChoice(Tx.s(R.string.mediaops_024), ok.map { it.label }, -1) { doExtract(uri, title, ok[it].index, onDone) }
            }
        }
    }.start()
}

private fun android.app.Activity.doExtract(uri: Uri, title: String, index: Int, onDone: () -> Unit) {
    val app = applicationContext
    runWithProgress(Tx.s(R.string.mediaops_025), { p, c -> MediaOps.extractAudio(app, uri, title, index, p, c) }) { onDone() }
}
