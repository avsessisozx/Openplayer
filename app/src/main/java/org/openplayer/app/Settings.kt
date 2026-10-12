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

enum class ListMode(val label: String) {
    FOLDERS("Klasörler"),
    FILES("Dosyalar"),
}

enum class SortKey(val label: String) {
    TITLE("Başlık"),
    DATE("Tarih"),
    LAST_PLAYED("Oynama zamanı"),
    STATUS("Durum"),
    DURATION("Uzunluk"),
    SIZE("Boyut"),
    RESOLUTION("Çözünürlük"),
    PATH("Adres"),
    EXTENSION("Dosya türü"),
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

enum class UiMode(val label: String, val scale: Float) {
    AUTO("Otomatik (ekrana göre)", 1f),
    PHONE("Telefon", 1f),
    BIG_PHONE("Büyük telefon", 1.12f),
    TABLET("Tablet", 1.25f),
    WIDE("Geniş ekran (PC, TV, emülatör)", 1.4f),
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
    VFLIP("vflip", "Dikey çevirme", R.drawable.ic_flip),
    SKIP_INTRO("skipintro", "İntro atla", 0, "»");

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
        get() = prefs.getBoolean("show_shortcuts", false)
        set(v) = prefs.edit().putBoolean("show_shortcuts", v).apply()

    /** Ses/parlaklık/sarma göstergesinin bırakıldıktan sonra kaybolma süresi (milisaniye). */
    // --- Tema ---
    var themeId: String
        get() = prefs.getString("theme_id", "dark") ?: "dark"
        set(v) = prefs.edit().putString("theme_id", v).apply()

    /** 0 = temanın kendi vurgu rengi. */
    var accentOverride: Int
        get() = prefs.getInt("accent_override", 0)
        set(v) = prefs.edit().putInt("accent_override", v).apply()

    var customTheme: String
        get() = prefs.getString("custom_theme", "") ?: ""
        set(v) = prefs.edit().putString("custom_theme", v.take(4096)).apply()

    /** Menü panellerinin opaklığı (yüzde). */
    var panelOpacityPercent: Int
        get() = prefs.getInt("panel_opacity", 80)
        set(v) = prefs.edit().putInt("panel_opacity", v.coerceIn(30, 100)).apply()

    var buttonShape: ButtonShape
        get() = enumOf("button_shape", ButtonShape.CIRCLE)
        set(v) = putEnum("button_shape", v)

    var uiMode: UiMode
        get() = enumOf("ui_mode", UiMode.AUTO)
        set(v) = putEnum("ui_mode", v)

    var naturalSort: Boolean
        get() = prefs.getBoolean("natural_sort", true)
        set(v) = prefs.edit().putBoolean("natural_sort", v).apply()

    var perVideoMemory: Boolean
        get() = prefs.getBoolean("per_video_memory", true)
        set(v) = prefs.edit().putBoolean("per_video_memory", v).apply()

    var musicMode: Boolean
        get() = prefs.getBoolean("music_mode", false)
        set(v) = prefs.edit().putBoolean("music_mode", v).apply()

    /** Müzik açılışında Kitaplık ekranı gösterilir (kapalıysa Şarkılar). */
    var musicStartHome: Boolean
        get() = prefs.getBoolean("music_start_home", true)
        set(v) = prefs.edit().putBoolean("music_start_home", v).apply()

    /** Müzik listelerinde simge/yazı ölçeği (yüzde). */
    var musicScale: Int
        get() = prefs.getInt("music_scale", 100)
        set(v) = prefs.edit().putInt("music_scale", v.coerceIn(30, 400)).apply()

    var musicSort: MusicSort
        get() = enumOf("music_sort", MusicSort.TITLE)
        set(v) = putEnum("music_sort", v)

    var musicShuffle: Boolean
        get() = prefs.getBoolean("music_shuffle", false)
        set(v) = prefs.edit().putBoolean("music_shuffle", v).apply()

    /** 0 = kapalı, 1 = tek şarkı, 2 = tüm liste (Player.REPEAT_MODE_*). */
    var musicRepeat: Int
        get() = prefs.getInt("music_repeat", 0).coerceIn(0, 2)
        set(v) = prefs.edit().putInt("music_repeat", v.coerceIn(0, 2)).apply()

    var useTrash: Boolean
        get() = prefs.getBoolean("use_trash", true)
        set(v) = prefs.edit().putBoolean("use_trash", v).apply()

    var show360: Boolean
        get() = prefs.getBoolean("show_360", true)
        set(v) = prefs.edit().putBoolean("show_360", v).apply()

    /** Liste küçük resim ölçeği (yüzde; 100 = varsayılan). */
    var listScale: Int
        get() = prefs.getInt("list_scale", 100)
        set(v) = prefs.edit().putInt("list_scale", v.coerceIn(25, 500)).apply()

    var gridScale: Int
        get() = prefs.getInt("grid_scale", 100)
        set(v) = prefs.edit().putInt("grid_scale", v.coerceIn(25, 500)).apply()

    var folderScale: Int
        get() = prefs.getInt("folder_scale", 100)
        set(v) = prefs.edit().putInt("folder_scale", v.coerceIn(25, 500)).apply()

    var listTextScale: Int
        get() = prefs.getInt("list_text_scale", 100)
        set(v) = prefs.edit().putInt("list_text_scale", v.coerceIn(70, 160)).apply()

    var skipSilence: Boolean
        get() = prefs.getBoolean("skip_silence", false)
        set(v) = prefs.edit().putBoolean("skip_silence", v).apply()

    var incognito: Boolean
        get() = prefs.getBoolean("incognito", false)
        set(v) = prefs.edit().putBoolean("incognito", v).apply()

    var skipIntroSec: Int
        get() = prefs.getInt("skip_intro_sec", 85)
        set(v) = prefs.edit().putInt("skip_intro_sec", v.coerceIn(5, 600)).apply()

    var edgeGuardDp: Int
        get() = prefs.getInt("edge_guard_dp", 0)
        set(v) = prefs.edit().putInt("edge_guard_dp", v.coerceIn(0, 80)).apply()

    var hudHideMs: Int
        get() = prefs.getInt("hud_hide_ms", 500)
        set(v) = prefs.edit().putInt("hud_hide_ms", v.coerceIn(0, 10_000)).apply()

    /** Kısayol düğmelerinin boyutu (yüzde). */
    var shortcutSizePercent: Int
        get() = prefs.getInt("shortcut_size", 80)
        set(v) = prefs.edit().putInt("shortcut_size", v.coerceIn(50, 150)).apply()

    /** Kısayol düğmelerinin arka plan opaklığı (yüzde; düşük = daha şeffaf). */
    var shortcutOpacityPercent: Int
        get() = prefs.getInt("shortcut_opacity", 35)
        set(v) = prefs.edit().putInt("shortcut_opacity", v.coerceIn(0, 100)).apply()

    /** Üst ve alt kontrol şeritlerinin karartma yoğunluğu (yüzde). */
    var scrimPercent: Int
        get() = prefs.getInt("scrim_percent", 30)
        set(v) = prefs.edit().putInt("scrim_percent", v.coerceIn(0, 100)).apply()

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

    /** Açılamayan videoda çözücüler sırayla otomatik denenir (HW → HW+ → SW). */
    var autoDecoderFallback: Boolean
        get() = prefs.getBoolean("auto_decoder_fallback", true)
        set(v) = prefs.edit().putBoolean("auto_decoder_fallback", v).apply()

    /** Sarmayı bırakınca tam kareye git (kapalıysa en yakın anahtar kare: daha akıcı, birkaç sn sapabilir). */
    var exactSeekOnRelease: Boolean
        get() = prefs.getBoolean("exact_seek_release", true)
        set(v) = prefs.edit().putBoolean("exact_seek_release", v).apply()

    /** Sararken görüntü canlı akar (ileri-geri sararken önizleme). */
    var liveScrub: Boolean
        get() = prefs.getBoolean("live_scrub", true)
        set(v) = prefs.edit().putBoolean("live_scrub", v).apply()

    /** Çimdik bırakılınca yakınlaştırmayı %100/125/150/200/300/500 adımlarına oturtur. */
    var quickZoom: Boolean
        get() = prefs.getBoolean("quick_zoom", true)
        set(v) = prefs.edit().putBoolean("quick_zoom", v).apply()

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
        get() = SecretBox.decrypt(prefs.getString("os_key", "") ?: "")
        set(v) = prefs.edit().putString("os_key", SecretBox.encrypt(v.trim())).apply()

    var osUser: String
        get() = prefs.getString("os_user", "") ?: ""
        set(v) = prefs.edit().putString("os_user", v.trim()).apply()

    var osPass: String
        get() = SecretBox.decrypt(prefs.getString("os_pass", "") ?: "")
        set(v) = prefs.edit().putString("os_pass", SecretBox.encrypt(v)).apply()

    // --- Ses ---
    /** Ses gecikmesi (ms). + sesi geciktirir, − öne alır. */
    var audioDelayMs: Long
        get() = prefs.getLong("audio_delay_ms", 0L)
        set(v) = prefs.edit().putLong("audio_delay_ms", v.coerceIn(-5000L, 5000L)).apply()

    /** Bluetooth çıkışta ek ses gecikmesi (ms). */
    var bluetoothDelayMs: Long
        get() = prefs.getLong("bt_delay_ms", 0L)
        set(v) = prefs.edit().putLong("bt_delay_ms", v.coerceIn(-5000L, 5000L)).apply()

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

    var listMode: ListMode
        get() = enumOf("list_mode", ListMode.FOLDERS)
        set(v) = putEnum("list_mode", v)

    var sortKey: SortKey
        get() = enumOf("sort_key", SortKey.TITLE)
        set(v) = putEnum("sort_key", v)

    var sortAscending: Boolean
        get() = prefs.getBoolean("sort_asc", true)
        set(v) = prefs.edit().putBoolean("sort_asc", v).apply()

    /** Liste satırında gösterilecek alanlar: thumb, duration, ext, played, resolution, path, size, date. */
    var listFields: Set<String>
        get() = prefs.getStringSet("list_fields", null) ?: setOf("thumb", "duration")
        set(v) = prefs.edit().putStringSet("list_fields", v).apply()

    var durationOnThumb: Boolean
        get() = prefs.getBoolean("duration_on_thumb", true)
        set(v) = prefs.edit().putBoolean("duration_on_thumb", v).apply()

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
