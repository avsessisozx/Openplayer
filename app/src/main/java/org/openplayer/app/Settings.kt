package org.openplayer.app

import android.content.Context
import android.content.SharedPreferences

enum class DecoderMode(val label: String, val short: String) {
    HW("Donanım (HW)", "DONANIM"),
    HW_PLUS("Donanım+ (HW+)", "DONANIM+"),
    SW("Yazılım (SW)", "YAZILIM"),
}

enum class SortOrder(val label: String) {
    NAME_ASC("Ad (A-Z)"),
    NAME_DESC("Ad (Z-A)"),
    DATE_DESC("Tarih (yeniden eskiye)"),
    DATE_ASC("Tarih (eskiden yeniye)"),
    SIZE_DESC("Boyut (büyükten küçüğe)"),
    SIZE_ASC("Boyut (küçükten büyüğe)"),
    DURATION_DESC("Süre (uzundan kısaya)"),
    DURATION_ASC("Süre (kısadan uzuna)"),
}

enum class DoubleTapAction(val label: String) {
    PLAY_PAUSE("Oynat / duraklat"),
    SEEK("Kenarlarda sarma, ortada oynat/duraklat"),
    NONE("Hiçbir şey"),
}

enum class OrientationMode(val label: String) {
    AUTO("Videoya göre otomatik"),
    SENSOR("Sensör (serbest)"),
    LANDSCAPE("Yatay"),
    PORTRAIT("Dikey"),
}

enum class Shortcut(val id: String, val label: String, val icon: Int, val glyph: String? = null) {
    ROTATE("rotate", "Ekran döndürme", R.drawable.ic_screen_rotation),
    BACKGROUND("background", "Arka planda oynat", R.drawable.ic_headphones),
    MUTE("mute", "Sesi kapat", R.drawable.ic_volume_off),
    EQUALIZER("equalizer", "Ekolayzır", R.drawable.ic_tune),
    AB_REPEAT("ab", "A-B tekrar", 0, "A-B"),
    SPEED("speed", "Oynatma hızı", 0, "1X"),
    REPEAT("repeat", "Tekrarla", R.drawable.ic_repeat),
    SHUFFLE("shuffle", "Karıştır", R.drawable.ic_shuffle),
    SLEEP("sleep", "Uyku zamanlayıcısı", R.drawable.ic_timer),
    NIGHT("night", "Gece modu", R.drawable.ic_night),
    SCREENSHOT("screenshot", "Ekran görüntüsü", R.drawable.ic_camera),
    MIRROR("mirror", "Ayna modu", R.drawable.ic_flip),
    VFLIP("vflip", "Dikey çevirme", R.drawable.ic_flip);

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** Uygulama genelindeki tüm kalıcı ayarlar. */
class AppSettings private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }

    val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        prefs.getString(key, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private fun putEnum(key: String, value: Enum<*>) {
        prefs.edit().putString(key, value.name).apply()
    }

    // --- Oynatıcı ---
    var decoder: DecoderMode
        get() = enumOf("decoder", DecoderMode.HW_PLUS)
        set(v) = putEnum("decoder", v)

    var backgroundPlay: Boolean
        get() = prefs.getBoolean("background_play", false)
        set(v) = prefs.edit().putBoolean("background_play", v).apply()

    var autoPip: Boolean
        get() = prefs.getBoolean("auto_pip", true)
        set(v) = prefs.edit().putBoolean("auto_pip", v).apply()

    var resumePlayback: Boolean
        get() = prefs.getBoolean("resume_playback", true)
        set(v) = prefs.edit().putBoolean("resume_playback", v).apply()

    var orientation: OrientationMode
        get() = enumOf("orientation", OrientationMode.AUTO)
        set(v) = putEnum("orientation", v)

    var showRemainingTime: Boolean
        get() = prefs.getBoolean("show_remaining", true)
        set(v) = prefs.edit().putBoolean("show_remaining", v).apply()

    var showShortcuts: Boolean
        get() = prefs.getBoolean("show_shortcuts", true)
        set(v) = prefs.edit().putBoolean("show_shortcuts", v).apply()

    var controlsTimeoutSec: Int
        get() = prefs.getInt("controls_timeout", 4)
        set(v) = prefs.edit().putInt("controls_timeout", v).apply()

    var playbackSpeed: Float
        get() = prefs.getFloat("speed", 1f)
        set(v) = prefs.edit().putFloat("speed", v).apply()

    // --- Hareketler ---
    var doubleTapAction: DoubleTapAction
        get() = enumOf("double_tap", DoubleTapAction.SEEK)
        set(v) = putEnum("double_tap", v)

    var doubleTapSeekSec: Int
        get() = prefs.getInt("double_tap_seek", 10)
        set(v) = prefs.edit().putInt("double_tap_seek", v).apply()

    var swipeSeekSec: Int
        get() = prefs.getInt("swipe_seek", 90)
        set(v) = prefs.edit().putInt("swipe_seek", v).apply()

    var horizontalSeekEnabled: Boolean
        get() = prefs.getBoolean("horizontal_seek", true)
        set(v) = prefs.edit().putBoolean("horizontal_seek", v).apply()

    var pinchZoomEnabled: Boolean
        get() = prefs.getBoolean("pinch_zoom", true)
        set(v) = prefs.edit().putBoolean("pinch_zoom", v).apply()

    var subtitleGestures: Boolean
        get() = prefs.getBoolean("subtitle_gestures", true)
        set(v) = prefs.edit().putBoolean("subtitle_gestures", v).apply()

    // --- Kısayollar ---
    var shortcuts: List<Shortcut>
        get() {
            val raw = prefs.getString("shortcuts", null)
            if (raw == null) return Shortcut.entries
            val ids = raw.split(",").filter { it.isNotEmpty() }.toSet()
            return Shortcut.entries.filter { it.id in ids }
        }
        set(v) = prefs.edit().putString("shortcuts", v.joinToString(",") { it.id }).apply()

    // --- Altyazı ---
    var subtitleSizeSp: Float
        get() = prefs.getFloat("sub_size", 20f)
        set(v) = prefs.edit().putFloat("sub_size", v).apply()

    var subtitleColor: Int
        get() = prefs.getInt("sub_color", 0xFFFFFFFF.toInt())
        set(v) = prefs.edit().putInt("sub_color", v).apply()

    var subtitleBackground: Int
        get() = prefs.getInt("sub_bg", 0x00000000)
        set(v) = prefs.edit().putInt("sub_bg", v).apply()

    var subtitleOutline: Boolean
        get() = prefs.getBoolean("sub_outline", true)
        set(v) = prefs.edit().putBoolean("sub_outline", v).apply()

    var subtitleBold: Boolean
        get() = prefs.getBoolean("sub_bold", false)
        set(v) = prefs.edit().putBoolean("sub_bold", v).apply()

    var subtitleBottomFraction: Float
        get() = prefs.getFloat("sub_bottom", 0.08f)
        set(v) = prefs.edit().putFloat("sub_bottom", v).apply()

    /** "auto" ya da bir karakter kümesi adı (UTF-8, windows-1254 ...). */
    var subtitleCharset: String
        get() = prefs.getString("sub_charset", "auto") ?: "auto"
        set(v) = prefs.edit().putString("sub_charset", v).apply()

    /** Otomatik algılama UTF-8 çıkmazsa kullanılacak küme. */
    var subtitleFallbackCharset: String
        get() = prefs.getString("sub_fallback_charset", "windows-1254") ?: "windows-1254"
        set(v) = prefs.edit().putString("sub_fallback_charset", v).apply()

    var subtitleLanguages: String
        get() = prefs.getString("sub_langs", "tr,en") ?: "tr,en"
        set(v) = prefs.edit().putString("sub_langs", v).apply()

    var autoEnableSubtitle: Boolean
        get() = prefs.getBoolean("auto_subtitle", true)
        set(v) = prefs.edit().putBoolean("auto_subtitle", v).apply()

    // --- OpenSubtitles (kullanıcı kendi anahtarını girer) ---
    var osApiKey: String
        get() = prefs.getString("os_key", "") ?: ""
        set(v) = prefs.edit().putString("os_key", v.trim()).apply()

    var osUser: String
        get() = prefs.getString("os_user", "") ?: ""
        set(v) = prefs.edit().putString("os_user", v.trim()).apply()

    var osPass: String
        get() = prefs.getString("os_pass", "") ?: ""
        set(v) = prefs.edit().putString("os_pass", v).apply()

    // --- Ses ---
    var eqEnabled: Boolean
        get() = prefs.getBoolean("eq_enabled", false)
        set(v) = prefs.edit().putBoolean("eq_enabled", v).apply()

    var eqLevels: List<Int>
        get() = prefs.getString("eq_levels", "")?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        set(v) = prefs.edit().putString("eq_levels", v.joinToString(",")).apply()

    /** Virgülle ayrılmış dil kodları (örn. "tr,en"). Boş = sistem dili. */
    var audioLanguages: String
        get() = prefs.getString("audio_langs", "") ?: ""
        set(v) = prefs.edit().putString("audio_langs", v.trim()).apply()

    /** 100 = normal, 200 = en yüksek yazılımsal artırma. */
    var volumeBoostPercent: Int
        get() = prefs.getInt("boost", 100)
        set(v) = prefs.edit().putInt("boost", v).apply()

    // --- Liste ---
    var sortOrder: SortOrder
        get() = enumOf("sort", SortOrder.NAME_ASC)
        set(v) = putEnum("sort", v)

    var gridView: Boolean
        get() = prefs.getBoolean("grid_view", false)
        set(v) = prefs.edit().putBoolean("grid_view", v).apply()

    var newVideoDays: Int
        get() = prefs.getInt("new_days", 7)
        set(v) = prefs.edit().putInt("new_days", v).apply()

    var hiddenFolders: Set<Long>
        get() = prefs.getStringSet("hidden_folders", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("hidden_folders", v.map { it.toString() }.toSet()).apply()

    var showHiddenFolders: Boolean
        get() = prefs.getBoolean("show_hidden", false)
        set(v) = prefs.edit().putBoolean("show_hidden", v).apply()
}
