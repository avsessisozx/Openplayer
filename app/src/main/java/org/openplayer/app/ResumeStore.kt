package org.openplayer.app

import android.content.Context
import android.net.Uri

/** Son oynatılan video, kaldığı konum, izlenme durumu ve yer imleri. */
object ResumeStore {
    private fun prefs(c: Context) = c.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun clearAll(c: Context) {
        prefs(c).edit().clear().apply()
        VideoMemory.clearAll(c)
    }

    fun lastUri(c: Context): String? = prefs(c).getString("last_uri", null)

    fun position(c: Context, uri: Uri): Long = prefs(c).getLong("pos_$uri", 0L)

    fun save(c: Context, uri: Uri, positionMs: Long) {
        prefs(c).edit()
            .putString("last_uri", uri.toString())
            .putLong("pos_$uri", positionMs)
            .putLong("at_$uri", System.currentTimeMillis())
            .apply()
    }

    fun lastPlayed(c: Context, uri: Uri): Long = prefs(c).getLong("at_$uri", 0L)

    fun markPlayed(c: Context, uri: Uri) {
        prefs(c).edit().putBoolean("played_$uri", true).apply()
    }

    fun isPlayed(c: Context, uri: String): Boolean = prefs(c).getBoolean("played_$uri", false)

    fun bookmarks(c: Context, uri: Uri): List<Long> =
        prefs(c).getString("bm_$uri", "")?.split(",")?.mapNotNull { it.toLongOrNull() }?.sorted() ?: emptyList()

    fun setBookmarks(c: Context, uri: Uri, list: List<Long>) {
        prefs(c).edit().putString("bm_$uri", list.distinct().sorted().joinToString(",")).apply()
    }
}
