package org.openplayer.app

import android.content.Context

/** Çalma sayıları ve son çalınma zamanı ("en çok çalınanlar", "son çalınanlar" listeleri için). */
object MusicStats {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("music_stats", Context.MODE_PRIVATE)

    fun record(c: Context, trackId: String) {
        val p = prefs(c)
        p.edit()
            .putInt("c_$trackId", p.getInt("c_$trackId", 0) + 1)
            .putLong("l_$trackId", System.currentTimeMillis())
            .apply()
    }

    fun count(c: Context, trackId: String): Int = prefs(c).getInt("c_$trackId", 0)

    fun last(c: Context, trackId: String): Long = prefs(c).getLong("l_$trackId", 0L)

    fun clear(c: Context) {
        prefs(c).edit().clear().apply()
    }
}
