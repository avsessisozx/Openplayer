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
        TRANSLATE("Altyazı çevirisi", "Altyazıyı yapay zekâ veya çeviri servisiyle başka dile çevirin", R.drawable.ic_subtitles),
        MUSIC("Müzik çalar", "Videolardan ayrı müzik kütüphanesi ve çalar (isteğe bağlı)", R.drawable.ic_music_note),
        THEME("Tema ve görünüm", "Renk temaları, vurgu rengi, düğme biçimi, panel opaklığı", R.drawable.ic_tune),
        LIST("Liste ve dosyalar", "Yeni etiketi, gizli klasörler, dosya erişimi", R.drawable.ic_view_list),
    }

    private val exportTheme = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(ThemeManager.exportJson().toByteArray()) }
            android.widget.Toast.makeText(this, "Tema dışa aktarıldı", android.widget.Toast.LENGTH_SHORT).show()
        }.onFailure { android.widget.Toast.makeText(this, "Yazılamadı", android.widget.Toast.LENGTH_SHORT).show() }
    }

    private val importTheme = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes(4097).toString(Charsets.UTF_8) }
            }.getOrNull()
            if (text != null && ThemeManager.importJson(this, text)) {
                ThemeManager.reload(); recreate()
            } else {
                android.widget.Toast.makeText(this, "Geçersiz tema dosyası", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val exportAll = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(SettingsBackup.export(this).toByteArray()) }
            android.widget.Toast.makeText(this, "Ayarlar dışa aktarıldı", android.widget.Toast.LENGTH_SHORT).show()
        }.onFailure { android.widget.Toast.makeText(this, "Yazılamadı", android.widget.Toast.LENGTH_SHORT).show() }
    }

    private val importAll = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes(262_145).toString(Charsets.UTF_8) }
            }.getOrNull()
            if (text != null && SettingsBackup.import(this, text)) {
                android.widget.Toast.makeText(this, "Ayarlar yüklendi", android.widget.Toast.LENGTH_SHORT).show()
                recreate()
            } else {
                android.widget.Toast.makeText(this, "Geçersiz ayar dosyası", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val importFont = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val app = applicationContext
            Thread {
                val value = SubtitleLook.importFont(app, uri)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (value == null) {
                        android.widget.Toast.makeText(this, "Geçerli bir .ttf / .otf yazı tipi seçin", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        s.subtitleFont = value
                        android.widget.Toast.makeText(this, "Yazı tipi eklendi", android.widget.Toast.LENGTH_SHORT).show()
                        render()
                    }
                }
            }.start()
        }
    }

    private fun java.io.InputStream.readBytes(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private lateinit var s: AppSettings
    private lateinit var content: LinearLayout
    private var page: Page? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(UiScale.wrap(newBase))
    }

    override fun applyOverrideConfiguration(overrideConfiguration: android.content.res.Configuration?) {
        // AppCompat kendi yapılandırmasıyla yoğunluk ölçeğini ezmesin.
        if (overrideConfiguration != null) {
            val uiMode = overrideConfiguration.uiMode
            overrideConfiguration.setTo(baseContext.resources.configuration)
            overrideConfiguration.uiMode = uiMode
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = AppSettings.get(this)
        ThemeManager.applyWindow(this)
        page = intent.getStringExtra(EXTRA_PAGE)?.let { name -> Page.entries.firstOrNull { it.name == name } }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(32))
        }
        setContentView(
            ScrollView(this).apply {
                fitsSystemWindows = true
                setBackgroundColor(UiColors.background)
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
            Page.TRANSLATE -> translatePage()
            Page.LIST -> listPage()
            Page.THEME -> themePage()
            Page.MUSIC -> musicPage()
        }
    }

    private fun home() {
        Page.entries.forEach { p ->
            categoryRow(p.icon, p.title, p.summary) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, p.name))
            }
        }
        section("Yedekleme")
        action("Ayarları dışa aktar", "Tüm ayarları dosyaya kaydeder (parola ve API anahtarı hariç)") {
            exportAll.launch("openplayer-ayarlar.json")
        }
        action("Ayarları içe aktar", "Daha önce kaydettiğiniz ayar dosyasını yükler") {
            importAll.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        action("Tüm ayarları sıfırla", "Oynatıcı, hareket, altyazı, ses, tema ve liste ayarları varsayılana döner (geçmiş ve OpenSubtitles hesabı korunur)") {
            PanelBuilder(this)
                .setTitle("Tüm ayarlar sıfırlansın mı?")
                .setMessage("Bu işlem geri alınamaz.")
                .setPositiveButton("Sıfırla") { _, _ ->
                    s.resetAll(this)
                    ThemeManager.reload()
                    android.widget.Toast.makeText(this, "Ayarlar sıfırlandı", android.widget.Toast.LENGTH_SHORT).show()
                    recreate()
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
        section("Hata bildirimi")
        action("Hata raporunu kopyala", "Sürüm, cihaz, ayarlar (parola ve anahtarlar hariç), son çeviri ve uygulama hataları tek metinde panoya kopyalanır; Claude'a yapıştırın") {
            BugReport.copyToClipboard(this) { n ->
                android.widget.Toast.makeText(this, "Rapor panoya kopyalandı ($n karakter). Yapıştırabilirsiniz.", android.widget.Toast.LENGTH_LONG).show()
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
        section("Devam etme")
        choice("Kaldığı yerden devam et", s.resumeMode.label, ResumeMode.entries.map { it.label }, s.resumeMode.ordinal) {
            s.resumeMode = ResumeMode.entries[it]
        }
        toggle("Yalnız ilk videoda devam et", "Listede sonraki videolar baştan başlar", s.resumeOnlyFirst) { s.resumeOnlyFirst = it }
        section("Hız")
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        choice("Varsayılan oynatma hızı", "${s.playbackSpeed}x", speeds.map { "${it}x" }, speeds.indexOfFirst { kotlin.math.abs(it - s.playbackSpeed) < 0.01f }) {
            s.playbackSpeed = speeds[it]
        }
        toggle("Uzun basınca hızlı oynat", "Ekrana basılı tutunca hızlanır, bırakınca normale döner", s.longPressSpeed) { s.longPressSpeed = it }
        val fast = listOf(1.5f, 2f, 2.5f, 3f, 4f)
        choice("Uzun basma hızı", "${s.longPressSpeedValue}x", fast.map { "${it}x" }, fast.indexOfFirst { kotlin.math.abs(it - s.longPressSpeedValue) < 0.01f }) {
            s.longPressSpeedValue = fast[it]
        }
        section("Oynatma")
        toggle("Her video için ayarları hatırla", "Hız, görünüm, yakınlaştırma, altyazı eşitleme ve ses parçası videoya özel saklanır", s.perVideoMemory) { s.perVideoMemory = it }
        toggle("Bitince listeye dön", "Son video bitince oynatıcı kapanır", s.backToList) { s.backToList = it }
        toggle("Hatalı videoyu atla", "Açılamayan videoda hata göstermeden sonrakine geçer", s.skipOnError) { s.skipOnError = it }
        toggle("Çıkmak için geri tuşuna iki kez bas", null, s.doubleBackToExit) { s.doubleBackToExit = it }
        toggle("Sessizlikleri atla", "Videodaki sessiz bölümleri hızla geçer", s.skipSilence) { s.skipSilence = it }
        val intro = listOf(30, 60, 85, 90, 120, 180)
        choice("İntro atla süresi", "${s.skipIntroSec} sn (kısayol olarak eklenebilir)", intro.map { "$it sn" }, intro.indexOf(s.skipIntroSec)) {
            s.skipIntroSec = intro[it]
        }
        val edges = listOf(0, 12, 24, 36, 48)
        choice("Kenar koruması", if (s.edgeGuardDp == 0) "Kapalı" else "${s.edgeGuardDp} dp", edges.map { if (it == 0) "Kapalı" else "$it dp" }, edges.indexOf(s.edgeGuardDp)) {
            s.edgeGuardDp = edges[it]
        }
        toggle("Arka planda oynat", "Ana ekrana çıkınca ses çalmaya devam eder", s.backgroundPlay) { s.backgroundPlay = it }
        toggle("Otomatik pencere içinde pencere (PiP)", "Ana ekrana çıkınca küçük pencerede devam eder", s.autoPip) { s.autoPip = it }
        choice("PiP düğmeleri", if (s.pipSeekButtons) "Geri / ileri sar (±10 sn)" else "Önceki / sonraki video",
            listOf("Önceki / sonraki video", "Geri / ileri sar (±10 sn)"), if (s.pipSeekButtons) 1 else 0) { s.pipSeekButtons = it == 1 }
        section("Ekran")
        toggle("Ekranı açık tut", "Oynatırken ekran kararmaz", s.keepScreenOn) { s.keepScreenOn = it }
        toggle("Açılışta kontrolleri göster", null, s.showControlsAtStart) { s.showControlsAtStart = it }
        toggle("Parlaklığı hatırla", "Kaydırarak ayarladığınız parlaklık sonraki videolarda da kullanılır", s.rememberBrightness) { s.rememberBrightness = it }
        choice("Köşede sürekli göster", s.osdInfo.label, OsdInfo.entries.map { it.label }, s.osdInfo.ordinal) {
            s.osdInfo = OsdInfo.entries[it]
        }
        toggle("Üst çubukta saat ve pil", "Kontroller açıkken başlığın yanında görünür", s.topBarClock) { s.topBarClock = it }
        toggle("Kilitlenince ekran yönünü sabitle", null, s.lockRotation) { s.lockRotation = it }
        section("Düğmeler")
        toggle("Önceki / sonraki düğmeleri", null, s.showPrevNext) { s.showPrevNext = it }
        toggle("İleri / geri sarma düğmeleri", "Oynat düğmesinin iki yanında", s.showSeekButtons) { s.showSeekButtons = it }
        val steps = listOf(5, 10, 15, 20, 30, 60)
        choice("Sarma düğmesi aralığı", "${s.seekButtonSec} sn", steps.map { "$it sn" }, steps.indexOf(s.seekButtonSec)) {
            s.seekButtonSec = steps[it]
        }
        section("Görünüm")
        toggle("Kalan süreyi göster", "Kapalıyken toplam süre gösterilir", s.showRemainingTime) { s.showRemainingTime = it }
        toggle("Kısayol satırı açık başlasın", "Kapalıyken oynatıcıda alt soldaki düğmeyle açılır. Hangilerinin görüneceğini \"Öğeleri özelleştir\" ile seçin.", s.showShortcuts) { s.showShortcuts = it }
        val sizes = listOf(50, 60, 70, 80, 90, 100, 120, 150)
        choice("Kısayol düğme boyutu", "%${s.shortcutSizePercent}", sizes.map { "%$it" }, sizes.indexOf(s.shortcutSizePercent)) {
            s.shortcutSizePercent = sizes[it]
        }
        val ops = listOf(0, 10, 20, 35, 50, 70, 100)
        choice("Kısayol düğme opaklığı", "%${s.shortcutOpacityPercent} (düşük = şeffaf)", ops.map { "%$it" }, ops.indexOf(s.shortcutOpacityPercent)) {
            s.shortcutOpacityPercent = ops[it]
        }
        val scrims = listOf(0, 10, 20, 30, 45, 60, 80)
        choice("Üst/alt şerit karartması", "%${s.scrimPercent}", scrims.map { "%$it" }, scrims.indexOf(s.scrimPercent)) {
            s.scrimPercent = scrims[it]
        }
        val hudMs = listOf(0, 100, 200, 300, 500, 700, 1000, 1500, 2000, 3000)
        choice("Göstergelerin kaybolma süresi", "${s.hudHideMs} ms", hudMs.map { "$it ms" }, hudMs.indexOf(s.hudHideMs)) {
            s.hudHideMs = hudMs[it]
        }
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
        note("Çizgileri sürükleyerek şeritlerin sınırını ayarlayın. Çizgileri üst üste getirirseniz orta şerit olmaz. Ekranın alt kenarındaki altyazı bölgesi bundan bağımsızdır.")
        content.addView(ZoneEditor(this, prefs), LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(8)) })
        for (zone in GesturePrefs.zones) {
            choice("${GesturePrefs.zoneLabel(zone)} şerit", prefs.get(zone).label,
                GestureAction.entries.map { it.label }, prefs.get(zone).ordinal) {
                prefs.set(zone, GestureAction.entries[it])
            }
        }
        action("Bölgeleri sıfırla", "Sol parlaklık, sağ ses, orta kapalı; sınırlar ortada") {
            prefs.set("left", GestureAction.BRIGHTNESS)
            prefs.set("mid", GestureAction.NONE)
            prefs.set("right", GestureAction.VOLUME)
            prefs.split1 = 0.5f
            prefs.split2 = 0.5f
            render()
        }
        section("Sarma")
        toggle("Sararken canlı önizleme", "Çubuğu veya ekranı kaydırırken görüntü akar; bırakınca tam konuma gider", s.liveScrub) { s.liveScrub = it }
        toggle("Bırakınca tam konuma git", "Kapalıyken en yakın anahtar kareye gider: daha akıcı, birkaç sn sapabilir", s.exactSeekOnRelease) { s.exactSeekOnRelease = it }
        toggle("Yatay kaydırma ile sarma", null, s.horizontalSeekEnabled) { s.horizontalSeekEnabled = it }
        val swipeOptions = listOf(30, 60, 90, 120, 180, 300)
        choice("Ekranı baştan sona kaydırınca sarma", "${s.swipeSeekSec} sn", swipeOptions.map { "$it sn" }, swipeOptions.indexOf(s.swipeSeekSec)) {
            s.swipeSeekSec = swipeOptions[it]
        }
        section("Dokunma")
        choice("Tek dokunma", s.tapAction.label, TapAction.entries.map { it.label }, s.tapAction.ordinal) {
            s.tapAction = TapAction.entries[it]
        }
        choice("Çift dokunma", s.doubleTapAction.label, DoubleTapAction.entries.map { it.label }, s.doubleTapAction.ordinal) {
            s.doubleTapAction = DoubleTapAction.entries[it]
        }
        val tapOptions = listOf(5, 10, 15, 20, 30)
        choice("Çift dokunma ile sarma süresi", "${s.doubleTapSeekSec} sn", tapOptions.map { "$it sn" }, tapOptions.indexOf(s.doubleTapSeekSec)) {
            s.doubleTapSeekSec = tapOptions[it]
        }
        section("Yakınlaştırma ve altyazı")
        toggle("Çimdikle yakınlaştırma ve kaydırma", null, s.pinchZoomEnabled) { s.pinchZoomEnabled = it }
        toggle("Hızlı yakınlaştırma", "Çimdiği bırakınca %100, 125, 150, 200, 300 veya 500 adımlarından en yakınına oturur", s.quickZoom) { s.quickZoom = it }
        toggle("Altyazı hareketleri", "Alt şeritte: yatay = önceki/sonraki satır, dikey = konum, çimdik = boyut", s.subtitleGestures) { s.subtitleGestures = it }
    }

    private fun videoPage() {
        section("Çözücü")
        choice("Varsayılan çözücü", s.decoder.label, DecoderMode.entries.map { it.label }, DecoderMode.entries.indexOf(s.decoder)) {
            s.decoder = DecoderMode.entries[it]
        }
        choice("Oynatma motoru", s.playbackEngine.label, PlaybackEngine.entries.map { it.label }, s.playbackEngine.ordinal) {
            s.playbackEngine = PlaybackEngine.entries[it]
        }
        toggle("Açılamazsa otomatik dene", "Video açılmazsa çözücüler sırayla (HW → HW+ → SW) sormadan denenir; ayarınız değişmez", s.autoDecoderFallback) { s.autoDecoderFallback = it }
        note("VLC motoru, ExoPlayer'ın açamadığı eski/nadir biçimler için yedektir (WMV, RealMedia, bozuk AVI vb.). Daha sade bir arayüzü vardır: oynat/duraklat, sarma, ses ve altyazı seçimi, hız, dış altyazı.")
        note("HW: sistemin donanım çözücüsü, en az pil harcar. HW+: donanımın desteklemediği biçimlerde FFmpeg kullanır. SW: önce FFmpeg ile işlemci çözmesi. Oynatma sırasında üstteki etiketten de değiştirilebilir.")
    }

    private fun audioPage() {
        section("Ses seviyesi")
        val boosts = listOf(100, 125, 150, 175, 200)
        choice("Ses artırma", "%${s.volumeBoostPercent}", boosts.map { "%$it" }, boosts.indexOf(s.volumeBoostPercent)) {
            s.volumeBoostPercent = boosts[it]
        }
        note("Yazılımsal artırmadır; yüksek değerlerde bozulma olabilir. Oynatıcıdaki ses menüsünden de ayarlanır.")
        section("Kanallar ve efektler")
        choice("Stereo modu", s.stereoMode.label, StereoMode.entries.map { it.label }, s.stereoMode.ordinal) {
            s.stereoMode = StereoMode.entries[it]
            AudioBalance.update(s)
        }
        choice("Yankı (reverb)", AudioFx.reverbLabels[s.reverbPreset], AudioFx.reverbLabels, s.reverbPreset) {
            s.reverbPreset = it
        }
        toggle("Sesi yumuşak başlat", "Oynatma başlarken ve sarınca ses kısa sürede yükselir", s.audioFade) { s.audioFade = it }
        section("Diğer uygulamalar ve kulaklık")
        toggle("Tek başına çal", "Başka uygulama ses çalınca veya arama gelince durur (kapalıysa birlikte çalar)", s.audioFocus) { s.audioFocus = it }
        toggle("Kulaklık çıkınca duraklat", "Kablolu veya Bluetooth kulaklık ayrılınca", s.pauseOnNoisy) { s.pauseOnNoisy = it }
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
        val sizes = listOf(12, 14, 16, 18, 20, 22, 24, 28, 32, 36, 42, 48)
        choice("Yazı boyutu", "${s.subtitleSizeSp.toInt()}", sizes.map { "$it" }, sizes.indexOf(s.subtitleSizeSp.toInt())) {
            s.subtitleSizeSp = sizes[it].toFloat()
        }
        val colors = listOf("Beyaz" to 0xFFFFFFFF.toInt(), "Sarı" to 0xFFFFE600.toInt(), "Yeşil" to 0xFF7CFF7C.toInt(), "Camgöbeği" to 0xFF66E5FF.toInt(),
            "Turuncu" to 0xFFFFB347.toInt(), "Pembe" to 0xFFFF8AD8.toInt(), "Açık gri" to 0xFFD0D0D0.toInt())
        choice("Yazı rengi", colors.firstOrNull { it.second == s.subtitleColor }?.first ?: "Özel", colors.map { it.first },
            colors.indexOfFirst { it.second == s.subtitleColor }) { s.subtitleColor = colors[it].second }
        val bgs = listOf("Şeffaf" to 0x00000000, "Yarı saydam" to 0x99000000.toInt(), "Siyah" to 0xFF000000.toInt())
        choice("Arka plan", bgs.firstOrNull { it.second == s.subtitleBackground }?.first ?: "Özel", bgs.map { it.first },
            bgs.indexOfFirst { it.second == s.subtitleBackground }) { s.subtitleBackground = bgs[it].second }
        val fonts = SubtitleLook.builtInFonts + SubtitleLook.importedFonts(this).map { "file:$it" to it }
        choice("Yazı tipi", SubtitleLook.fontLabel(this, s.subtitleFont), fonts.map { it.second } + "＋ Dosyadan yazı tipi ekle (.ttf / .otf)",
            fonts.indexOfFirst { it.first == s.subtitleFont }) { i ->
            if (i < fonts.size) s.subtitleFont = fonts[i].first
            else importFont.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream", "*/*"))
        }
        if (s.subtitleFont.startsWith("file:")) {
            action("Bu yazı tipini kaldır", SubtitleLook.fontLabel(this, s.subtitleFont)) {
                SubtitleLook.deleteFont(this, s.subtitleFont.removePrefix("file:"))
                s.subtitleFont = "default"
                render()
            }
        }
        toggle("Kalın yazı", null, s.subtitleBold) { s.subtitleBold = it }
        val edges = SubtitleLook.edges
        choice("Kenar", edges.firstOrNull { it.first == s.subtitleEdge }?.second ?: "Kenar çizgisi", edges.map { it.second },
            edges.indexOfFirst { it.first == s.subtitleEdge }) { s.subtitleEdge = edges[it].first }
        val edgeColors = listOf("Siyah" to 0xFF000000.toInt(), "Koyu gri" to 0xFF333333.toInt(), "Beyaz" to 0xFFFFFFFF.toInt(),
            "Mavi" to 0xFF1E40AF.toInt(), "Kırmızı" to 0xFFB91C1C.toInt())
        choice("Kenar rengi", edgeColors.firstOrNull { it.second == s.subtitleEdgeColor }?.first ?: "Özel", edgeColors.map { it.first },
            edgeColors.indexOfFirst { it.second == s.subtitleEdgeColor }) { s.subtitleEdgeColor = edgeColors[it].second }
        choice("Hizalama", SubtitleLook.alignLabels[s.subtitleAlign], SubtitleLook.alignLabels, s.subtitleAlign) { s.subtitleAlign = it }
        toggle("\"/\" ile başlayan satırları italik yap", "Bazı altyazılarda italik yerine satır başına \"/\" konur", s.subtitleItalicSlash) { s.subtitleItalicSlash = it }
        section("Senkron")
        val delays = listOf(-2000L, -1000L, -500L, -250L, 0L, 250L, 500L, 1000L, 2000L)
        choice("Varsayılan altyazı gecikmesi", "${s.subtitleDefaultDelayMs} ms", delays.map { (if (it > 0) "+" else "") + "$it ms" }, delays.indexOf(s.subtitleDefaultDelayMs)) {
            s.subtitleDefaultDelayMs = delays[it]
        }
        note("+ altyazıyı geç gösterir, − erken gösterir. Her video bu değerle başlar; oynatırken altyazı menüsünden değiştirilebilir.")
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

    private fun translatePage() {
        section("Sağlayıcı")
        val provs = TrProvider.entries
        choice("Çeviri sağlayıcısı", s.trProvider.label, provs.map { it.label }, s.trProvider.ordinal) { s.trProvider = provs[it] }
        section("Diller")
        val langs = TrLangs.all
        choice("Hedef dil", TrLangs.label(s.trTarget), langs.map { it.tr }, langs.indexOfFirst { it.code == s.trTarget }) { s.trTarget = langs[it].code }
        val sources = listOf("auto") + langs.map { it.code }
        choice("Kaynak dil", TrLangs.label(s.trSource), sources.map { TrLangs.label(it) }, sources.indexOf(s.trSource)) { s.trSource = sources[it] }
        note("Çoğu altyazı İngilizcedir. MyMemory'de \"Otomatik algıla\" her zaman güvenilir değildir; kaynak dili elle seçmek daha sağlamdır.")
        when (s.trProvider) {
            TrProvider.MYMEMORY -> {
                section("MyMemory")
                text("E-posta (isteğe bağlı)", s.trEmail.ifEmpty { "Girilmedi (günde 5.000 karakter)" }, "ornek@eposta.com") { s.trEmail = it }
                note("Hesap ya da anahtar gerekmez. E-posta yalnız günlük kotayı 50.000 karaktere çıkarır, doğrulama yapılmaz. " +
                    "Uzun filmler için kota dar kalabilir; o zaman yapay zekâ ya da kendi LibreTranslate adresinizi kullanın.")
            }
            TrProvider.LIBRE -> {
                section("LibreTranslate")
                text("Adres", s.trApiUrl.ifEmpty { "Girilmedi" }, "https://sunucunuz.example.com") { s.trApiUrl = it }
                text("API anahtarı (isteğe bağlı)", if (s.trApiKey.isEmpty()) "Girilmedi" else "••••••••", "Sunucu istiyorsa girin", password = true) { s.trApiKey = it }
                note("Kendi sunucunuzun ya da anahtar veren bir LibreTranslate servisinin adresi. Adresin sonuna /translate eklenmesi gerekmez.")
            }
            TrProvider.OPENAI -> {
                section("Hazır ayarlar (adres ve modeli doldurur)")
                action("Groq", "Ücretsiz anahtar: console.groq.com") { preset("https://api.groq.com/openai/v1", "llama-3.3-70b-versatile") }
                action("OpenRouter", "Ücretsiz modeller var: openrouter.ai") { preset("https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free") }
                action("Google Gemini", "Ücretsiz anahtar: aistudio.google.com") { preset("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash") }
                action("Ollama (aynı ağdaki bilgisayar)", "Adresteki IP'yi kendi bilgisayarınıza göre düzeltin") { preset("http://192.168.1.10:11434/v1", "llama3.1") }
                section("Bağlantı")
                text("Adres", s.trApiUrl.ifEmpty { "Girilmedi" }, "https://api.ornek.com/v1") { s.trApiUrl = it }
                text("API anahtarı", if (s.trApiKey.isEmpty()) "Girilmedi" else "••••••••", "Sağlayıcınızdan alın", password = true) { s.trApiKey = it }
                text("Model", s.trModel.ifEmpty { "Girilmedi" }, "örn. llama-3.3-70b-versatile") { s.trModel = it }
                action("Model listesini getir", "Sağlayıcının güncel modellerinden seçin (model adları zamanla değişir)") { pickModel() }
                note("OpenAI uyumlu (/chat/completions) her adres çalışır. Anahtar cihazda şifrelenerek saklanır ve ayar yedeğine yazılmaz.")
            }
        }
        section("Deneme")
        action("Bağlantıyı dene", "İki örnek cümleyi çevirir") { testTranslate() }
        note("Çeviri sırasında altyazı metni seçtiğiniz servise gönderilir. Biçim (italik, renk) çevrilen altyazıda korunmaz.")
    }

    private fun preset(url: String, model: String) {
        s.trApiUrl = url
        s.trModel = model
        android.widget.Toast.makeText(this, "Adres ve model dolduruldu; şimdi API anahtarınızı girin", android.widget.Toast.LENGTH_LONG).show()
        render()
    }

    private fun pickModel() {
        val cfg = s.translateConfig()
        Thread {
            val result = runCatching { SubtitleTranslator(cfg).listModels() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { ids ->
                    if (ids.isEmpty()) showMessage("Model bulunamadı", "Sağlayıcı boş bir liste döndürdü.")
                    else {
                        val shown = ids.take(200)
                        showChoice("Model seçin", shown, shown.indexOf(s.trModel)) { i ->
                            s.trModel = shown[i]
                            render()
                        }
                    }
                }.onFailure { showMessage("Liste alınamadı", it.message ?: "Bilinmeyen hata") }
            }
        }.start()
    }

    private fun testTranslate() {
        val cfg = s.translateConfig()
        val sample = listOf("Hello, how are you?", "I'll be right back.")
        Thread {
            val result = runCatching { SubtitleTranslator(cfg).translate(sample) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { out ->
                    showMessage("Bağlantı başarılı", sample.indices.joinToString("\n\n") { "${sample[it]}\n→ ${out[it]}" })
                }.onFailure { showMessage("Deneme başarısız", it.message ?: "Bilinmeyen hata") }
            }
        }.start()
    }

    private fun onlinePage() {
        section("OpenSubtitles hesabı")
        text("API anahtarı", if (s.osApiKey.isEmpty()) "Girilmedi" else "••••••••", "opensubtitles.com hesabınızdan alın") { s.osApiKey = it }
        text("Kullanıcı adı", s.osUser.ifEmpty { "İsteğe bağlı" }, "İndirme için gerekebilir") { s.osUser = it }
        text("Parola", if (s.osPass.isEmpty()) "İsteğe bağlı" else "••••••••", "Parola", password = true) { s.osPass = it }
        note("Bilgiler cihazda şifrelenerek saklanır. Anahtarı opensubtitles.com/consumers adresinden ücretsiz alabilirsiniz.")
    }

    private fun musicPage() {
        section("Müzik modu")
        toggle("Müzik çaları etkinleştir", "Açınca ana ekranda \"Müzik\" sekmesi görünür. Müzik izni ilk açtığınızda istenir.", s.musicMode) { s.musicMode = it }
        toggle("Açılışta Kitaplık ekranı", "Kapalıyken müzik doğrudan Şarkılar sekmesiyle açılır", s.musicStartHome) { s.musicStartHome = it }
        val sorts = MusicSort.entries
        choice("Varsayılan sıralama", s.musicSort.label, sorts.map { it.label }, sorts.indexOf(s.musicSort)) {
            s.musicSort = sorts[it]
        }
        note("Müzik; albüm, sanatçı ve klasör görünümleri, \"Çalıyor\" ekranı, kuyruk, uyku zamanlayıcısı, ekolayzır ve bildirimden denetim sunar. Bluetooth ve kilit ekranı düğmeleri çalışır.")
    }

    private fun themeApplied() {
        ThemeManager.reload()
        recreate()
    }

    private fun themePage() {
        section("Renk teması")
        val all = ThemeManager.presets
        all.chunked(3).forEach { rowThemes ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14), dp(4), dp(14), dp(4))
            }
            for (i in 0 until 3) {
                val th = rowThemes.getOrNull(i)
                if (th == null) {
                    line.addView(View(this), LinearLayout.LayoutParams(0, dp(72), 1f))
                    continue
                }
                val sel = s.themeId == th.id
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(th.surface)
                        setStroke(dp(if (sel) 3 else 1), if (sel) th.accent else 0x33FFFFFF)
                    }
                    addView(View(context).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(th.accent)
                        }
                    }, LinearLayout.LayoutParams(dp(22), dp(22)))
                    addView(TextView(context).apply {
                        text = th.name
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, dp(6), 0, 0)
                    })
                    setOnClickListener { s.themeId = th.id; themeApplied() }
                }
                line.addView(card, LinearLayout.LayoutParams(0, dp(72), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
            content.addView(line)
        }
        if (s.themeId == "custom") note("Şu an içe aktarılmış özel tema kullanılıyor.")
        section("Vurgu rengi")
        val accents = ThemeManager.accentChoices
        val accIdx = accents.indexOfFirst { it.second == s.accentOverride }.coerceAtLeast(0)
        choice("Vurgu rengi", accents[accIdx].first, accents.map { it.first }, accIdx) {
            s.accentOverride = accents[it].second
            themeApplied()
        }
        choice("Arayüz modu", s.uiMode.label, UiMode.entries.map { it.label }, s.uiMode.ordinal) {
            s.uiMode = UiMode.entries[it]
            recreate()
        }
        note("Otomatik: ekran boyutuna göre ayarlanır. Telefonda tablet düzeni görmek veya tersi için elle seçin.")
        section("Düğmeler ve paneller")
        choice("Düğme biçimi", s.buttonShape.label, ButtonShape.entries.map { it.label }, s.buttonShape.ordinal) {
            s.buttonShape = ButtonShape.entries[it]
        }
        val pan = listOf(40, 50, 60, 70, 80, 90, 100)
        choice("Menü opaklığı", "%${s.panelOpacityPercent} (düşük = şeffaf)", pan.map { "%$it" }, pan.indexOf(s.panelOpacityPercent)) {
            s.panelOpacityPercent = pan[it]
        }
        section("Oynatıcı kısayolları")
        val sizes = listOf(50, 60, 70, 80, 90, 100, 120, 150)
        choice("Kısayol düğme boyutu", "%${s.shortcutSizePercent}", sizes.map { "%$it" }, sizes.indexOf(s.shortcutSizePercent)) {
            s.shortcutSizePercent = sizes[it]
        }
        val ops = listOf(0, 10, 20, 35, 50, 70, 100)
        choice("Kısayol düğme opaklığı", "%${s.shortcutOpacityPercent}", ops.map { "%$it" }, ops.indexOf(s.shortcutOpacityPercent)) {
            s.shortcutOpacityPercent = ops[it]
        }
        val scrims = listOf(0, 10, 20, 30, 45, 60, 80)
        choice("Üst/alt şerit karartması", "%${s.scrimPercent}", scrims.map { "%$it" }, scrims.indexOf(s.scrimPercent)) {
            s.scrimPercent = scrims[it]
        }
        section("Tema dosyası")
        action("Temayı dışa aktar", "Geçerli renkleri JSON dosyası olarak kaydeder, paylaşabilirsiniz") {
            exportTheme.launch("openplayer-tema.json")
        }
        action("Tema içe aktar", "Başkasının paylaştığı tema dosyasını yükler") {
            importTheme.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        action("Varsayılana dön", "Tema, vurgu rengi ve düğme biçimini sıfırlar") {
            s.themeId = "dark"; s.accentOverride = 0; s.buttonShape = ButtonShape.CIRCLE; s.panelOpacityPercent = 80
            themeApplied()
        }
    }

    private fun listPage() {
        section("Liste")
        toggle("Silince çöp kutusuna taşı", "Android 11 ve üstünde silinen videolar geri alınabilir (30 gün). Kapalıyken kalıcı silinir.", s.useTrash) { s.useTrash = it }
        toggle("Silerken altyazıyı da sil", "Videonun yanındaki aynı adlı altyazı dosyaları da silinir. Yalnız kalıcı silmede çalışır; çöp kutusuna taşırken altyazılara dokunulmaz.", s.deleteSubtitlesToo) { s.deleteSubtitlesToo = it }
        toggle("Ana menüde çıkış düğmesi", "Menüye uygulamayı tamamen kapatan \"Çıkış\" eklenir", s.exitButton) { s.exitButton = it }
        toggle("Doğal sıralama", "\"Bölüm 2\", \"Bölüm 10\"dan önce gelir", s.naturalSort) { s.naturalSort = it }
        val days = listOf(1, 3, 7, 14, 30)
        choice("\"Yeni\" etiketi süresi", "${s.newVideoDays} gün", days.map { "$it gün" }, days.indexOf(s.newVideoDays)) {
            s.newVideoDays = days[it]
        }
        toggle("360° videoları listede göster", "Kapalıyken 360° videolar listelerden gizlenir; açıkken \"360°\" etiketi görünür", s.show360) { s.show360 = it }
        val tsizes = listOf(70, 85, 100, 125, 160)
        val tnames = listOf("Çok küçük", "Küçük", "Normal", "Büyük", "Çok büyük")
        choice("Liste yazı boyutu", tnames.getOrElse(tsizes.indexOf(s.listTextScale)) { "%${s.listTextScale}" }, tnames, tsizes.indexOf(s.listTextScale).coerceAtLeast(0)) {
            s.listTextScale = tsizes[it]
        }
        action("Simge boyutlarını sıfırla", "Listede iki parmakla yaptığınız boyutlandırmayı varsayılana döndürür") {
            s.listScale = 100; s.gridScale = 100; s.folderScale = 100; s.listTextScale = 100; s.musicScale = 100
            android.widget.Toast.makeText(this, "Boyutlar sıfırlandı", android.widget.Toast.LENGTH_SHORT).show()
        }
        toggle("Gizli klasörleri göster", "Klasöre uzun basarak gizleyebilirsiniz", s.showHiddenFolders) { s.showHiddenFolders = it }
        section("Son oynatılan")
        toggle("Her klasörde son izleneni işaretle", "Kapalıyken yalnız en son izlenen video vurgulanır", s.markLastInFolder) { s.markLastInFolder = it }
        toggle("Klasör açılınca son izlenene kaydır", null, s.scrollToLast) { s.scrollToLast = it }
        toggle("Yüzen oynat düğmesi", "Sağ alttaki düğme son oynatılan videoyu açar", s.showFab) { s.showFab = it }
        toggle("Aşağı çekerek yenile", "Listenin en üstünde aşağı çekince videolar yeniden taranır", s.swipeRefresh) { s.swipeRefresh = it }
        section("Gizlilik")
        toggle("Gizli mod", "Açıkken kaldığı yer ve geçmiş kaydedilmez", s.incognito) { s.incognito = it }
        action("Geçmişi temizle", "Kaldığı yerleri, izlenme durumunu ve yer imlerini siler") {
            ResumeStore.clearAll(this)
            android.widget.Toast.makeText(this, "Geçmiş temizlendi", android.widget.Toast.LENGTH_SHORT).show()
        }
        section("Ağ")
        text("HTTP User-Agent", s.httpUserAgent.ifEmpty { "Varsayılan (OpenPlayer/1.0)" }, "Boş bırakırsanız varsayılan kullanılır") { s.httpUserAgent = it }
        note("Ağ akışlarında sunucuya gönderilen tanıtım metni. Uygulama yeniden açılınca geçerli olur.")
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
