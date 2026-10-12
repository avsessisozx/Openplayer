package org.openplayer.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Ayarlar: ana sayfada kategoriler, her kategori kendi sayfasında. */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAGE = "org.openplayer.app.SETTINGS_PAGE"
    }

    private enum class Page(val title: String, val summary: String, val icon: Int) {
        PLAYER("Oynatıcı", "Kontroller, ekran yönü, arka planda oynatma, PiP", R.drawable.ic_play),
        GESTURES("Hareketler", "Ekran bölgeleri, sarma, çift dokunma, yakınlaştırma", R.drawable.ic_aspect),
        VIDEO("Video ve çözücü", "HW / HW+ / SW çözücü seçimi", R.drawable.ic_tune),
        AUDIO("Ses", "Ses artırma, ekolayzır, ses dili", R.drawable.ic_volume_up),
        SUBTITLE("Altyazı", "Görünüm, karakter kodlaması, diller", R.drawable.ic_subtitles),
        ONLINE("Çevrimiçi altyazı", "OpenSubtitles hesabı ve API anahtarı", R.drawable.ic_cloud_download),
        LIST("Liste ve dosyalar", "Yeni etiketi, gizli klasörler, dosya erişimi", R.drawable.ic_view_list),
    }

    private lateinit var s: AppSettings
    private lateinit var content: LinearLayout
    private var page: Page? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = AppSettings.get(this)
        page = intent.getStringExtra(EXTRA_PAGE)?.let { name -> Page.entries.firstOrNull { it.name == name } }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(32))
        }
        setContentView(
            ScrollView(this).apply {
                fitsSystemWindows = true
                setBackgroundColor(Color.BLACK)
                addView(content)
            }
        )
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(titleBar(page?.title ?: "Ayarlar"))
        when (page) {
            null -> home()
            Page.PLAYER -> playerPage()
            Page.GESTURES -> gesturesPage()
            Page.VIDEO -> videoPage()
            Page.AUDIO -> audioPage()
            Page.SUBTITLE -> subtitlePage()
            Page.ONLINE -> onlinePage()
            Page.LIST -> listPage()
        }
    }

    private fun home() {
        Page.entries.forEach { p ->
            categoryRow(p.icon, p.title, p.summary) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, p.name))
            }
        }
        section("Hakkında")
        action("OpenPlayer ${versionName()}", "AGPLv3 lisanslı açık kaynak yazılım. Kaynak kodu için dokunun.") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/avsessisozx/Openplayer")))
        }
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""

    private fun playerPage() {
        section("Oynatma")
        toggle("Arka planda oynat", "Ana ekrana çıkınca ses çalmaya devam eder", s.backgroundPlay) { s.backgroundPlay = it }
        toggle("Otomatik pencere içinde pencere (PiP)", "Ana ekrana çıkınca küçük pencerede devam eder", s.autoPip) { s.autoPip = it }
        toggle("Kaldığı yerden devam et", null, s.resumePlayback) { s.resumePlayback = it }
        section("Görünüm")
        toggle("Kalan süreyi göster", "Kapalıyken toplam süre gösterilir", s.showRemainingTime) { s.showRemainingTime = it }
        toggle("Kısayol satırını göster", "Üst taraftaki yuvarlak düğmeler. Hangilerinin görüneceğini oynatıcıdaki \"Öğeleri özelleştir\" ile seçin.", s.showShortcuts) { s.showShortcuts = it }
        choice("Ekran yönü", s.orientation.label, OrientationMode.entries.map { it.label }, s.orientation.ordinal) {
            s.orientation = OrientationMode.entries[it]
        }
        val timeouts = listOf(2, 3, 4, 5, 8, 10)
        choice("Kontrollerin kapanma süresi", "${s.controlsTimeoutSec} sn", timeouts.map { "$it sn" }, timeouts.indexOf(s.controlsTimeoutSec)) {
            s.controlsTimeoutSec = timeouts[it]
        }
    }

    private fun gesturesPage() {
        section("Dikey kaydırma bölgeleri")
        val prefs = GesturePrefs(this)
        for (zone in GesturePrefs.zones) {
            choice("${GesturePrefs.zoneLabel(zone)} bölge", prefs.get(zone).label,
                GestureAction.entries.map { it.label }, prefs.get(zone).ordinal) {
                prefs.set(zone, GestureAction.entries[it])
            }
        }
        section("Sarma")
        toggle("Yatay kaydırma ile sarma", null, s.horizontalSeekEnabled) { s.horizontalSeekEnabled = it }
        val swipeOptions = listOf(30, 60, 90, 120, 180, 300)
        choice("Ekranı baştan sona kaydırınca sarma", "${s.swipeSeekSec} sn", swipeOptions.map { "$it sn" }, swipeOptions.indexOf(s.swipeSeekSec)) {
            s.swipeSeekSec = swipeOptions[it]
        }
        section("Dokunma")
        choice("Çift dokunma", s.doubleTapAction.label, DoubleTapAction.entries.map { it.label }, s.doubleTapAction.ordinal) {
            s.doubleTapAction = DoubleTapAction.entries[it]
        }
        val tapOptions = listOf(5, 10, 15, 20, 30)
        choice("Çift dokunma ile sarma süresi", "${s.doubleTapSeekSec} sn", tapOptions.map { "$it sn" }, tapOptions.indexOf(s.doubleTapSeekSec)) {
            s.doubleTapSeekSec = tapOptions[it]
        }
        section("Yakınlaştırma ve altyazı")
        toggle("Çimdikle yakınlaştırma ve kaydırma", null, s.pinchZoomEnabled) { s.pinchZoomEnabled = it }
        toggle("Altyazı hareketleri", "Alt şeritte: yatay = önceki/sonraki satır, dikey = konum, çimdik = boyut", s.subtitleGestures) { s.subtitleGestures = it }
    }

    private fun videoPage() {
        section("Çözücü")
        choice("Varsayılan çözücü", s.decoder.label, DecoderMode.entries.map { it.label }, DecoderMode.entries.indexOf(s.decoder)) {
            s.decoder = DecoderMode.entries[it]
        }
        note("HW: sistemin donanım çözücüsü, en az pil harcar. HW+: donanımın desteklemediği biçimlerde FFmpeg kullanır. SW: önce FFmpeg ile işlemci çözmesi. Oynatma sırasında üstteki etiketten de değiştirilebilir.")
    }

    private fun audioPage() {
        section("Ses seviyesi")
        val boosts = listOf(100, 125, 150, 175, 200)
        choice("Ses artırma", "%${s.volumeBoostPercent}", boosts.map { "%$it" }, boosts.indexOf(s.volumeBoostPercent)) {
            s.volumeBoostPercent = boosts[it]
        }
        note("Yazılımsal artırmadır; yüksek değerlerde bozulma olabilir. Oynatıcıdaki ses menüsünden de ayarlanır.")
        section("Ekolayzır")
        toggle("Ekolayzırı açık başlat", "Bantlar oynatıcıdaki ekolayzır düğmesinden ayarlanır", s.eqEnabled) { s.eqEnabled = it }
        section("Ses parçası")
        text("Tercih edilen ses dilleri", s.audioLanguages.ifEmpty { "Videonun varsayılanı" }, "Virgülle ayırın, örn. tr,en") { s.audioLanguages = it }
    }

    private fun subtitlePage() {
        section("Seçim")
        toggle("Altyazıyı otomatik seç", "Videonun yanındaki dosya veya tercih ettiğiniz dildeki gömülü parça", s.autoEnableSubtitle) { s.autoEnableSubtitle = it }
        text("Tercih edilen altyazı dilleri", s.subtitleLanguages, "Virgülle ayırın, örn. tr,en") { s.subtitleLanguages = it.ifBlank { "tr,en" } }
        section("Görünüm")
        val sizes = listOf(14, 16, 18, 20, 24, 28, 32, 36)
        choice("Yazı boyutu", "${s.subtitleSizeSp.toInt()}", sizes.map { "$it" }, sizes.indexOf(s.subtitleSizeSp.toInt())) {
            s.subtitleSizeSp = sizes[it].toFloat()
        }
        val colors = listOf("Beyaz" to 0xFFFFFFFF.toInt(), "Sarı" to 0xFFFFE600.toInt(), "Yeşil" to 0xFF7CFF7C.toInt(), "Camgöbeği" to 0xFF66E5FF.toInt())
        choice("Yazı rengi", colors.firstOrNull { it.second == s.subtitleColor }?.first ?: "Özel", colors.map { it.first },
            colors.indexOfFirst { it.second == s.subtitleColor }) { s.subtitleColor = colors[it].second }
        val bgs = listOf("Şeffaf" to 0x00000000, "Yarı saydam" to 0x99000000.toInt(), "Siyah" to 0xFF000000.toInt())
        choice("Arka plan", bgs.firstOrNull { it.second == s.subtitleBackground }?.first ?: "Özel", bgs.map { it.first },
            bgs.indexOfFirst { it.second == s.subtitleBackground }) { s.subtitleBackground = bgs[it].second }
        toggle("Kenar çizgisi", null, s.subtitleOutline) { s.subtitleOutline = it }
        toggle("Kalın yazı", null, s.subtitleBold) { s.subtitleBold = it }
        section("Karakter kodlaması")
        val charsets = listOf("auto", "UTF-8", "windows-1254", "ISO-8859-9", "windows-1252", "windows-1250", "windows-1251")
        choice("Karakter kodlaması", s.subtitleCharset, charsets, charsets.indexOf(s.subtitleCharset)) {
            s.subtitleCharset = charsets[it]
        }
        val fallbacks = charsets.drop(2)
        choice("Otomatik algılama yedeği", s.subtitleFallbackCharset, fallbacks, fallbacks.indexOf(s.subtitleFallbackCharset)) {
            s.subtitleFallbackCharset = fallbacks[it]
        }
        note("Altyazı gecikmesi oynatma sırasında altyazı menüsünden ayarlanır.")
    }

    private fun onlinePage() {
        section("OpenSubtitles hesabı")
        text("API anahtarı", if (s.osApiKey.isEmpty()) "Girilmedi" else "••••••••", "opensubtitles.com hesabınızdan alın") { s.osApiKey = it }
        text("Kullanıcı adı", s.osUser.ifEmpty { "İsteğe bağlı" }, "İndirme için gerekebilir") { s.osUser = it }
        text("Parola", if (s.osPass.isEmpty()) "İsteğe bağlı" else "••••••••", "Parola", password = true) { s.osPass = it }
        note("Bilgiler cihazda şifrelenerek saklanır. Anahtarı opensubtitles.com/consumers adresinden ücretsiz alabilirsiniz.")
    }

    private fun listPage() {
        section("Liste")
        val days = listOf(1, 3, 7, 14, 30)
        choice("\"Yeni\" etiketi süresi", "${s.newVideoDays} gün", days.map { "$it gün" }, days.indexOf(s.newVideoDays)) {
            s.newVideoDays = days[it]
        }
        toggle("Gizli klasörleri göster", "Klasöre uzun basarak gizleyebilirsiniz", s.showHiddenFolders) { s.showHiddenFolders = it }
        section("Dosya erişimi")
        action("Tüm dosyalara erişim izni", "Videonun yanındaki altyazıları otomatik bulmak için gerekir") { openAllFilesAccess() }
    }

    private fun categoryRow(icon: Int, title: String, summary: String, onClick: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        box.addView(iconView(icon, 44, 10).apply { setColorFilter(UiColors.accent) })
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextColor(UiColors.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            })
            addView(TextView(context).apply {
                text = summary
                setTextColor(UiColors.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        box.addView(iconView(R.drawable.ic_chevron, 32, 4).apply { setColorFilter(UiColors.textSecondary) })
        content.addView(box)
    }

    // ------------------------------------------------------------ satır türleri

    private fun titleBar(title: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(12), dp(20), dp(8))
        addView(iconView(R.drawable.ic_back).apply { setOnClickListener { finish() } })
        addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    }

    private fun section(title: String) {
        content.addView(TextView(this).apply {
            text = title
            setTextColor(UiColors.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(24), dp(20), dp(6))
        })
    }

    private fun note(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            setTextColor(UiColors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(20), dp(2), dp(20), dp(6))
        })
    }

    private fun row(title: String, subtitle: String?, onClick: (() -> Unit)?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            addView(TextView(context).apply {
                text = title
                setTextColor(UiColors.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            if (subtitle != null) {
                addView(TextView(context).apply {
                    text = subtitle
                    setTextColor(UiColors.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
            }
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            content.addView(this)
        }

    private fun choice(title: String, current: String, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        row(title, current) {
            showChoice(title, items, selected) {
                onPick(it)
                render()
            }
        }
    }

    private fun toggle(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextColor(UiColors.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            if (subtitle != null) {
                addView(TextView(context).apply {
                    text = subtitle
                    setTextColor(UiColors.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
            }
        }
        val sw = SwitchCompat(this).apply {
            isChecked = value
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        box.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(sw)
        box.setOnClickListener { sw.toggle() }
        content.addView(box)
    }

    private fun text(title: String, current: String, hint: String, password: Boolean = false, onSet: (String) -> Unit) {
        row(title, current) {
            showInput(title, hint, "", password) {
                onSet(it)
                render()
            }
        }
    }

    private fun action(title: String, subtitle: String?, onClick: () -> Unit) {
        row(title, subtitle, onClick)
    }

    private fun openAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            runCatching { startActivity(intent) }.onFailure {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            showMessage("Bilgi", "Bu Android sürümünde depolama izni yeterlidir.")
        }
    }
}
