package org.openplayer.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/** Her video için ayrı hatırlanan oynatma ayarları (hız, görünüm, altyazı eşitleme, ses parçası). */
object VideoMemory {
    data class Entry(
        val speed: Float = 1f,
        val aspect: String = AspectMode.FIT.name,
        val zoom: Float = 1f,
        val subDelayMs: Long = 0L,
        val subSpeed: Double = 1.0,
        val audioIndex: Int = -1,
    )

    private fun prefs(c: Context) = c.getSharedPreferences("video_memory", Context.MODE_PRIVATE)

    fun get(c: Context, uri: Uri): Entry? {
        val raw = prefs(c).getString(uri.toString(), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Entry(
                speed = o.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 4f),
                aspect = o.optString("aspect", AspectMode.FIT.name),
                zoom = o.optDouble("zoom", 1.0).toFloat().coerceIn(1f, 5f),
                subDelayMs = o.optLong("subDelay", 0L).coerceIn(-600_000L, 600_000L),
                subSpeed = o.optDouble("subSpeed", 1.0).coerceIn(0.5, 2.0),
                audioIndex = o.optInt("audio", -1),
            )
        }.getOrNull()
    }

    fun put(c: Context, uri: Uri, e: Entry) {
        val o = JSONObject()
            .put("speed", e.speed.toDouble())
            .put("aspect", e.aspect)
            .put("zoom", e.zoom.toDouble())
            .put("subDelay", e.subDelayMs)
            .put("subSpeed", e.subSpeed)
            .put("audio", e.audioIndex)
        prefs(c).edit().putString(uri.toString(), o.toString()).apply()
    }

    fun setAudio(c: Context, uri: Uri, index: Int) {
        val e = get(c, uri) ?: Entry()
        put(c, uri, e.copy(audioIndex = index))
    }

    fun clearAll(c: Context) {
        prefs(c).edit().clear().apply()
    }
}
