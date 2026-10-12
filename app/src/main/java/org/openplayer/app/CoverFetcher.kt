package org.openplayer.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Albüm kapağını iTunes Search API'den (anahtarsız, herkese açık) arar. Yalnızca kullanıcı onaylayınca çağrılır. */
object CoverFetcher {

    private const val MAX_BYTES = 4 * 1024 * 1024

    private fun get(url: String, limit: Int): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "OpenPlayer/1.0")
        }
        return try {
            if (conn.responseCode != 200) return null
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > limit) return null
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Başarılıysa kapak görselinin baytlarını döndürür; bulunamazsa null. */
    fun fetch(artist: String, album: String): ByteArray? = runCatching {
        val term = URLEncoder.encode("$artist $album", "UTF-8")
        val json = get("https://itunes.apple.com/search?term=$term&entity=album&limit=1", 200_000) ?: return null
        val results = JSONObject(String(json, Charsets.UTF_8)).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val small = results.getJSONObject(0).optString("artworkUrl100")
        if (!small.startsWith("https://")) return null
        get(small.replace("100x100bb", "600x600bb"), MAX_BYTES)
    }.getOrNull()
}
