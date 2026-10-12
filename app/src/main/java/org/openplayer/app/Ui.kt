package org.openplayer.app

import android.content.Context

object UiColors {
    val accent = 0xFF2F8CFF.toInt()
    val textPrimary = 0xFFEDEDED.toInt()
    val textSecondary = 0xFF9AA3AD.toInt()
    val folderIcon = 0xFF4A5560.toInt()
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
