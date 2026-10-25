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
    CIRCLE(Tx.s(R.string.thememanag_007)),
    ROUNDED(Tx.s(R.string.thememanag_008)),
    SQUARE(Tx.s(R.string.thememanag_009)),
}

/** Seçili temayı tutar; arayüz renkleri [UiColors] üzerinden buradan okunur. */
object ThemeManager {

    val presets = listOf(
        AppTheme("dark", Tx.s(R.string.thememanag_010), 0xFF000000.toInt(), 0xFF1E242B.toInt(), 0xFF2F8CFF.toInt()),
        AppTheme("amoled", "AMOLED", 0xFF000000.toInt(), 0xFF0B0B0B.toInt(), 0xFF00E5FF.toInt()),
        AppTheme("ocean", Tx.s(R.string.thememanag_011), 0xFF06141F.toInt(), 0xFF0E2A3D.toInt(), 0xFF29B6F6.toInt()),
        AppTheme("sunset", Tx.s(R.string.thememanag_001), 0xFF1A0E10.toInt(), 0xFF2E1A1E.toInt(), 0xFFFF7043.toInt()),
        AppTheme("forest", Tx.s(R.string.thememanag_012), 0xFF0A140E.toInt(), 0xFF16281C.toInt(), 0xFF66BB6A.toInt()),
        AppTheme("purple", Tx.s(R.string.thememanag_013), 0xFF120A1C.toInt(), 0xFF241638.toInt(), 0xFFAB47BC.toInt()),
        AppTheme("rose", Tx.s(R.string.thememanag_002), 0xFF1A0A12.toInt(), 0xFF30152A.toInt(), 0xFFEC407A.toInt()),
        AppTheme("gold", Tx.s(R.string.thememanag_003), 0xFF14110A.toInt(), 0xFF2A2412.toInt(), 0xFFFFC107.toInt()),
        AppTheme("mono", Tx.s(R.string.thememanag_014), 0xFF0D0D0D.toInt(), 0xFF222222.toInt(), 0xFFB0BEC5.toInt()),
    )

    val accentChoices = listOf(
        Tx.s(R.string.thememanag_004) to 0,
        Tx.s(R.string.settingsac_284) to 0xFF2F8CFF.toInt(),
        Tx.s(R.string.thememanag_015) to 0xFF00BCD4.toInt(),
        Tx.s(R.string.playermenu_024) to 0xFF4CAF50.toInt(),
        Tx.s(R.string.thememanag_005) to 0xFFC0CA33.toInt(),
        Tx.s(R.string.playermenu_025) to 0xFFFFC107.toInt(),
        Tx.s(R.string.settingsac_281) to 0xFFFF7043.toInt(),
        Tx.s(R.string.settingsac_161) to 0xFFF44336.toInt(),
        Tx.s(R.string.settingsac_280) to 0xFFEC407A.toInt(),
        Tx.s(R.string.thememanag_013) to 0xFFAB47BC.toInt(),
        Tx.s(R.string.thememanag_006) to 0xFF5C6BC0.toInt(),
        Tx.s(R.string.playermenu_130) to 0xFFFFFFFF.toInt(),
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
            name = o.optString("name", Tx.s(R.string.settingsac_156)).take(24).ifBlank { Tx.s(R.string.settingsac_156) },
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

/** Arayüz modu: seçime (veya ekran boyutuna) göre arayüz yoğunluğunu ölçekler. */
object UiScale {
    fun wrap(base: android.content.Context): android.content.Context {
        val cfg0 = base.resources.configuration
        val mode = runCatching {
            base.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getString("ui_mode", null)
        }.getOrNull()?.let { n -> UiMode.entries.firstOrNull { it.name == n } } ?: UiMode.AUTO
        val factor = if (mode == UiMode.AUTO) {
            val sw = cfg0.smallestScreenWidthDp
            when {
                sw >= 840 -> UiMode.WIDE.scale
                sw >= 600 -> UiMode.TABLET.scale
                else -> 1f
            }
        } else mode.scale
        if (factor == 1f) return base
        val cfg = android.content.res.Configuration(cfg0)
        cfg.densityDpi = (cfg0.densityDpi * factor).toInt()
        return base.createConfigurationContext(cfg)
    }
}
