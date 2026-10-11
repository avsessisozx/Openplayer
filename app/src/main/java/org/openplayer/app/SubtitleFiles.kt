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
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Video"
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

    // ---- Her video için seçilmiş (indirilmiş veya eklenmiş) altyazı ----

    private fun prefs(c: Context) = c.getSharedPreferences("subtitle_links", Context.MODE_PRIVATE)

    fun remembered(context: Context, video: Uri): File? =
        prefs(context).getString(video.toString(), null)?.let { File(it) }?.takeIf { it.exists() }

    fun remember(context: Context, video: Uri, subtitle: File) {
        prefs(context).edit().putString(video.toString(), subtitle.absolutePath).apply()
    }

    fun downloadDir(context: Context): File = File(context.filesDir, "subtitles").apply { mkdirs() }
}
