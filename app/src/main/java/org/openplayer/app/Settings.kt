package org.openplayer.app

import android.content.Context
import android.content.SharedPreferences

enum class DecoderMode(val label: String, val short: String) {
    HW(Tx.s(R.string.settings_001), "DONANIM"),
    HW_PLUS(Tx.s(R.string.settings_002), "DONANIM+"),
    SW(Tx.s(R.string.settings_003), "YAZILIM"),
}

enum class SortOrder(val label: String) {
    NAME_ASC("Ad (A-Z)"),
    NAME_DESC("Ad (Z-A)"),
    DATE_DESC(Tx.s(R.string.settings_030)),
    DATE_ASC(Tx.s(R.string.settings_031)),
    SIZE_DESC(Tx.s(R.string.settings_004)),
    SIZE_ASC(Tx.s(R.string.settings_005)),
    DURATION_DESC(Tx.s(R.string.musicrepos_001)),
    DURATION_ASC(Tx.s(R.string.settings_006)),
}

enum class ListMode(val label: String) {
    FOLDERS(Tx.s(R.string.mainactivi_015)),
    FILES(Tx.s(R.string.settings_032)),
}

enum class SortKey(val label: String) {
    TITLE(Tx.s(R.string.mediainfo_007)),
    DATE(Tx.s(R.string.listpanel_023)),
    LAST_PLAYED(Tx.s(R.string.listpanel_010)),
    STATUS(Tx.s(R.string.settings_033)),
    DURATION(Tx.s(R.string.listpanel_020)),
    SIZE(Tx.s(R.string.listpanel_022)),
    RESOLUTION(Tx.s(R.string.listpanel_011)),
    PATH(Tx.s(R.string.listpanel_021)),
    EXTENSION(Tx.s(R.string.settings_007)),
}

enum class DoubleTapAction(val label: String) {
    PLAY_PAUSE(Tx.s(R.string.settings_034)),
    SEEK(Tx.s(R.string.settings_035)),
    NONE(Tx.s(R.string.gesturecon_001)),
}

enum class PlaybackEngine(val label: String) {
    AUTO(Tx.s(R.string.settings_008)),
    EXO(Tx.s(R.string.settings_009)),
    VLC(Tx.s(R.string.settings_010)),
}

enum class OrientationMode(val label: String) {
    AUTO(Tx.s(R.string.settings_011)),
    SENSOR(Tx.s(R.string.settings_012)),
    LANDSCAPE(Tx.s(R.string.playeracti_063)),
    PORTRAIT(Tx.s(R.string.playeracti_064)),
}

enum class ResumeMode(val label: String) {
    YES(Tx.s(R.string.settings_013)),
    NO(Tx.s(R.string.settings_014)),
    ASK(Tx.s(R.string.settings_036)),
}

/** Ekrana tek dokunuşun işlevi. */
enum class TapAction(val label: String) {
    TOGGLE_UI(Tx.s(R.string.settings_015)),
    SHOW_THEN_PAUSE(Tx.s(R.string.settings_016)),
    BOTH(Tx.s(R.string.settings_017)),
    PLAY_PAUSE(Tx.s(R.string.settings_018)),
}

/** Kontroller gizliyken köşede sürekli görünen bilgi. */
enum class OsdInfo(val label: String) {
    NONE(Tx.s(R.string.audiofx_004)),
    CLOCK(Tx.s(R.string.settings_037)),
    ELAPSED(Tx.s(R.string.settings_019)),
    BOTH(Tx.s(R.string.settings_020)),
}

enum class StereoMode(val label: String) {
    STEREO("Stereo"),
    MONO(Tx.s(R.string.settings_021)),
    REVERSE(Tx.s(R.string.settings_022)),
}

enum class UiMode(val label: String, val scale: Float) {
    AUTO(Tx.s(R.string.settings_023), 1f),
    PHONE(Tx.s(R.string.settings_038), 1f),
    BIG_PHONE(Tx.s(R.string.settings_024), 1.12f),
    TABLET("Tablet", 1.25f),
    WIDE(Tx.s(R.string.settings_025), 1.4f),
}

enum class Shortcut(val id: String, val label: String, val icon: Int, val glyph: String? = null) {
    ROTATE("rotate", Tx.s(R.string.settings_026), R.drawable.ic_screen_rotation),
    BACKGROUND("background", Tx.s(R.string.settings_039), R.drawable.ic_headphones),
    MUTE("mute", Tx.s(R.string.settings_040), R.drawable.ic_volume_off),
    EQUALIZER("equalizer", Tx.s(R.string.musiccontr_002), R.drawable.ic_tune),
    AB_REPEAT("ab", "A-B tekrar", 0, "A-B"),
    SPEED("speed", Tx.s(R.string.gesturecon_003), 0, "1X"),
    REPEAT("repeat", Tx.s(R.string.settings_041), R.drawable.ic_repeat),
    SHUFFLE("shuffle", Tx.s(R.string.musicactiv_020), R.drawable.ic_shuffle),
    SLEEP("sleep", Tx.s(R.string.nowplaying_004), R.drawable.ic_timer),
    NIGHT("night", Tx.s(R.string.settings_042), R.drawable.ic_night),
    SCREENSHOT("screenshot", Tx.s(R.string.settings_027), R.drawable.ic_camera),
    MIRROR("mirror", Tx.s(R.string.settings_043), R.drawable.ic_flip),
    VFLIP("vflip", Tx.s(R.string.settings_028), R.drawable.ic_flip),
    SKIP_INTRO("skipintro", Tx.s(R.string.settings_029), 0, "»");

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

    /** Eski açık/kapalı ayardan türetilir; yeni ayar üç seçeneklidir (Evet / Hayır / Sor). */
    var resumeMode: ResumeMode
        get() = enumOf("resume_mode", if (prefs.getBoolean("resume_playback", true)) ResumeMode.YES else ResumeMode.NO)
        set(v) = putEnum("resume_mode", v)

    var resumePlayback: Boolean
        get() = resumeMode != ResumeMode.NO
        set(v) { resumeMode = if (v) ResumeMode.YES else ResumeMode.NO }

    /** Yalnız ilk açılan videoda kaldığı yerden devam et; listedeki sonrakiler baştan başlar. */
    var resumeOnlyFirst: Boolean
        get() = prefs.getBoolean("resume_only_first", false)
        set(v) = prefs.edit().putBoolean("resume_only_first", v).apply()

    /** Ekrana uzun basılı tutunca hızlı oynat (bırakınca normale döner). */
    var longPressSpeed: Boolean
        get() = prefs.getBoolean("long_press_speed", true)
        set(v) = prefs.edit().putBoolean("long_press_speed", v).apply()

    var longPressSpeedValue: Float
        get() = prefs.getFloat("long_press_speed_value", 2f)
        set(v) = prefs.edit().putFloat("long_press_speed_value", v.coerceIn(1.25f, 4f)).apply()

    var backToList: Boolean
        get() = prefs.getBoolean("back_to_list", false)
        set(v) = prefs.edit().putBoolean("back_to_list", v).apply()

    var doubleBackToExit: Boolean
        get() = prefs.getBoolean("double_back_exit", false)
        set(v) = prefs.edit().putBoolean("double_back_exit", v).apply()

    /** Açılamayan videoda hata göstermeden sonrakine geç. */
    var skipOnError: Boolean
        get() = prefs.getBoolean("skip_on_error", false)
        set(v) = prefs.edit().putBoolean("skip_on_error", v).apply()

    /** Başka uygulama ses çalınca/arama gelince dur (ses odağı). */
    var audioFocus: Boolean
        get() = prefs.getBoolean("audio_focus", true)
        set(v) = prefs.edit().putBoolean("audio_focus", v).apply()

    var pauseOnNoisy: Boolean
        get() = prefs.getBoolean("pause_on_noisy", true)
        set(v) = prefs.edit().putBoolean("pause_on_noisy", v).apply()

    /** Oynatma başlarken ve sarınca ses kısa sürede yükselir (ani ses patlaması olmaz). */
    var audioFade: Boolean
        get() = prefs.getBoolean("audio_fade", true)
        set(v) = prefs.edit().putBoolean("audio_fade", v).apply()

    var osdInfo: OsdInfo
        get() = enumOf("osd_info", OsdInfo.NONE)
        set(v) = putEnum("osd_info", v)

    /** Kontroller açıkken üst çubukta saat ve pil. */
    var topBarClock: Boolean
        get() = prefs.getBoolean("top_bar_clock", true)
        set(v) = prefs.edit().putBoolean("top_bar_clock", v).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(v) = prefs.edit().putBoolean("keep_screen_on", v).apply()

    var showControlsAtStart: Boolean
        get() = prefs.getBoolean("controls_at_start", false)
        set(v) = prefs.edit().putBoolean("controls_at_start", v).apply()

    var rememberBrightness: Boolean
        get() = prefs.getBoolean("remember_brightness", false)
        set(v) = prefs.edit().putBoolean("remember_brightness", v).apply()

    /** Son ayarlanan oynatıcı parlaklığı (0..1); -1 = sistem. */
    var savedBrightness: Float
        get() = prefs.getFloat("saved_brightness", -1f)
        set(v) = prefs.edit().putFloat("saved_brightness", v).apply()

    var showSeekButtons: Boolean
        get() = prefs.getBoolean("seek_buttons", false)
        set(v) = prefs.edit().putBoolean("seek_buttons", v).apply()

    var seekButtonSec: Int
        get() = prefs.getInt("seek_button_sec", 10)
        set(v) = prefs.edit().putInt("seek_button_sec", v.coerceIn(1, 600)).apply()

    var showPrevNext: Boolean
        get() = prefs.getBoolean("show_prev_next", true)
        set(v) = prefs.edit().putBoolean("show_prev_next", v).apply()

    var tapAction: TapAction
        get() = enumOf("tap_action", TapAction.TOGGLE_UI)
        set(v) = putEnum("tap_action", v)

    /** Ekran kilitlenince ekran yönü de sabitlenir. */
    var lockRotation: Boolean
        get() = prefs.getBoolean("lock_rotation", true)
        set(v) = prefs.edit().putBoolean("lock_rotation", v).apply()

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

    // --- 360° / VR
    var vrTwoFingerRotate: Boolean
        get() = prefs.getBoolean("vr_two_finger_rotate", true)
        set(v) = prefs.edit().putBoolean("vr_two_finger_rotate", v).apply()

    var vrHorizonLevel: Boolean
        get() = prefs.getBoolean("vr_horizon_level", true)
        set(v) = prefs.edit().putBoolean("vr_horizon_level", v).apply()

    var vrLockRotation: Boolean
        get() = prefs.getBoolean("vr_lock_rotation", true)
        set(v) = prefs.edit().putBoolean("vr_lock_rotation", v).apply()

    var vrAutoRotate: Boolean
        get() = prefs.getBoolean("vr_auto_rotate", false)
        set(v) = prefs.edit().putBoolean("vr_auto_rotate", v).apply()

    var vrTapStopsAutoRotate: Boolean
        get() = prefs.getBoolean("vr_tap_stop_auto", true)
        set(v) = prefs.edit().putBoolean("vr_tap_stop_auto", v).apply()

    var vrTapStopsSensor: Boolean
        get() = prefs.getBoolean("vr_tap_stop_sensor", false)
        set(v) = prefs.edit().putBoolean("vr_tap_stop_sensor", v).apply()

    var vrHoldWheel: Boolean
        get() = prefs.getBoolean("vr_hold_wheel", true)
        set(v) = prefs.edit().putBoolean("vr_hold_wheel", v).apply()

    var vrSensorAlt: Boolean
        get() = prefs.getBoolean("vr_sensor_alt", false)
        set(v) = prefs.edit().putBoolean("vr_sensor_alt", v).apply()

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

    /** Ana ekran klasör sırası: name_asc, name_desc, new_desc, new_asc, count_desc, count_asc, size_desc, size_asc. */
    var folderOrder: String
        get() = prefs.getString("folder_order", "name_asc") ?: "name_asc"
        set(v) = prefs.edit().putString("folder_order", v).apply()

    var folderGrid: Boolean
        get() = prefs.getBoolean("folder_grid", false)
        set(v) = prefs.edit().putBoolean("folder_grid", v).apply()

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
    var playbackEngine: PlaybackEngine
        get() = enumOf("playback_engine", PlaybackEngine.AUTO)
        set(v) = putEnum("playback_engine", v)

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
        get() = subtitleEdge != SubtitleLook.EDGE_NONE
        set(v) { subtitleEdge = if (v) SubtitleLook.EDGE_OUTLINE else SubtitleLook.EDGE_NONE }

    /** Kenar türü (SubtitleLook.EDGE_*); eski "kenar çizgisi" ayarından türetilir. */
    var subtitleEdge: Int
        get() = prefs.getInt("sub_edge", if (prefs.getBoolean("sub_outline", true)) SubtitleLook.EDGE_OUTLINE else SubtitleLook.EDGE_NONE)
        set(v) = prefs.edit().putInt("sub_edge", v).apply()

    var subtitleEdgeColor: Int
        get() = prefs.getInt("sub_edge_color", 0xFF000000.toInt())
        set(v) = prefs.edit().putInt("sub_edge_color", v).apply()

    /** "default", "sans", "serif", "mono", "condensed" ya da "file:<ad>" (içe aktarılmış yazı tipi). */
    var subtitleFont: String
        get() = prefs.getString("sub_font", "default") ?: "default"
        set(v) = prefs.edit().putString("sub_font", v).apply()

    /** 0 sol, 1 orta, 2 sağ. */
    var subtitleAlign: Int
        get() = prefs.getInt("sub_align", 1).coerceIn(0, 2)
        set(v) = prefs.edit().putInt("sub_align", v.coerceIn(0, 2)).apply()

    /** "/" ile başlayan satırları italik göster. */
    var subtitleItalicSlash: Boolean
        get() = prefs.getBoolean("sub_italic_slash", false)
        set(v) = prefs.edit().putBoolean("sub_italic_slash", v).apply()

    /** Her videoda başlangıç altyazı gecikmesi (ms). */
    var subtitleDefaultDelayMs: Long
        get() = prefs.getLong("sub_default_delay", 0L)
        set(v) = prefs.edit().putLong("sub_default_delay", v.coerceIn(-60_000L, 60_000L)).apply()

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

    // --- Altyazı çevirisi ---
    var trProvider: TrProvider
        get() = TrProvider.byId(prefs.getString("tr_provider", "") ?: "")
        set(v) = prefs.edit().putString("tr_provider", v.id).apply()

    var trTarget: String
        get() = prefs.getString("tr_target", "tr") ?: "tr"
        set(v) = prefs.edit().putString("tr_target", v).apply()

    /** "auto" ya da kaynak dil kodu. */
    var trSource: String
        get() = prefs.getString("tr_source", "en") ?: "en"
        set(v) = prefs.edit().putString("tr_source", v).apply()

    var trEmail: String
        get() = prefs.getString("tr_email", "") ?: ""
        set(v) = prefs.edit().putString("tr_email", v.trim()).apply()

    var trApiUrl: String
        get() = prefs.getString("tr_url", "") ?: ""
        set(v) = prefs.edit().putString("tr_url", v.trim()).apply()

    var trApiKey: String
        get() = SecretBox.decrypt(prefs.getString("tr_key", "") ?: "")
        set(v) = prefs.edit().putString("tr_key", SecretBox.encrypt(v.trim())).apply()

    var trModel: String
        get() = prefs.getString("tr_model", "") ?: ""
        set(v) = prefs.edit().putString("tr_model", v.trim()).apply()

    fun translateConfig(): TrConfig = TrConfig(trProvider, trTarget, trSource, trEmail, trApiUrl, trApiKey, trModel)

    // --- Liste ve genel ---
    /** Videoyu kalıcı silerken yanındaki altyazı dosyalarını da sil (çöp kutusunda silinmez). */
    var deleteSubtitlesToo: Boolean
        get() = prefs.getBoolean("delete_subs", true)
        set(v) = prefs.edit().putBoolean("delete_subs", v).apply()

    /** Boşsa varsayılan "OpenPlayer/1.0". */
    var httpUserAgent: String
        get() = prefs.getString("http_ua", "") ?: ""
        set(v) = prefs.edit().putString("http_ua", v.trim().take(200)).apply()

    /** Ana menüde "Çıkış" öğesi. */
    var exitButton: Boolean
        get() = prefs.getBoolean("exit_button", false)
        set(v) = prefs.edit().putBoolean("exit_button", v).apply()

    /** Pencere içinde pencere düğmeleri: false = önceki/sonraki, true = ±10 sn sar. */
    var pipSeekButtons: Boolean
        get() = prefs.getBoolean("pip_seek", false)
        set(v) = prefs.edit().putBoolean("pip_seek", v).apply()

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

    /** Cihazdan bağımsız grafik EQ açık mı. */
    var graphicEqEnabled: Boolean
        get() = prefs.getBoolean("geq_enabled", false)
        set(v) = prefs.edit().putBoolean("geq_enabled", v).apply()

    /** 10 bant kazanç (dB), virgülle ayrılmış. Boş = tümü 0. */
    var graphicEqBands: List<Int>
        get() = prefs.getString("geq_bands", "")?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        set(v) = prefs.edit().putString("geq_bands", v.joinToString(",")).apply()

    /** Parametrik kip: bant frekansı ve Q'su da ayarlanabilir (kapalıysa sabit grafik bantlar). */
    var graphicEqParametric: Boolean
        get() = prefs.getBoolean("geq_param", false)
        set(v) = prefs.edit().putBoolean("geq_param", v).apply()

    /** Parametrik bant frekansları (Hz), virgülle. Boş = varsayılan [AudioEq.FREQS]. */
    var graphicEqFreqs: List<Int>
        get() = prefs.getString("geq_freqs", "")?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        set(v) = prefs.edit().putString("geq_freqs", v.joinToString(",")).apply()

    /** Parametrik bant Q'ları ×100 (tamsayı), virgülle. Boş = varsayılan. */
    var graphicEqQs: List<Int>
        get() = prefs.getString("geq_qs", "")?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        set(v) = prefs.edit().putString("geq_qs", v.joinToString(",")).apply()

    /** Virgülle ayrılmış dil kodları (örn. "tr,en"). Boş = sistem dili. */
    var audioLanguages: String
        get() = prefs.getString("audio_langs", "") ?: ""
        set(v) = prefs.edit().putString("audio_langs", v.trim()).apply()

    var bassStrength: Int
        get() = prefs.getInt("bass_strength", 0)
        set(v) = prefs.edit().putInt("bass_strength", v.coerceIn(0, 1000)).apply()

    var virtualizerStrength: Int
        get() = prefs.getInt("virtualizer_strength", 0)
        set(v) = prefs.edit().putInt("virtualizer_strength", v.coerceIn(0, 1000)).apply()

    /** -100 (sol) .. 100 (sağ). */
    var audioBalance: Int
        get() = prefs.getInt("audio_balance", 0)
        set(v) = prefs.edit().putInt("audio_balance", v.coerceIn(-100, 100)).apply()

    var monoMix: Boolean
        get() = prefs.getBoolean("mono_mix", false)
        set(v) = prefs.edit().putBoolean("mono_mix", v).apply()

    var reverseStereo: Boolean
        get() = prefs.getBoolean("reverse_stereo", false)
        set(v) = prefs.edit().putBoolean("reverse_stereo", v).apply()

    var stereoMode: StereoMode
        get() = when {
            monoMix -> StereoMode.MONO
            reverseStereo -> StereoMode.REVERSE
            else -> StereoMode.STEREO
        }
        set(v) {
            monoMix = v == StereoMode.MONO
            reverseStereo = v == StereoMode.REVERSE
        }

    /** 0 = kapalı; 1..6 = PresetReverb hazır ayarları (küçük oda .. sahne). */
    var reverbPreset: Int
        get() = prefs.getInt("reverb_preset", 0).coerceIn(0, 6)
        set(v) = prefs.edit().putInt("reverb_preset", v.coerceIn(0, 6)).apply()

    /** 0 kapalı, 1 parça, 2 albüm. Yalnızca ses kısma uygulanır. */
    var replayGainMode: Int
        get() = prefs.getInt("replay_gain", 0)
        set(v) = prefs.edit().putInt("replay_gain", v.coerceIn(0, 2)).apply()

    /** Şarkı başı/sonu ses geçişi (sn); 0 kapalı. */
    var crossfadeSec: Int
        get() = prefs.getInt("crossfade_sec", 0)
        set(v) = prefs.edit().putInt("crossfade_sec", v.coerceIn(0, 12)).apply()

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

    /** Klasör içinde en son oynatılan videoyu vurgula (kapalıysa yalnız genel son video). */
    var markLastInFolder: Boolean
        get() = prefs.getBoolean("mark_last_in_folder", true)
        set(v) = prefs.edit().putBoolean("mark_last_in_folder", v).apply()

    var scrollToLast: Boolean
        get() = prefs.getBoolean("scroll_to_last", false)
        set(v) = prefs.edit().putBoolean("scroll_to_last", v).apply()

    var showFab: Boolean
        get() = prefs.getBoolean("show_fab", true)
        set(v) = prefs.edit().putBoolean("show_fab", v).apply()

    var swipeRefresh: Boolean
        get() = prefs.getBoolean("swipe_refresh", true)
        set(v) = prefs.edit().putBoolean("swipe_refresh", v).apply()

    /** Tüm ayarları varsayılana döndürür; OpenSubtitles ve çeviri hesap bilgileri korunur. */
    fun resetAll(context: Context) {
        val keep = listOf("os_key", "os_user", "os_pass", "tr_provider", "tr_url", "tr_key", "tr_model", "tr_email").associateWith { prefs.getString(it, null) }
        val editor = prefs.edit().clear()
        for ((k, v) in keep) if (v != null) editor.putString(k, v)
        editor.apply()
        context.getSharedPreferences("gestures", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
