package org.openplayer.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class OsResult(
    val fileId: Long,
    val release: String,
    val language: String,
    val downloads: Int,
    val hashMatch: Boolean,
)

/**
 * opensubtitles.com REST API istemcisi. API anahtarı koda gömülmez;
 * kullanıcı Ayarlar'dan kendi anahtarını girer.
 */
class OpenSubtitlesClient(
    private val apiKey: String,
    private val user: String,
    private val pass: String,
) {
    private val base = "https://api.opensubtitles.com/api/v1"
    private var token: String? = null

    private fun call(method: String, url: String, body: String? = null, bearer: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Api-Key", apiKey)
            conn.setRequestProperty("User-Agent", "OpenPlayer v0.3")
            conn.setRequestProperty("Accept", "application/json")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                throw IOException("OpenSubtitles hatası $code${if (msg.isNotEmpty()) ": $msg" else ""}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun login() {
        if (token != null || user.isEmpty() || pass.isEmpty()) return
        val body = JSONObject().put("username", user).put("password", pass).toString()
        token = JSONObject(call("POST", "$base/login", body)).optString("token").ifEmpty { null }
    }

    fun search(query: String, languages: String, movieHash: String?): List<OsResult> {
        if (apiKey.isEmpty()) throw IOException("OpenSubtitles API anahtarı girilmemiş (Ayarlar > Altyazı)")
        val params = buildString {
            append("query=").append(URLEncoder.encode(query, "UTF-8"))
            if (languages.isNotBlank()) append("&languages=").append(URLEncoder.encode(languages.replace(" ", ""), "UTF-8"))
            if (movieHash != null) append("&moviehash=").append(movieHash)
            append("&order_by=download_count&order_direction=desc")
        }
        val json = JSONObject(call("GET", "$base/subtitles?$params"))
        val data = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<OsResult>()
        for (i in 0 until data.length()) {
            val attr = data.getJSONObject(i).optJSONObject("attributes") ?: continue
            val files = attr.optJSONArray("files") ?: continue
            if (files.length() == 0) continue
            out += OsResult(
                fileId = files.getJSONObject(0).optLong("file_id"),
                release = attr.optString("release").ifEmpty { files.getJSONObject(0).optString("file_name") },
                language = attr.optString("language"),
                downloads = attr.optInt("download_count"),
                hashMatch = attr.optBoolean("moviehash_match"),
            )
        }
        return out.sortedByDescending { it.hashMatch }
    }

    fun download(fileId: Long): ByteArray {
        login()
        val response = JSONObject(call("POST", "$base/download", JSONObject().put("file_id", fileId).toString(), token))
        val link = response.optString("link")
        if (link.isEmpty()) throw IOException("İndirme bağlantısı alınamadı")
        // Sunucudan gelen bağlantı güvenilmez: yalnızca https, yönlendirme yok, boyut sınırlı.
        val url = URL(link)
        if (url.protocol != "https") throw IOException("Güvensiz indirme bağlantısı reddedildi")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode !in 200..299) throw IOException("İndirme hatası ${conn.responseCode}")
            val out = ByteArrayOutputStream()
            if (!SubtitleFiles.copyLimited(conn.inputStream, out)) throw IOException("Altyazı çok büyük, reddedildi")
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }
}

/** opensubtitles.org uyumlu dosya özeti: boyut + ilk/son 64 KB. */
object OsHash {
    fun compute(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val size = pfd.statSize
            val chunk = 65536
            if (size < chunk * 2) return null
            java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                var hash = size
                fun addChunk(position: Long) {
                    val buf = ByteBuffer.allocate(chunk).order(ByteOrder.LITTLE_ENDIAN)
                    ch.position(position)
                    while (buf.hasRemaining()) if (ch.read(buf) < 0) break
                    buf.flip()
                    val longs = buf.asLongBuffer()
                    while (longs.hasRemaining()) hash += longs.get()
                }
                addChunk(0)
                addChunk(size - chunk)
                "%016x".format(hash)
            }
        }
    }.getOrNull()

    /** Dosya adından arama sorgusu çıkarır (uzantı, çözünürlük ve yayın etiketleri atılır). */
    fun cleanTitle(fileName: String): String {
        var t = fileName.substringBeforeLast('.')
        t = t.replace(Regex("""[._]"""), " ")
        t = t.replace(Regex("""\b(1080p|720p|480p|2160p|4k|x264|x265|h264|h265|hevc|bluray|brrip|webrip|web-dl|hdrip|dvdrip|türkçe|dublaj|altyazılı|izle|film)\b""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("""[\[(].*?[\])]"""), " ")
        return t.replace(Regex("""\s+"""), " ").trim()
    }
}
