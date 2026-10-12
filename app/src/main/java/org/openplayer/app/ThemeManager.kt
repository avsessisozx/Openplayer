package org.openplayer.app

import android.app.Activity
import android.content.Context
import org.json.JSONObject

/** Bir renk teması: arka plan, panel yüzeyi ve vurgu rengi. Hepsi koyu temadır (açık metin için). */
data class AppTheme(
    val id: String,
    val name: String,
    val background: Int,
    val surface: Int,
    val accent: Int,
    val textSecondary: Int = 0xFF9AA3AD.toInt(),
)

enum class ButtonShape(val label: String) {
    CIRCLE("Daire"),
    ROUNDED("Yuvarlak kare"),
    SQUARE("Kare"),
}

/** Seçili temayı tutar; arayüz renkleri [UiColors] üzerinden buradan okunur. */
object ThemeManager {

    val presets = listOf(
        AppTheme("dark", "Koyu", 0xFF000000.toInt(), 0xFF1E242B.toInt(), 0xFF2F8CFF.toInt()),
        AppTheme("amoled", "AMOLED", 0xFF000000.toInt(), 0xFF0B0B0B.toInt(), 0xFF00E5FF.toInt()),
        AppTheme("ocean", "Okyanus", 0xFF06141F.toInt(), 0xFF0E2A3D.toInt(), 0xFF29B6F6.toInt()),
        AppTheme("sunset", "Gün batımı", 0xFF1A0E10.toInt(), 0xFF2E1A1E.toInt(), 0xFFFF7043.toInt()),
        AppTheme("forest", "Orman", 0xFF0A140E.toInt(), 0xFF16281C.toInt(), 0xFF66BB6A.toInt()),
        AppTheme("purple", "Mor", 0xFF120A1C.toInt(), 0xFF241638.toInt(), 0xFFAB47BC.toInt()),
        AppTheme("rose", "Gül", 0xFF1A0A12.toInt(), 0xFF30152A.toInt(), 0xFFEC407A.toInt()),
        AppTheme("gold", "Altın", 0xFF14110A.toInt(), 0xFF2A2412.toInt(), 0xFFFFC107.toInt()),
        AppTheme("mono", "Gri", 0xFF0D0D0D.toInt(), 0xFF222222.toInt(), 0xFFB0BEC5.toInt()),
    )

    val accentChoices = listOf(
        "Temanın rengi" to 0,
        "Mavi" to 0xFF2F8CFF.toInt(),
        "Turkuaz" to 0xFF00BCD4.toInt(),
        "Yeşil" to 0xFF4CAF50.toInt(),
        "Sarı-yeşil" to 0xFFC0CA33.toInt(),
        "Sarı" to 0xFFFFC107.toInt(),
        "Turuncu" to 0xFFFF7043.toInt(),
        "Kırmızı" to 0xFFF44336.toInt(),
        "Pembe" to 0xFFEC407A.toInt(),
        "Mor" to 0xFFAB47BC.toInt(),
        "İndigo" to 0xFF5C6BC0.toInt(),
        "Beyaz" to 0xFFFFFFFF.toInt(),
    )

    private var app: Context? = null

    @Volatile
    var current: AppTheme = presets[0]
        private set

    @Volatile
    private var accentOverride = 0

    val accent: Int get() = if (accentOverride != 0) accentOverride else current.accent
    val background: Int get() = current.background
    val surface: Int get() = current.surface

    fun install(context: Context) {
        if (app == null) app = context.applicationContext
        reload()
    }

    fun reload() {
        val c = app ?: return
        val s = AppSettings.get(c)
        current = when (s.themeId) {
            "custom" -> parseCustom(s.customTheme) ?: presets[0]
            else -> presets.firstOrNull { it.id == s.themeId } ?: presets[0]
        }
        accentOverride = s.accentOverride
    }

    fun dialogStyle(): Int = when (current.id) {
        "amoled" -> R.style.PanelDialog_Amoled
        "ocean" -> R.style.PanelDialog_Ocean
        "sunset" -> R.style.PanelDialog_Sunset
        "forest" -> R.style.PanelDialog_Forest
        "purple" -> R.style.PanelDialog_Purple
        "rose" -> R.style.PanelDialog_Rose
        "gold" -> R.style.PanelDialog_Gold
        "mono" -> R.style.PanelDialog_Mono
        else -> R.style.PanelDialog
    }

    /** Durum ve gezinme çubuğunu tema arka planına boyar (oynatıcı hariç). */
    fun applyWindow(activity: Activity) {
        activity.window.statusBarColor = background
        activity.window.navigationBarColor = background
    }

    // ---------------------------------------------------------------- dosya (JSON)

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    fun exportJson(): String = JSONObject()
        .put("format", "openplayer-theme-1")
        .put("name", current.name)
        .put("background", hex(current.background))
        .put("surface", hex(current.surface))
        .put("accent", hex(accent))
        .put("textSecondary", hex(current.textSecondary))
        .toString(2)

    private fun color(o: JSONObject, key: String): Int? {
        val v = o.optString(key)
        if (!Regex("^#[0-9A-Fa-f]{6}$").matches(v)) return null
        return (0xFF000000.toInt()) or v.substring(1).toInt(16)
    }

    private fun parseCustom(json: String): AppTheme? = runCatching {
        if (json.isBlank()) return null
        val o = JSONObject(json)
        AppTheme(
            id = "custom",
            name = o.optString("name", "Özel").take(24).ifBlank { "Özel" },
            background = color(o, "background") ?: return null,
            surface = color(o, "surface") ?: return null,
            accent = color(o, "accent") ?: return null,
            textSecondary = color(o, "textSecondary") ?: 0xFF9AA3AD.toInt(),
        )
    }.getOrNull()

    /** Dışarıdan gelen tema dosyasını doğrular (yalnızca #RRGGBB renkleri, boyut sınırı) ve uygular. */
    fun importJson(context: Context, text: String): Boolean {
        if (text.length > 4096) return false
        val theme = parseCustom(text) ?: return false
        val s = AppSettings.get(context)
        s.customTheme = text
        s.themeId = "custom"
        s.accentOverride = 0
        reload()
        return theme.id == "custom"
    }
}
