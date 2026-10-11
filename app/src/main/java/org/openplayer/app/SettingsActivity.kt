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

/** Tüm ayarlar tek sayfada, bölümlere ayrılmış. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var s: AppSettings
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = AppSettings.get(this)
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
        content.addView(titleBar())

        section("Çözücü")
        choice("Varsayılan çözücü", s.decoder.label, DecoderMode.entries.map { it.label }, DecoderMode.entries.indexOf(s.decoder)) {
            s.decoder = DecoderMode.entries[it]
        }
        note("HW: sistemin donanım çözücüsü. HW+: donanım, desteklemediği biçimde FFmpeg. SW: önce FFmpeg ile işlemci çözmesi. Oynatma sırasında üstteki etiketten de değiştirilebilir.")

        section("Oynatıcı")
        toggle("Arka planda oynat", "Ana ekrana çıkınca ses çalmaya devam eder", s.backgroundPlay) { s.backgroundPlay = it }
        toggle("Otomatik pencere içinde pencere (PiP)", "Ana ekrana çıkınca küçük pencerede devam eder", s.autoPip) { s.autoPip = it }
        toggle("Kaldığı yerden devam et", null, s.resumePlayback) { s.resumePlayback = it }
        toggle("Kalan süreyi göster", "Kapalıyken toplam süre gösterilir", s.showRemainingTime) { s.showRemainingTime = it }
        toggle("Kısayol satırını göster", null, s.showShortcuts) { s.showShortcuts = it }
        choice("Ekran yönü", s.orientation.label, OrientationMode.entries.map { it.label }, s.orientation.ordinal) {
            s.orientation = OrientationMode.entries[it]
        }
        val timeouts = listOf(2, 3, 4, 5, 8, 10)
        choice("Kontrollerin kapanma süresi", "${s.controlsTimeoutSec} sn", timeouts.map { "$it sn" }, timeouts.indexOf(s.controlsTimeoutSec)) {
            s.controlsTimeoutSec = timeouts[it]
        }

        section("Hareketler")
        val prefs = GesturePrefs(this)
        for (zone in GesturePrefs.zones) {
            choice("${GesturePrefs.zoneLabel(zone)} bölge (dikey kaydırma)", prefs.get(zone).label,
                GestureAction.entries.map { it.label }, prefs.get(zone).ordinal) {
                prefs.set(zone, GestureAction.entries[it])
            }
        }
        toggle("Yatay kaydırma ile sarma", null, s.horizontalSeekEnabled) { s.horizontalSeekEnabled = it }
        val swipeOptions = listOf(30, 60, 90, 120, 180, 300)
        choice("Ekranı baştan sona kaydırınca sarma", "${s.swipeSeekSec} sn", swipeOptions.map { "$it sn" }, swipeOptions.indexOf(s.swipeSeekSec)) {
            s.swipeSeekSec = swipeOptions[it]
        }
        choice("Çift dokunma", s.doubleTapAction.label, DoubleTapAction.entries.map { it.label }, s.doubleTapAction.ordinal) {
            s.doubleTapAction = DoubleTapAction.entries[it]
        }
        val tapOptions = listOf(5, 10, 15, 20, 30)
        choice("Çift dokunma ile sarma süresi", "${s.doubleTapSeekSec} sn", tapOptions.map { "$it sn" }, tapOptions.indexOf(s.doubleTapSeekSec)) {
            s.doubleTapSeekSec = tapOptions[it]
        }
        toggle("Çimdikle yakınlaştırma ve kaydırma", null, s.pinchZoomEnabled) { s.pinchZoomEnabled = it }
        toggle("Altyazı hareketleri", "Alt şeritte: yatay = önceki/sonraki satır, dikey = konum, çimdik = boyut", s.subtitleGestures) { s.subtitleGestures = it }

        section("Altyazı")
        toggle("Altyazıyı otomatik seç", "Videonun yanındaki dosya veya tercih ettiğiniz dildeki gömülü parça", s.autoEnableSubtitle) { s.autoEnableSubtitle = it }
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
        val charsets = listOf("auto", "UTF-8", "windows-1254", "ISO-8859-9", "windows-1252", "windows-1250", "windows-1251")
        choice("Karakter kodlaması", s.subtitleCharset, charsets, charsets.indexOf(s.subtitleCharset)) {
            s.subtitleCharset = charsets[it]
        }
        val fallbacks = charsets.drop(2)
        choice("Otomatik algılama yedeği", s.subtitleFallbackCharset, fallbacks, fallbacks.indexOf(s.subtitleFallbackCharset)) {
            s.subtitleFallbackCharset = fallbacks[it]
        }
        text("Tercih edilen altyazı dilleri", s.subtitleLanguages, "Virgülle ayırın, örn. tr,en") { s.subtitleLanguages = it.ifBlank { "tr,en" } }
        text("OpenSubtitles API anahtarı", if (s.osApiKey.isEmpty()) "Girilmedi" else "••••••••", "opensubtitles.com hesabınızdan alın") { s.osApiKey = it }
        text("OpenSubtitles kullanıcı adı", s.osUser.ifEmpty { "İsteğe bağlı" }, "İndirme için gerekebilir") { s.osUser = it }
        text("OpenSubtitles parola", if (s.osPass.isEmpty()) "İsteğe bağlı" else "••••••••", "Parola", password = true) { s.osPass = it }

        section("Liste")
        val days = listOf(1, 3, 7, 14, 30)
        choice("\"Yeni\" etiketi süresi", "${s.newVideoDays} gün", days.map { "$it gün" }, days.indexOf(s.newVideoDays)) {
            s.newVideoDays = days[it]
        }
        toggle("Gizli klasörleri göster", "Klasöre uzun basarak gizleyebilirsiniz", s.showHiddenFolders) { s.showHiddenFolders = it }

        section("Dosya erişimi")
        action("Tüm dosyalara erişim izni", "Videonun yanındaki altyazıları otomatik bulmak için gerekir") { openAllFilesAccess() }

        section("Hakkında")
        action("OpenPlayer ${BuildConfigVersion.name}", "AGPLv3 lisanslı açık kaynak yazılım. Kaynak kodu için dokunun.") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/avsessisozx/Openplayer")))
        }
    }

    // ------------------------------------------------------------ satır türleri

    private fun titleBar(): View = TextView(this).apply {
        text = "Ayarlar"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(20), dp(20), dp(20), dp(12))
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

/** Sürüm adı tek yerde tutulur. */
object BuildConfigVersion {
    const val name = "0.3.0"
}
