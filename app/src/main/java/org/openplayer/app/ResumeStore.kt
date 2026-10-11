package org.openplayer.app

import android.content.Context
import android.net.Uri

/** Son oynatılan video ve kaldığı konumu saklar. */
object ResumeStore {
    private fun prefs(c: Context) = c.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun lastUri(c: Context): String? = prefs(c).getString("last_uri", null)

    fun position(c: Context, uri: Uri): Long = prefs(c).getLong("pos_$uri", 0L)

    fun save(c: Context, uri: Uri, positionMs: Long) {
        prefs(c).edit()
            .putString("last_uri", uri.toString())
            .putLong("pos_$uri", positionMs)
            .apply()
    }
}
