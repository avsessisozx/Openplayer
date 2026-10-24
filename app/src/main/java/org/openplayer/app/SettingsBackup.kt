package org.openplayer.app

import android.content.Context
import org.json.JSONObject

/**
 * Ayarları tek bir JSON dosyasına aktarır / dosyadan yükler.
 * Parola ve API anahtarı dosyaya yazılmaz; içe aktarırken de kabul edilmez.
 */
object SettingsBackup {
    private const val FORMAT = "openplayer-settings-1"
    private const val MAX_BYTES = 256 * 1024
    private val files = listOf("settings", "gestures")
    private val secret = setOf("os_key", "os_pass", "tr_key")

    fun export(c: Context): String {
        val root = JSONObject().put("format", FORMAT)
        for (f in files) {
            val o = JSONObject()
            for ((k, v) in c.getSharedPreferences(f, Context.MODE_PRIVATE).all) {
                if (k in secret) continue
                when (v) {
                    is Boolean -> o.put(k, JSONObject().put("t", "b").put("v", v))
                    is Int -> o.put(k, JSONObject().put("t", "i").put("v", v))
                    is Long -> o.put(k, JSONObject().put("t", "l").put("v", v))
                    is Float -> o.put(k, JSONObject().put("t", "f").put("v", v.toDouble()))
                    is String -> o.put(k, JSONObject().put("t", "s").put("v", v))
                }
            }
            root.put(f, o)
        }
        return root.toString(2)
    }

    /** Geçerliyse uygular ve true döner. */
    fun import(c: Context, text: String): Boolean {
        if (text.length > MAX_BYTES) return false
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        if (root.optString("format") != FORMAT) return false
        for (f in files) {
            val o = root.optJSONObject(f) ?: continue
            val ed = c.getSharedPreferences(f, Context.MODE_PRIVATE).edit()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k in secret || k.length > 64) continue
                val e = o.optJSONObject(k) ?: continue
                when (e.optString("t")) {
                    "b" -> ed.putBoolean(k, e.optBoolean("v"))
                    "i" -> ed.putInt(k, e.optInt("v"))
                    "l" -> ed.putLong(k, e.optLong("v"))
                    "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                    "s" -> {
                        val s = e.optString("v")
                        if (s.length <= 4096) ed.putString(k, s)
                    }
                }
            }
            ed.apply()
        }
        ThemeManager.reload()
        return true
    }
}
