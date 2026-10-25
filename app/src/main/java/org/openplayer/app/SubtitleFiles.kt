package org.openplayer.app

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

/** Videonun yanındaki altyazı dosyalarını bulur ve çevrimiçi indirilenleri hatırlar. */
object SubtitleFiles {

    @Suppress("DEPRECATION")
    fun pathOf(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: Tx.s(R.string.mediainfo_013)
    }

    fun sizeOf(context: Context, uri: Uri): Long {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
                }
            }
        }
        return pathOf(context, uri)?.let { File(it).length() } ?: 0L
    }

    /** Aynı ada sahip altyazılar. "Tüm dosyalara erişim" izni yoksa liste boş döner. */
    fun findSidecars(context: Context, video: Uri): List<File> {
        val path = pathOf(context, video) ?: return emptyList()
        val file = File(path)
        val base = file.nameWithoutExtension
        val siblings = file.parentFile?.listFiles() ?: return emptyList()
        return siblings.filter {
            it.isFile && it.extension.lowercase() in SubtitleParser.extensions &&
                    it.nameWithoutExtension.startsWith(base, ignoreCase = true)
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * Silme için yalnız bu videoya ait altyazılar: "Film.srt" ve "Film.tr.srt" gibi. "Film 2.srt" başka videoya
     * ait olabileceğinden sayılmaz. Erişim izni yoksa liste boş döner.
     */
    fun exactSidecarsForPath(videoPath: String): List<File> {
        if (videoPath.isEmpty()) return emptyList()
        val file = File(videoPath)
        val base = file.nameWithoutExtension
        val siblings = file.parentFile?.listFiles() ?: return emptyList()
        return siblings.filter {
            it.isFile && it.extension.lowercase() in SubtitleParser.extensions && it.extension.lowercase() != "txt" &&
                (it.nameWithoutExtension.equals(base, ignoreCase = true) ||
                    it.nameWithoutExtension.startsWith("$base.", ignoreCase = true))
        }
    }

    // ---- Her video için seçilmiş (indirilmiş veya eklenmiş) altyazı ----

    private fun prefs(c: Context) = c.getSharedPreferences("subtitle_links", Context.MODE_PRIVATE)

    fun remembered(context: Context, video: Uri): File? =
        prefs(context).getString(video.toString(), null)?.let { File(it) }?.takeIf { it.exists() }

    fun remember(context: Context, video: Uri, subtitle: File) {
        prefs(context).edit().putString(video.toString(), subtitle.absolutePath).apply()
    }

    fun downloadDir(context: Context): File = File(context.filesDir, "subtitles").apply { mkdirs() }

    /** Altyazı dosyası için en büyük kabul edilen boyut (gerçek altyazılar birkaç yüz KB'dir). */
    const val MAX_BYTES = 5L * 1024 * 1024

    /** [input]'u en fazla [MAX_BYTES] kadar kopyalar; sınır aşılırsa false döner. */
    fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream): Boolean {
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return true
            total += n
            if (total > MAX_BYTES) return false
            output.write(buf, 0, n)
        }
    }

    /** İndirilen/içe aktarılan altyazılar sınırsız birikmesin: en yeni 200 dosya kalır. */
    fun pruneDownloads(context: Context, keep: Int = 200) {
        runCatching {
            downloadDir(context).listFiles()?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.delete() }
        }
    }
}
