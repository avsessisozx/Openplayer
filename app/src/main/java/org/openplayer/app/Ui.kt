package org.openplayer.app

import android.content.Context

/** Arayüz renkleri; seçili temadan okunur ([ThemeManager]). */
object UiColors {
    val accent: Int get() = ThemeManager.accent
    val background: Int get() = ThemeManager.background
    val surface: Int get() = ThemeManager.surface
    val textPrimary: Int get() = 0xFFEDEDED.toInt()
    val textSecondary: Int get() = ThemeManager.current.textSecondary
    val folderIcon: Int get() = 0xFF4A5560.toInt()
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
