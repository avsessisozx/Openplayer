package org.openplayer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * Şarkı etiketi düzenlemeleri. Dosyaya dokunulmaz; değişiklikler uygulamanın içinde saklanır,
 * kütüphanede ve çalarken gösterilir. "Orijinale döndür" ile silinir.
 */
object MusicTagStore {

    class Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val genre: String? = null,
        val year: Int? = null,
        val trackNo: Int? = null,
        val cover: Boolean = false,
    )

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("music_tags", Context.MODE_PRIVATE)

    private fun coverFile(c: Context, id: Long) = File(File(c.applicationContext.filesDir, "covers"), "$id.jpg")

    fun get(c: Context, id: Long): Tags? {
        val raw = prefs(c).getString(id.toString(), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Tags(
                title = o.optString("title").takeIf { o.has("title") },
                artist = o.optString("artist").takeIf { o.has("artist") },
                album = o.optString("album").takeIf { o.has("album") },
                albumArtist = o.optString("albumArtist").takeIf { o.has("albumArtist") },
                genre = o.optString("genre").takeIf { o.has("genre") },
                year = if (o.has("year")) o.optInt("year") else null,
                trackNo = if (o.has("trackNo")) o.optInt("trackNo") else null,
                cover = o.optBoolean("cover", false) && coverFile(c, id).exists(),
            )
        }.getOrNull()
    }

    fun save(c: Context, id: Long, t: Tags) {
        val o = JSONObject()
        t.title?.let { o.put("title", it.take(200)) }
        t.artist?.let { o.put("artist", it.take(200)) }
        t.album?.let { o.put("album", it.take(200)) }
        t.albumArtist?.let { o.put("albumArtist", it.take(200)) }
        t.genre?.let { o.put("genre", it.take(100)) }
        t.year?.let { o.put("year", it) }
        t.trackNo?.let { o.put("trackNo", it) }
        if (t.cover) o.put("cover", true)
        prefs(c).edit().putString(id.toString(), o.toString()).apply()
    }

    fun reset(c: Context, id: Long) {
        prefs(c).edit().remove(id.toString()).apply()
        coverFile(c, id).delete()
    }

    fun clearAll(c: Context) {
        prefs(c).edit().clear().apply()
        File(c.applicationContext.filesDir, "covers").deleteRecursively()
    }

    /** Seçilen görseli küçültüp uygulamaya kopyalar. */
    fun setCover(c: Context, id: Long, source: Uri): Boolean = runCatching {
        val dir = File(c.applicationContext.filesDir, "covers").apply { mkdirs() }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 1024 && opts.outHeight / (sample * 2) >= 1024) sample *= 2
        val bmp = c.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        File(dir, "$id.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val cur = get(c, id)
        save(c, id, Tags(cur?.title, cur?.artist, cur?.album, cur?.albumArtist, cur?.genre, cur?.year, cur?.trackNo, cover = true))
        true
    }.getOrDefault(false)

    fun apply(c: Context, t: MusicTrack): MusicTrack {
        val g = get(c, t.id) ?: return t
        return t.copy(
            title = g.title?.takeIf { it.isNotBlank() } ?: t.title,
            artist = g.artist?.takeIf { it.isNotBlank() } ?: t.artist,
            album = g.album?.takeIf { it.isNotBlank() } ?: t.album,
            albumArtist = g.albumArtist ?: t.albumArtist,
            genre = g.genre ?: t.genre,
            year = g.year ?: t.year,
            trackNo = g.trackNo ?: t.trackNo,
            coverFile = if (g.cover) coverFile(c, t.id).absolutePath else null,
        )
    }
}
